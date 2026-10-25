'use strict';
/*
 * DSHM v2.1.46 stability patch set for the pinned DSH 0.1.7-rc.1 runtime.
 *
 * Every fix that previous maintenance applied by hand on the phone (and that a
 * runtime reinstall silently erased) is reproduced here at build time, plus the
 * root-cause fixes for the recurring field failures:
 *
 *   bundle-retention   app-boot / plugin-manager: a pnpm operation must never drop
 *                      an existing, still-installed bundle just because its
 *                      dsh.bundle metadata was unreadable at that instant
 *                      (Codex / WorkBuddy / bridge "plugin vanished" after
 *                      installing an unrelated plugin).
 *   stale-writer-lock  atomic-write: a `<file>.lock` whose recorded PID is gone
 *                      (Android force-stop) is reclaimed instead of blocking
 *                      every profile write forever.
 *   v4-source          dsh-session: retired `{kind:'plugin'}` and missing message
 *                      sources are normalized to the v4 producer-owned shape at
 *                      the single append choke point ("format v4 message
 *                      requires a producer-owned source kind").
 *   v3-missing-source  v3->v4 migration: early v3 rows without a source get the
 *                      role-implied source instead of failing the session open.
 *   config-compose     config-editor: compose inherited config once instead of
 *                      once per entry (settings save took 5-10 s).
 *   ptc-ld-path        PTC runtime: keep LD_LIBRARY_PATH so the Android libnode
 *                      child can load libz.so.1.
 *   attachment-fsync   attachment-local: an ancestor directory outside the app
 *                      sandbox that cannot be opened (EACCES on /data/user/0)
 *                      ends the durability walk instead of rejecting the image.
 *   sharp-wasm         sharp: android-arm64 loads the bundled wasm32 build.
 *   prompt-reason      session-controller: a rejected prompt keeps its real
 *                      cause in the error instead of a bare "agent-busy".
 *   todo-panel         conversation UI: the todo list collapses with a
 *                      compositor-friendly transition instead of unmounting.
 *
 * Contract (same as the other native-runtime patches): the target directory
 * must carry the candidate marker, every file must match its recorded
 * upstream sha256 (or the recorded patched sha256 for an idempotent replay),
 * and every anchor must match exactly once. Anything else throws before a
 * single byte is written.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const own = path.resolve(__dirname, '..');
const hash = (b) => crypto.createHash('sha256').update(b).digest('hex');
const DSH_VERSION = '0.1.7-rc.1';

function once(source, anchor, replacement, id) {
  const parts = source.split(anchor);
  if (parts.length !== 2) throw new Error(`Stability anchor mismatch (${id}): found ${parts.length - 1}`);
  return parts[0] + replacement + parts[1];
}

/* ---------------------------------------------------------------- patches */

const BUNDLE_RETENTION_APP_BOOT = {
  id: 'bundle-retention',
  file: '@deepseek-ai/dsh-app-boot/lib/index.js',
  apply(s) {
    return once(s,
      'const bundles = previous.filter((name) => !(beforeNames.has(name) || afterNames.has(name)) || bundleNames.has(name));',
      '// DSHM: a dependency that is still declared keeps its bundle slot; only a real removal (absent from\n' +
      '\t// dependencies after the operation) drops it. Transiently unreadable metadata must not disable a plugin.\n' +
      '\tconst bundles = previous.filter((name) => !(beforeNames.has(name) || afterNames.has(name)) || bundleNames.has(name) || afterNames.has(name) && beforeNames.has(name));',
      this.id);
  }
};

const BUNDLE_RETENTION_PLUGIN_MANAGER = {
  id: 'bundle-retention',
  file: '@deepseek-ai/dsh-plugin-manager/lib/index.js',
  apply(s) {
    return once(s,
      'return dependencies.includes(name) && bundleManifest(name, dir, anchor) !== void 0;',
      '// DSHM: a retained dependency keeps its slot even if its manifest is unreadable right now.\n' +
      '\t\tif (dependencies.includes(name) && beforeDeps.has(name)) return true;\n' +
      '\t\ttry {\n' +
      '\t\t\treturn dependencies.includes(name) && bundleManifest(name, dir, anchor) !== void 0;\n' +
      '\t\t} catch {\n' +
      '\t\t\treturn false;\n' +
      '\t\t}',
      this.id);
  }
};

const STALE_WRITER_LOCK = {
  id: 'stale-writer-lock',
  file: '@deepseek-ai/dsh-atomic-write/lib/index.js',
  apply(s) {
    s = once(s,
      'import { lstat, mkdir, rename, rm, writeFile } from "node:fs/promises";',
      'import { lstat, mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";',
      this.id);
    s = once(s,
      'async function withFileLock(filename, operation, options) {',
      '/**\n' +
      '* DSHM: reclaim a writer lock only when it provably has no owner: it records a numeric PID that is\n' +
      '* not this process and that the kernel reports as nonexistent (ESRCH). An unreadable lock, a PID we\n' +
      '* are not allowed to signal (EPERM) or a live PID is left untouched, exactly like upstream. The\n' +
      '* stale file is renamed aside (never deleted) so the recovery leaves evidence.\n' +
      '* @param lockPath - the `<file>.lock` sibling.\n' +
      '* @returns whether the stale lock was moved aside.\n' +
      '*/\n' +
      'async function reclaimOrphanLock(lockPath) {\n' +
      '\tif (process.platform === "win32") return false;\n' +
      '\tlet text;\n' +
      '\ttry {\n' +
      '\t\ttext = await readFile(lockPath, "utf8");\n' +
      '\t} catch {\n' +
      '\t\treturn false;\n' +
      '\t}\n' +
      '\tif (!/^\\d+\\n?$/.test(text)) return false;\n' +
      '\tconst pid = Number.parseInt(text, 10);\n' +
      '\tif (!Number.isSafeInteger(pid) || pid <= 1 || pid === process.pid) return false;\n' +
      '\ttry {\n' +
      '\t\tprocess.kill(pid, 0);\n' +
      '\t\treturn false;\n' +
      '\t} catch (error) {\n' +
      '\t\tif (error?.code !== "ESRCH") return false;\n' +
      '\t}\n' +
      '\ttry {\n' +
      '\t\tconst again = await readFile(lockPath, "utf8");\n' +
      '\t\tif (again !== text) return false;\n' +
      '\t\tawait rename(lockPath, `${lockPath}.stale-${pid}-${Date.now()}`);\n' +
      '\t\treturn true;\n' +
      '\t} catch {\n' +
      '\t\treturn false;\n' +
      '\t}\n' +
      '}\n' +
      'async function withFileLock(filename, operation, options) {\n' +
      '\tlet reclaimedOrphan = false;',
      this.id);
    s = once(s,
      'if (Date.now() >= deadline) throw new Error(`atomic-write: timed out waiting for the writer lock at ${lockPath}`);',
      'if (!reclaimedOrphan && await reclaimOrphanLock(lockPath)) {\n' +
      '\t\t\treclaimedOrphan = true;\n' +
      '\t\t\tcontinue;\n' +
      '\t\t}\n' +
      '\t\tif (Date.now() >= deadline) throw new Error(`atomic-write: timed out waiting for the writer lock at ${lockPath}`);',
      this.id);
    return s;
  }
};

const V4_SOURCE = {
  id: 'v4-source',
  file: '@deepseek-ai/dsh-session/lib/index.js',
  apply(s) {
    s = once(s,
      'import { callConfigEquals } from "@deepseek-ai/dsh-llm";',
      'import { callConfigEquals } from "@deepseek-ai/dsh-llm";\n' +
      '//#region DSHM v4 source normalization\n' +
      '/**\n' +
      '* Session format v4 refuses a durable message whose source kind is missing, empty, or the retired\n' +
      '* literal "plugin". Plugins written for v3 still emit both shapes; one such row poisons the whole\n' +
      '* append batch and fails the next turn. Normalize at the single append choke point, using exactly the\n' +
      '* mapping the official v3->v4 migration emits ("plugin:<name>"), and leave valid sources untouched.\n' +
      '*/\n' +
      'const DSHM_MESSAGE_SLOTS = new Set(["developer/message", "system/message", "assistant/message", "tool/result"]);\n' +
      'function dshmRoleKind(role) {\n' +
      '\treturn role === "assistant" ? "model" : role === "tool" ? "tool" : role === "system" ? "system-prompt" : "user";\n' +
      '}\n' +
      'function dshmMessage(message) {\n' +
      '\tif (message === null || typeof message !== "object" || Array.isArray(message)) return message;\n' +
      '\tconst source = message.source;\n' +
      '\tif (source !== null && typeof source === "object" && !Array.isArray(source)) {\n' +
      '\t\tconst kind = source.kind;\n' +
      '\t\tif (typeof kind === "string" && kind.length > 0 && kind !== "plugin") return message;\n' +
      '\t\tif (kind === "plugin") {\n' +
      '\t\t\tconst label = typeof source.plugin === "string" && source.plugin.length > 0 ? source.plugin : "unattributed";\n' +
      '\t\t\tconst { plugin: _retired, ...rest } = source;\n' +
      '\t\t\treturn { ...message, source: { ...rest, kind: `plugin:${label}` } };\n' +
      '\t\t}\n' +
      '\t}\n' +
      '\tif (source === void 0) {\n' +
      '\t\tconst kind = dshmRoleKind(message.role);\n' +
      '\t\tif (kind === "tool" && typeof message.toolCallId === "string") return { ...message, source: { kind, callId: message.toolCallId } };\n' +
      '\t\treturn kind === "tool" ? { ...message, source: { kind: "plugin:unattributed" } } : { ...message, source: { kind } };\n' +
      '\t}\n' +
      '\treturn { ...message, source: { kind: "plugin:unattributed" } };\n' +
      '}\n' +
      'function dshmMessages(list) {\n' +
      '\tif (!Array.isArray(list)) return list;\n' +
      '\tlet changed = false;\n' +
      '\tconst next = list.map((item) => {\n' +
      '\t\tconst fixed = dshmMessage(item);\n' +
      '\t\tif (fixed !== item) changed = true;\n' +
      '\t\treturn fixed;\n' +
      '\t});\n' +
      '\treturn changed ? next : list;\n' +
      '}\n' +
      'function dshmNormalizeSources(type, data) {\n' +
      '\tif (data === null || typeof data !== "object" || Array.isArray(data)) return data;\n' +
      '\tif (type === "user/message") return dshmMessage(data);\n' +
      '\tif (DSHM_MESSAGE_SLOTS.has(type) && data.message !== null && typeof data.message === "object") {\n' +
      '\t\tconst message = dshmMessage(data.message);\n' +
      '\t\treturn message === data.message ? data : { ...data, message };\n' +
      '\t}\n' +
      '\tif (type === "agent/inbox/spliced") {\n' +
      '\t\tconst inserted = dshmMessages(data.inserted);\n' +
      '\t\treturn inserted === data.inserted ? data : { ...data, inserted };\n' +
      '\t}\n' +
      '\tif (type === "session/title-llm-request") {\n' +
      '\t\tconst messages = dshmMessages(data.messages);\n' +
      '\t\treturn messages === data.messages ? data : { ...data, messages };\n' +
      '\t}\n' +
      '\treturn data;\n' +
      '}\n' +
      '//#endregion',
      this.id);
    s = once(s,
      'const dataSnapshot = snapshotJsonValue(data);',
      'const dataSnapshot = snapshotJsonValue(dshmNormalizeSources(type, data));',
      this.id);
    return s;
  }
};

const V3_MISSING_SOURCE = {
  id: 'v3-missing-source',
  file: '@deepseek-ai/dsh-session-format-v3-to-v4/lib/index.js',
  apply(s) {
    return once(s,
      'if (!isSessionFormatJsonObject(source)) return message;',
      'if (!isSessionFormatJsonObject(source)) {\n' +
      '\t\t\t\t// DSHM: early v3 releases wrote no source at all; v4 admission requires one.\n' +
      '\t\t\t\t// Restore the producer a v3 write implied for this role.\n' +
      '\t\t\t\tif (source !== void 0) return message;\n' +
      '\t\t\t\tconst role = message["role"];\n' +
      '\t\t\t\tconst kind = role === "assistant" ? "model" : role === "tool" ? "tool" : role === "system" ? "system-prompt" : "user";\n' +
      '\t\t\t\tconst callId = message["toolCallId"];\n' +
      '\t\t\t\treturn {\n' +
      '\t\t\t\t\t...message,\n' +
      '\t\t\t\t\tsource: kind === "tool" && typeof callId === "string" ? { kind, callId } : kind === "tool" ? { kind: "plugin:unattributed" } : { kind }\n' +
      '\t\t\t\t};\n' +
      '\t\t\t}',
      this.id);
  }
};

const CONFIG_COMPOSE = {
  id: 'config-compose',
  file: '@deepseek-ai/dsh-config-editor/lib/index.js',
  apply(s) {
    return once(s,
      '\t\treturn this.entries().map((entry) => ({\n\t\t\tentry,\n\t\t\tinherited: this.inherited(entry, loaded),',
      '\t\t// DSHM (matches upstream 0.2.1): compose once for every entry without its own profile config;\n' +
      '\t\t// only entries that the profile overrides need the per-entry recomposition.\n' +
      '\t\tconst entries = this.entries();\n' +
      '\t\tconst overridden = new Set(loaded.patches.filter((patch) => patch.insert === void 0 && Object.hasOwn(patch, "config")).map((patch) => patch.id));\n' +
      '\t\tconst composed = /* @__PURE__ */ new Map();\n' +
      '\t\tif (entries.some((entry) => !overridden.has(entry.options.id))) {\n' +
      '\t\t\tfor (const row of flatten(composeEntries([...loaded.layers.map((layer) => layer.patches), loaded.patches]))) if (!composed.has(row.id)) composed.set(row.id, row);\n' +
      '\t\t}\n' +
      '\t\treturn entries.map((entry) => ({\n\t\t\tentry,\n' +
      '\t\t\tinherited: overridden.has(entry.options.id) ? this.inherited(entry, loaded) : structuredClone(composed.get(entry.options.id)?.config ?? {}),',
      this.id);
  }
};

const PTC_LD_PATH = {
  id: 'ptc-ld-path',
  file: '@deepseek-ai/dsh-ptc-runtime-node/lib/index.js',
  apply(s) {
    const anchor = 'const env = Object.fromEntries(Object.keys(process.env).filter((key) => !STARTUP_ENVIRONMENT_NAMES.has(key.toUpperCase()) && key.toUpperCase() !== "ELECTRON_RUN_AS_NODE").map((key) => [key, void 0]));';
    return once(s, anchor,
      anchor + '\n\t\t\t// DSHM: the Android libnode child needs the app native library path (libz.so.1, libc++_shared.so).\n' +
      '\t\t\tif (process.platform === "android" && process.env.LD_LIBRARY_PATH !== void 0) env.LD_LIBRARY_PATH = process.env.LD_LIBRARY_PATH;',
      this.id);
  }
};

const ATTACHMENT_FSYNC = {
  id: 'attachment-fsync',
  file: '@deepseek-ai/dsh-attachment-local/lib/index.js',
  apply(s) {
    return once(s,
      'const handle = await open(path, constants.O_RDONLY);',
      'let handle;\n' +
      '\ttry {\n' +
      '\t\thandle = await open(path, constants.O_RDONLY);\n' +
      '\t} catch (error) {\n' +
      '\t\t// DSHM: Android denies opening ancestors of the app sandbox (/data/user/0). Those entries are\n' +
      '\t\t// owned by the system and already durable; only skip that case, never a real I/O failure.\n' +
      '\t\tif (process.platform === "android" && (error?.code === "EACCES" || error?.code === "EPERM")) return;\n' +
      '\t\tthrow error;\n' +
      '\t}',
      this.id);
  }
};

const SHARP_WASM = {
  id: 'sharp-wasm',
  file: 'sharp/dist/sharp.cjs',
  apply(s) {
    return once(s,
      'sharp = require("@img/sharp-wasm32/sharp.node");',
      '// DSHM: android-arm64 has no native prebuild; load the bundled, integrity-pinned wasm32 build by path.\n' +
      '    sharp = runtimePlatform === "android-arm64"\n' +
      '      ? require("../../@img/sharp-wasm32/index.cjs")\n' +
      '      : require("@img/sharp-wasm32/sharp.node");',
      this.id);
  }
};

const PROMPT_REASON = {
  id: 'prompt-reason',
  file: '@deepseek-ai/dsh-api-session-controller/lib/index.js',
  apply(s) {
    return once(s,
      'throw new RemoteError("session/agent-busy", "prompt rejected", { reason: String(error) });',
      '// DSHM: keep the real cause visible instead of a bare "prompt rejected".\n' +
      '\t\t\t\tthrow new RemoteError("session/agent-busy", `prompt rejected: ${String(error?.message ?? error).slice(0, 300)}`, { reason: String(error) });',
      this.id);
  }
};

const TODO_PANEL = {
  id: 'todo-panel',
  file: '@deepseek-ai/dsh-client-ui-conversation/lib/client.js',
  apply(s) {
    s = once(s,
      'background:var(--dsw-specific-menu);backdrop-filter:var(--dsw-menu-backdrop-filter);box-shadow:var(--dsw-elevation-panel);--dsh-scrollbar-thumb:var(--dsw-alias-scrollbar-bg-l2)',
      'background:var(--dsw-specific-menu);box-shadow:var(--dsw-elevation-panel);--dsh-scrollbar-thumb:var(--dsw-alias-scrollbar-bg-l2)',
      this.id);
    s = once(s,
      '.lXshSW_list{flex-direction:column;gap:8px;max-height:180px;margin:0;padding:0;list-style:none;display:flex;overflow-y:auto}',
      '.lXshSW_list{flex-direction:column;gap:8px;max-height:180px;margin:0;padding:0;list-style:none;display:flex;overflow-y:auto;contain:layout paint style;overscroll-behavior-y:contain;transition:max-height 160ms cubic-bezier(.22,1,.36,1),opacity 120ms ease,margin-top 160ms cubic-bezier(.22,1,.36,1)}.lXshSW_list[data-collapsed]{max-height:0;opacity:0;overflow:hidden;margin-top:-8px;pointer-events:none}@media (prefers-reduced-motion:reduce){.lXshSW_list{transition:none}}',
      this.id);
    s = once(s,
      '}), !collapsed && (0, react_jsx_runtime.jsx)("ul", {\n\t\t\t\t\t\tclassName: TodoPanel_module_css_default.list,',
      '}), (0, react_jsx_runtime.jsx)("ul", {\n\t\t\t\t\t\tclassName: TodoPanel_module_css_default.list,\n' +
      '\t\t\t\t\t\t"data-collapsed": collapsed ? "" : void 0,\n' +
      '\t\t\t\t\t\t"aria-hidden": collapsed,',
      this.id);
    return s;
  }
};

const PATCHES = [
  BUNDLE_RETENTION_APP_BOOT,
  BUNDLE_RETENTION_PLUGIN_MANAGER,
  STALE_WRITER_LOCK,
  V4_SOURCE,
  V3_MISSING_SOURCE,
  CONFIG_COMPOSE,
  PTC_LD_PATH,
  ATTACHMENT_FSYNC,
  SHARP_WASM,
  PROMPT_REASON,
  TODO_PANEL
];

/* ---------------------------------------------------------------- driver */

function manifest() {
  return JSON.parse(fs.readFileSync(path.join(own, 'stability-source.json'), 'utf8'));
}

function plan(root) {
  const meta = manifest();
  const byFile = new Map();
  for (const patch of PATCHES) {
    if (!byFile.has(patch.file)) byFile.set(patch.file, []);
    byFile.get(patch.file).push(patch);
  }
  const out = [];
  for (const [file, patches] of byFile) {
    const record = meta.files[file];
    if (!record) throw new Error(`Stability manifest has no record for ${file}`);
    const full = path.join(root, 'node_modules', file);
    const before = fs.readFileSync(full);
    const digest = hash(before);
    if (record.patched !== undefined && digest === record.patched) {
      out.push({ file, full, alreadyApplied: true, ids: patches.map((p) => p.id) });
      continue;
    }
    if (digest !== record.upstream) throw new Error(`Unreviewed stability source ${file}: ${digest}`);
    let text = before.toString('utf8');
    for (const patch of patches) text = patch.apply(text);
    const after = Buffer.from(text, 'utf8');
    if (record.patched !== undefined && hash(after) !== record.patched) throw new Error(`Patched output drifted for ${file}: ${hash(after)}`);
    out.push({ file, full, before: digest, after: hash(after), bytes: after, ids: patches.map((p) => p.id) });
  }
  return out;
}

function apply(root, options = {}) {
  root = fs.realpathSync(root);
  const marker = JSON.parse(fs.readFileSync(path.join(root, '.dshm-native017-candidate.json'), 'utf8'));
  if (marker.production !== false || marker.purpose !== 'dshm-native017-candidate') throw new Error('Candidate-only marker required');
  const dsh = JSON.parse(fs.readFileSync(path.join(root, 'node_modules/@deepseek-ai/dsh/package.json'), 'utf8'));
  if (dsh.version !== DSH_VERSION) throw new Error(`Stability patches are pinned DSH ${DSH_VERSION}, found ${dsh.version}`);
  // Plan everything first: no file is written unless every file and every anchor verified.
  const steps = plan(root);
  const results = [];
  for (const step of steps) {
    if (step.alreadyApplied) {
      results.push({ file: step.file, ids: step.ids, alreadyApplied: true });
      continue;
    }
    if (!options.dryRun) fs.writeFileSync(step.full, step.bytes);
    results.push({ file: step.file, ids: step.ids, before: step.before, after: step.after });
  }
  const report = { dsh: DSH_VERSION, dryRun: options.dryRun === true, patches: PATCHES.length, files: results };
  if (!options.dryRun) fs.writeFileSync(path.join(root, 'dshm-stability-patch.json'), JSON.stringify(report, null, 2) + '\n');
  return report;
}

module.exports = { apply, plan, PATCHES, once };

if (require.main === module) {
  const target = process.argv[2] || path.join(own, 'sdk');
  console.log(JSON.stringify(apply(target, { dryRun: process.argv.includes('--dry-run') })));
}
