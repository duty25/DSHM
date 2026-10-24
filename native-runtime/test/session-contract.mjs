import * as fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {pathToFileURL, fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import os from 'node:os';

const workspace = path.dirname(fileURLToPath(import.meta.url));
const location = {runtime: process.env.DSHM_NATIVE_CORE_ROOT, stage: process.env.TMPDIR || os.tmpdir(), target: '0.1.7-rc.1'};
assert.ok(location.runtime && path.isAbsolute(location.runtime), 'Explicit candidate core required');
const marker = JSON.parse(await fs.readFile(path.join(location.runtime, '.dshm-native017-candidate.json'), 'utf8'));
assert.equal(marker.production, false);
assert.equal(marker.purpose, 'dshm-native017-candidate');
const require = createRequire(path.join(location.runtime, 'package.json'));
const load = name => import(pathToFileURL(require.resolve(name)).href);
const {Context} = await load('@deepseek-ai/cordis');
const {default: JsonlSessionPersistence} = await load('@deepseek-ai/dsh-session-persistence-jsonl');
const {Session, SessionId, SESSION_FORMAT_VERSION} = await load('@deepseek-ai/dsh-session');
const {SessionAlreadyOwnedError} = await load('@deepseek-ai/dsh-session-persistence');
const {createUserMessage} = await load('@deepseek-ai/dsh-llm');
const [mode = 'suite', root, id, compression] = process.argv.slice(2);

async function backend(directory, encoding) {
  const ctx = new Context();
  const fiber = ctx.plugin(JsonlSessionPersistence, {root: directory, compression: encoding});
  await fiber.inertia;
  assert.equal(fiber.state, 2, 'Persistence plugin must really be active');
  return {storage: ctx.get('sessionPersistence'), close: () => fiber.dispose()};
}
function addTurn(session, turn, text) {
  session.append('turn/start', {turn});
  session.append('step/start', {turn, step: 1});
  session.append('user/message', createUserMessage({content: [{type: 'text', text}], source: {kind: 'user'}}), {surfaceOp: 'append'});
  session.append('step/end', {turn, step: 1});
  session.append('turn/end', {turn, reason: {kind: 'completed'}});
}
function texts(events) {
  return events.filter(event => event.type === 'user/message').flatMap(event => event.data.content.map(block => block.text));
}

if (mode !== 'suite') {
  const service = await backend(root, compression);
  try {
    if (mode === 'expect-owned') {
      await assert.rejects(service.storage.open(SessionId(id), 'write'), SessionAlreadyOwnedError);
      console.log(JSON.stringify({otherWriterRejected: true}));
    } else {
      const access = mode === 'append' ? 'write' : 'read';
      const handle = await service.storage.open(SessionId(id), access);
      try {
        const loaded = await handle.read();
        if (mode === 'append') {
          const session = Session.fromRestore(SessionId(id), loaded.events, handle.header, handle.inheritedEventCount, loaded.eventState);
          addTurn(session, 2, 'native017-second-process-write');
          await handle.append(session.snapshotEvents(loaded.events.length));
          await handle.flush();
        }
        const after = await handle.read();
        console.log(JSON.stringify({header: handle.header, texts: texts(after.events), eventCount: after.events.length}));
      } finally {
        await handle.close();
      }
    }
  } finally {
    await service.close();
  }
} else {
  assert.equal(process.platform, process.env.DSHM_EXPECT_PLATFORM || 'android');
  const cases = [];
  const report = {at: new Date().toISOString(), node: process.version, platform: process.platform, target: location.target, realSessionsUsed: false, realModelCalls: 0, backend: '@deepseek-ai/dsh-session-persistence-jsonl', cases, passed: false};
  const worker = (phase, directory, sessionId, encoding) => {
    const child = spawnSync(process.execPath, [fileURLToPath(import.meta.url), phase, directory, sessionId, encoding], {env: process.env, encoding: 'utf8', timeout: 20000});
    assert.equal(child.status, 0, child.stderr || child.error?.message || child.stdout);
    return JSON.parse(child.stdout.trim().split('\n').at(-1));
  };
  try {
    for (const encoding of ['none', 'zstd']) {
      const directory = await fs.mkdtemp(path.join(location.stage, 'session-check-' + encoding + '-'));
      const sessionId = SessionId('native017-' + randomUUID());
      const header = {version: SESSION_FORMAT_VERSION, id: sessionId, createdAt: Date.now(), cwd: directory, isSeeded: false, delegationDepth: 0};
      const session = Session.create(sessionId, [], header);
      addTurn(session, 1, 'native017-initial-durable-write');
      const service = await backend(directory, encoding);
      let handle;
      try {
        handle = await service.storage.create(header);
        await handle.append(session.snapshotEvents());
        await handle.flush();
        assert.deepEqual(texts((await handle.read()).events), ['native017-initial-durable-write']);
        cases.push({encoding, name: 'create-append-flush-read', passed: true});
        assert.equal(worker('expect-owned', directory, sessionId, encoding).otherWriterRejected, true);
        cases.push({encoding, name: 'session-writer-excluded-cross-process', passed: true});
      } finally {
        await handle?.close();
        await service.close();
      }
      const cold = worker('read', directory, sessionId, encoding);
      assert.equal(cold.header.id, sessionId);
      assert.deepEqual(cold.texts, ['native017-initial-durable-write']);
      cases.push({encoding, name: 'fresh-process-reopen-read', passed: true});
      const appended = worker('append', directory, sessionId, encoding);
      assert.deepEqual(appended.texts, ['native017-initial-durable-write', 'native017-second-process-write']);
      cases.push({encoding, name: 'fresh-process-resume-append', passed: true});
      const reread = worker('read', directory, sessionId, encoding);
      assert.deepEqual(reread.texts, appended.texts);
      cases.push({encoding, name: 'second-reopen-preserves-new-write', passed: true, directory});
    }
    report.passed = true;
  } catch (error) {
    report.error = {message: error.message, code: error.code, stack: error.stack};
    process.exitCode = 1;
  } finally {
    await fs.writeFile(path.join(process.env.DSHM_NATIVE_REPORT_DIR || location.stage, 'native-session-contract.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
  }
}
