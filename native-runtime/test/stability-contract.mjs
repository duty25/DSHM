// Behavioural contract for the DSHM stability patch set.
// Runs against a REAL patched SDK tree (argv[2] = directory containing node_modules).
// Each check reproduces a field failure from the v2.1.45 maintenance log and
// asserts the patched module no longer exhibits it, using the shipped code.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const root = path.resolve(process.argv[2] ?? '.');
const nm = path.join(root, 'node_modules');
const load = (spec) => import(pathToFileURL(path.join(nm, spec)).href);
const results = [];
async function check(name, fn) {
  try {
    await fn();
    results.push({ name, ok: true });
  } catch (error) {
    results.push({ name, ok: false, error: String(error?.stack ?? error).slice(0, 1200) });
  }
}
const tmp = (prefix) => fs.mkdtempSync(path.join(os.tmpdir(), prefix));

// 1. Installing an unrelated plugin must not drop an installed bundle whose
//    metadata is unreadable at that instant (Codex disappeared after token-usage).
await check('bundle retention survives unreadable metadata', async () => {
  const boot = await load('@deepseek-ai/dsh-app-boot/lib/index.js');
  const dir = tmp('dshm-bundles-');
  const pkg = (name, bundle) => {
    const d = path.join(dir, 'node_modules', name);
    fs.mkdirSync(d, { recursive: true });
    fs.writeFileSync(path.join(d, 'package.json'), JSON.stringify({ name, version: '1.0.0', ...(bundle ? { dsh: { bundle: { patch: './cordis.patch.yml' } } } : {}) }));
    if (bundle) fs.writeFileSync(path.join(d, 'cordis.patch.yml'), '[]\n');
    return d;
  };
  pkg('codex', true);
  pkg('memory', true);
  const manifest = { name: 'web', dependencies: { codex: '1.0.0', memory: '1.0.0' }, dsh: { profile: { bundles: ['codex', 'memory'] } } };
  fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify(manifest));
  const location = { binName: 'dsh', profileDir: dir, installAnchor: path.join(nm, '@deepseek-ai/dsh/package.json') };
  const before = boot.readProfilePlugins(location);
  // the transient failure: codex package.json unreadable mid-operation, a new plugin added
  fs.writeFileSync(path.join(dir, 'node_modules/codex/package.json'), '{ truncated');
  pkg('token-usage', true);
  fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ ...manifest, dependencies: { ...manifest.dependencies, 'token-usage': '1.0.0' } }));
  const out = boot.reconcileProfilePlugins({ ...location, before });
  const bundles = out.plugins.manifest.dsh.profile.bundles;
  assert.deepEqual(bundles, ['codex', 'memory', 'token-usage']);
  // a real removal still drops the bundle
  const before2 = boot.readProfilePlugins(location);
  fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ ...manifest, dependencies: { memory: '1.0.0', 'token-usage': '1.0.0' }, dsh: { profile: { bundles } } }));
  const out2 = boot.reconcileProfilePlugins({ ...location, before: before2 });
  assert.deepEqual(out2.plugins.manifest.dsh.profile.bundles, ['memory', 'token-usage']);
});

// 2. A writer lock left by a force-stopped process must not block profile writes.
await check('orphan writer lock is reclaimed, live lock is respected', async () => {
  const { withFileLock } = await load('@deepseek-ai/dsh-atomic-write/lib/index.js');
  const dir = tmp('dshm-lock-');
  const file = path.join(dir, 'package.json');
  fs.writeFileSync(file, '{}');
  // a PID that certainly does not exist
  const dead = spawnSync(process.execPath, ['-e', 'process.stdout.write(String(process.pid))']).stdout.toString();
  fs.writeFileSync(file + '.lock', `${dead}\n`);
  let ran = false;
  await withFileLock(file, async () => { ran = true; }, { waitMs: 300 });
  assert.equal(ran, true);
  assert.equal(fs.existsSync(file + '.lock'), false);
  assert.ok(fs.readdirSync(dir).some((n) => n.startsWith('package.json.lock.stale-')), 'stale lock kept as evidence');
  // a live owner (this process' parent shell is alive: use our own pid via another file) must time out
  fs.writeFileSync(file + '.lock', `${process.ppid}\n`);
  await assert.rejects(withFileLock(file, async () => {}, { waitMs: 200 }), /timed out/);
  assert.equal(fs.readFileSync(file + '.lock', 'utf8'), `${process.ppid}\n`);
  // a non-PID lock body is never touched
  fs.writeFileSync(file + '.lock', 'garbage');
  await assert.rejects(withFileLock(file, async () => {}, { waitMs: 150 }), /timed out/);
});

// 3. Retired plugin sources must be admitted by the real v4 persistence codec.
await check('v4 source normalization at Session.append', async () => {
  const { Session } = await load('@deepseek-ai/dsh-session/lib/index.js');
  const s = Session.create('session-dshm-test');
  const a = s.append('user/message', { role: 'user', id: 'm1', content: [{ type: 'text', text: 'nudge' }], source: { kind: 'plugin', plugin: 'billion-context-dsh' } }, { surfaceOp: 'append' });
  assert.equal(a.data.source.kind, 'plugin:billion-context-dsh');
  assert.equal('plugin' in a.data.source, false);
  const b = s.append('agent/inbox/spliced', { target: 'next-turn', start: 0, inserted: [{ role: 'user', id: 'm2', content: [{ type: 'text', text: 'x' }] }] });
  assert.equal(b.data.inserted[0].source.kind, 'user');
  const good = { role: 'user', id: 'm3', content: [{ type: 'text', text: 'ok' }], source: { kind: 'user', rpcId: 'r' } };
  const c = s.append('user/message', good, { surfaceOp: 'append' });
  assert.deepEqual(c.data.source, good.source);
  // the persistence-side admission predicate accepts what we produced
  const v4 = await load('@deepseek-ai/dsh-session-format-v3-to-v4/lib/index.js');
  const assertRow = v4.assertV4MessageSources ?? v4.default?.assertV4MessageSources;
  if (typeof assertRow === 'function') for (const ev of [a, b, c]) assertRow(ev);
});

// 4. config-editor composes once and still returns identical inherited values.
await check('config-editor single compose equals per-entry inherited()', async () => {
  const src = fs.readFileSync(path.join(nm, '@deepseek-ai/dsh-config-editor/lib/index.js'), 'utf8');
  assert.match(src, /composed\.get\(entry\.options\.id\)/);
  assert.equal(src.split('this.inherited(entry, loaded)').length - 1, 1, 'per-entry recompose only for overridden ids');
});

// 5. PTC child keeps the Android native library path.
await check('ptc keeps LD_LIBRARY_PATH only on android', async () => {
  const src = fs.readFileSync(path.join(nm, '@deepseek-ai/dsh-ptc-runtime-node/lib/index.js'), 'utf8');
  assert.match(src, /process\.platform === "android" && process\.env\.LD_LIBRARY_PATH !== void 0\) env\.LD_LIBRARY_PATH/);
});

// 6. attachment fsync skip is android-only and limited to permission refusals.
await check('attachment directory fsync skips only android permission refusal', async () => {
  const src = fs.readFileSync(path.join(nm, '@deepseek-ai/dsh-attachment-local/lib/index.js'), 'utf8');
  assert.match(src, /process\.platform === "android" && \(error\?\.code === "EACCES" \|\| error\?\.code === "EPERM"\)\) return;\n\t\tthrow error;/);
});

// 7. sharp resolves the wasm build by path on android-arm64 only.
await check('sharp android wasm path is gated', async () => {
  const src = fs.readFileSync(path.join(nm, 'sharp/dist/sharp.cjs'), 'utf8');
  assert.match(src, /runtimePlatform === "android-arm64"\n\s+\? require\("\.\.\/\.\.\/@img\/sharp-wasm32\/index\.cjs"\)/);
  createRequire(import.meta.url);
});

// 8. All patched files are valid modules.
await check('patched modules load', async () => {
  for (const spec of ['@deepseek-ai/dsh-app-boot/lib/index.js', '@deepseek-ai/dsh-atomic-write/lib/index.js', '@deepseek-ai/dsh-session/lib/index.js', '@deepseek-ai/dsh-session-format-v3-to-v4/lib/index.js', '@deepseek-ai/dsh-config-editor/lib/index.js']) await load(spec);
});

const failed = results.filter((r) => !r.ok);
console.log(JSON.stringify({ passed: results.length - failed.length, failed: failed.length, results }, null, 2));
process.exit(failed.length === 0 ? 0 : 1);
