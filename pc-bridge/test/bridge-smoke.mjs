import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, rm, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { listSessions, readSessionSafety, scanSessionSafety } from '../lib/codex-sessions.mjs';
import { buildFollowerStartTurnRequest, isDesktopSyncUnavailableError } from '../lib/codex-ipc.mjs';
import { clawAgents, clawHealth, clawSessions, clawTurn } from '../lib/claw-adapter.mjs';
import { startBridge } from '../server.mjs';

let failures = 0;
async function check(name, fn) {
  try {
    await fn();
    console.log(`ok - ${name}`);
  } catch (error) {
    failures += 1;
    console.error(`FAIL - ${name}: ${error.message}`);
  }
}

const bridge = await startBridge({ port: 0, token: '', host: '127.0.0.1' });
const base = bridge.url;
const tempDir = await mkdtemp(join(tmpdir(), 'pc-bridge-smoke-'));

const stub = createServer(async (req, res) => {
  const url = new URL(req.url || '/', 'http://stub.local');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  const json = (status, body) => {
    res.writeHead(status);
    res.end(JSON.stringify(body));
  };
  if (url.pathname === '/api/health') {
    json(200, { ok: true, profile: 'autoclaw', gateway: { running: true, health: 'ok' }, time: Date.now() });
    return;
  }
  if (url.pathname === '/api/turn') {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
    if (String(body.message).includes('SLOW')) {
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 5000));
    }
    json(200, {
      ok: true,
      runId: 'stub-run',
      status: 'ok',
      text: `reply: ${body.message}`,
      sessionId: 'stub-session',
      provider: 'stub',
      model: 'stub-model',
    });
    return;
  }
  if (url.pathname === '/api/sessions') {
    json(200, { ok: true, count: 1, sessions: [{ key: 'stub', title: 'stub session' }] });
    return;
  }
  if (url.pathname === '/api/agents') {
    json(200, { ok: true, agents: [{ id: 'main', name: 'main agent' }] });
    return;
  }
  json(404, { ok: false, error: 'not found' });
});
await new Promise((resolvePromise) => stub.listen(0, '127.0.0.1', resolvePromise));
const stubUrl = `http://127.0.0.1:${stub.address().port}`;

async function post(path, body) {
  const res = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, body: await res.json() };
}

async function get(path) {
  const res = await fetch(`${base}${path}`);
  return { status: res.status, body: await res.json() };
}

await check('health', async () => {
  const result = await get('/api/health');
  assert.equal(result.status, 200);
  assert.equal(result.body.ok, true);
  assert.equal(result.body.name, 'aether-pc-bridge');
  assert.equal(result.body.desktopSync, false);
  assert.equal(result.body.claw, true);
});

await check('health reports desktop sync flag', async () => {
  const syncBridge = await startBridge({ port: 0, token: '', host: '127.0.0.1', desktopSync: true });
  try {
    const res = await fetch(`${syncBridge.url}/api/health`);
    const body = await res.json();
    assert.equal(body.desktopSync, true);
  } finally {
    await syncBridge.close();
  }
});

await check('health reports claw flag', async () => {
  const noClawBridge = await startBridge({ port: 0, token: '', host: '127.0.0.1', claw: false });
  try {
    const res = await fetch(`${noClawBridge.url}/api/health`);
    const body = await res.json();
    assert.equal(body.claw, false);
    const clawRes = await fetch(`${noClawBridge.url}/api/claw/health`);
    assert.equal(clawRes.status, 503);
  } finally {
    await noClawBridge.close();
  }
});

await check('desktop sync request payload', async () => {
  const id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const req = buildFollowerStartTurnRequest(id, 'hello desktop');
  assert.equal(req.type, 'request');
  assert.equal(req.method, 'thread-follower-start-turn');
  assert.equal(req.version, 1);
  assert.equal(req.params.conversationId, id);
  assert.equal(req.params.turnStartParams.input[0].type, 'text');
  assert.equal(req.params.turnStartParams.input[0].text, 'hello desktop');
  assert.ok(/^[0-9a-f-]{36}$/i.test(req.params.turnStartParams.clientUserMessageId));
});

await check('desktop sync unavailable error classifier', async () => {
  assert.equal(isDesktopSyncUnavailableError(new Error('no-client-found')), true);
  assert.equal(isDesktopSyncUnavailableError(new Error('ECONNREFUSED')), true);
  assert.equal(isDesktopSyncUnavailableError(new Error('connect timed out')), true);
  assert.equal(isDesktopSyncUnavailableError(new Error('invalid params')), false);
});

let sessionList = [];
await check('list sessions newest first', async () => {
  const result = await get('/api/sessions?limit=20');
  assert.equal(result.status, 200);
  assert.ok(Array.isArray(result.body.sessions));
  assert.ok(result.body.sessions.length > 0);
  sessionList = result.body.sessions;
  const times = sessionList.map((item) => Date.parse(item.updatedAt));
  for (let i = 1; i < times.length; i++) {
    assert.ok(times[i - 1] >= times[i], `session ${i} is older than ${i - 1}`);
  }
});

await check('resume guard detects read-only sessions', async () => {
  const restricted = JSON.stringify({
    timestamp: '2026-08-02T15:43:32.000Z',
    type: 'turn_context',
    payload: {
      cwd: 'D:\\document\\work\\Aether\\pc-bridge',
      sandbox_policy: { type: 'read-only' },
      permission_profile: { type: 'managed', file_system: { type: 'restricted' } },
    },
  });
  const safety = scanSessionSafety(restricted + '\n');
  assert.equal(safety.blocked, true);
  assert.equal(safety.cwd, 'D:\\document\\work\\Aether\\pc-bridge');
});

await check('resume guard allows danger-full-access sessions', async () => {
  const safe = JSON.stringify({
    timestamp: '2026-08-02T15:43:32.000Z',
    type: 'turn_context',
    payload: { cwd: 'D:\\document\\project', sandbox_policy: { type: 'danger-full-access' } },
  });
  const safety = scanSessionSafety(safe + '\n');
  assert.equal(safety.blocked, false);
});

await check('resume guard uses latest sandbox after an earlier restricted context', async () => {
  const restricted = JSON.stringify({
    timestamp: '2026-08-03T01:21:46.000Z',
    type: 'turn_context',
    payload: {
      cwd: 'D:\\document\\project',
      sandbox_policy: { type: 'workspace-write', network_access: false },
      permission_profile: {
        type: 'managed',
        file_system: { type: 'restricted' },
      },
    },
  });
  const safe = JSON.stringify({
    timestamp: '2026-08-03T01:23:13.000Z',
    type: 'turn_context',
    payload: {
      cwd: 'D:\\document\\project',
      sandbox_policy: { type: 'danger-full-access' },
      permission_profile: { type: 'disabled' },
    },
  });
  const safety = scanSessionSafety(restricted + '\n' + safe + '\n');
  assert.equal(safety.blocked, false);
  assert.equal(safety.sandboxPolicy, 'danger-full-access');
  assert.equal(safety.fileSystemAccess, '');
});

await check('readSessionSafety resolves against CODEX_HOME', async () => {
  const fakeHome = await mkdtemp(join(tmpdir(), 'pc-bridge-home-'));
  const sessionsDir = join(fakeHome, 'sessions', '2026', '08');
  await mkdir(sessionsDir, { recursive: true });
  const id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const file = join(sessionsDir, `rollout-2026-08-02T00-00-00-${id}.jsonl`);
  const line = JSON.stringify({
    timestamp: '2026-08-02T00:00:00.000Z',
    type: 'turn_context',
    payload: { cwd: 'C:\\work', sandbox_policy: { type: 'read-only' } },
  });
  await writeFile(file, line + '\n', 'utf8');
  const oldHome = process.env.CODEX_HOME;
  process.env.CODEX_HOME = fakeHome;
  try {
    const safety = await readSessionSafety(id);
    assert.equal(safety.blocked, true);
    assert.equal(safety.cwd, 'C:\\work');
  } finally {
    if (oldHome === undefined) {
      delete process.env.CODEX_HOME;
    } else {
      process.env.CODEX_HOME = oldHome;
    }
  }
  await rm(fakeHome, { recursive: true, force: true });
});

await check('list sessions prefers real file mtime over stale index', async () => {
  const fakeHome = await mkdtemp(join(tmpdir(), 'pc-bridge-home-'));
  const sessionsDir = join(fakeHome, 'sessions', '2026', '08');
  await mkdir(sessionsDir, { recursive: true });
  const staleId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  const freshId = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
  const staleFile = join(sessionsDir, `rollout-2026-08-02T00-00-00-${staleId}.jsonl`);
  const freshFile = join(sessionsDir, `rollout-2026-08-02T00-00-01-${freshId}.jsonl`);
  const sessionLine = (ts, text) => JSON.stringify({
    timestamp: ts,
    type: 'event_msg',
    payload: { type: 'user_message', message: text },
  }) + '\n';
  await writeFile(staleFile, sessionLine('2026-08-02T00:00:00.000Z', 'stale file'), 'utf8');
  await writeFile(freshFile, sessionLine('2026-08-02T00:00:01.000Z', 'fresh file'), 'utf8');
  await utimes(staleFile, new Date('2026-08-02T00:00:00Z'), new Date('2026-08-02T00:00:00Z'));
  await utimes(freshFile, new Date('2026-08-03T02:00:00Z'), new Date('2026-08-03T02:00:00Z'));
  const indexFile = join(fakeHome, 'session_index.jsonl');
  await writeFile(indexFile, [
    JSON.stringify({ id: staleId, thread_name: 'stale index', updated_at: '2026-08-03T01:00:00.000Z' }),
    JSON.stringify({ id: freshId, thread_name: 'fresh index', updated_at: '2026-08-02T01:00:00.000Z' }),
    '',
  ].join('\n'), 'utf8');
  const oldHome = process.env.CODEX_HOME;
  process.env.CODEX_HOME = fakeHome;
  try {
    const result = await listSessions({ limit: 10 });
    const byId = new Map(result.sessions.map((item) => [item.id, item]));
    const fresh = byId.get(freshId);
    const stale = byId.get(staleId);
    assert.ok(fresh, 'fresh file session should be listed');
    assert.ok(stale, 'stale file session should be listed');
    const freshAt = Date.parse(fresh.updatedAt);
    const staleAt = Date.parse(stale.updatedAt);
    assert.ok(freshAt > staleAt, `file mtime should win over index (fresh ${freshAt}, stale ${staleAt})`);
    const indexOf = result.sessions.map((item) => item.id);
    assert.ok(indexOf.indexOf(freshId) < indexOf.indexOf(staleId), 'fresh file session should sort first');
  } finally {
    if (oldHome === undefined) {
      delete process.env.CODEX_HOME;
    } else {
      process.env.CODEX_HOME = oldHome;
    }
  }
  await rm(fakeHome, { recursive: true, force: true });
});

await check('read session history', async () => {
  const sessionId = sessionList[0].id;
  const result = await get(`/api/sessions/${sessionId}`);
  assert.equal(result.status, 200);
  assert.ok(Array.isArray(result.body.messages));
  assert.ok(typeof result.body.markdown === 'string' && result.body.markdown.length > 0);
});

await check('mcp tools/list', async () => {
  const result = await post('/mcp', { jsonrpc: '2.0', id: 't1', method: 'tools/list' });
  assert.equal(result.status, 200);
  assert.ok(Array.isArray(result.body.result.tools));
  const names = result.body.result.tools.map((tool) => tool.name);
  for (const expected of ['codex_list_sessions', 'codex_read_session', 'codex_exec', 'codex_resume', 'codex_poll', 'codex_stop', 'claw_turn', 'claw_sessions', 'claw_agents', 'claw_health', 'pc_shell', 'pc_file_read', 'pc_git_status']) {
    assert.ok(names.includes(expected), `missing tool ${expected}`);
  }
});

await check('mcp codex_list_sessions call', async () => {
  const result = await post('/mcp', {
    jsonrpc: '2.0',
    id: 't2',
    method: 'tools/call',
    params: { name: 'codex_list_sessions', arguments: { limit: 3 } },
  });
  assert.equal(result.status, 200);
  const text = result.body.result.content[0].text;
  assert.ok(text.includes('"ok": true'));
});

await check('claw adapter health', async () => {
  const result = await clawHealth({ baseUrl: stubUrl });
  assert.equal(result.ok, true);
  assert.equal(result.profile, 'autoclaw');
});

await check('claw adapter turn', async () => {
  const result = await clawTurn({ message: 'hello claw', baseUrl: stubUrl, timeoutSec: 30 });
  assert.equal(result.ok, true);
  assert.equal(result.text, 'reply: hello claw');
});

await check('claw adapter sessions', async () => {
  const result = await clawSessions({ baseUrl: stubUrl, limit: 5 });
  assert.ok(Array.isArray(result.sessions));
  assert.equal(result.count, 1);
});

await check('claw adapter agents', async () => {
  const result = await clawAgents({ baseUrl: stubUrl });
  assert.ok(Array.isArray(result.agents));
  assert.equal(result.agents[0].id, 'main');
});

await check('claw adapter connection error is clear', async () => {
  await assert.rejects(() => clawHealth({ baseUrl: 'http://127.0.0.1:1', timeoutMs: 1500 }), /claw-bridge/);
});

const oldClawUrl = process.env.CLAW_BRIDGE_URL;
process.env.CLAW_BRIDGE_URL = stubUrl;
try {
  await check('rest claw health', async () => {
    const result = await get('/api/claw/health');
    assert.equal(result.status, 200);
    assert.equal(result.body.ok, true);
    assert.equal(result.body.profile, 'autoclaw');
  });
  await check('rest claw turn', async () => {
    const result = await post('/api/claw/turn', { message: 'from rest', timeout_sec: 30 });
    assert.equal(result.status, 200);
    assert.equal(result.body.task.kind, 'claw');
    const deadline = Date.now() + 10000;
    let lastBody;
    while (Date.now() < deadline) {
      const poll = await get(`/api/tasks/${result.body.task.id}`);
      lastBody = poll.body;
      if (lastBody.task.status === 'completed' || lastBody.task.status === 'failed') break;
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 200));
    }
    assert.equal(lastBody.task.status, 'completed', lastBody.task.error || 'claw turn did not complete');
    assert.ok(lastBody.task.lastMessage.includes('from rest'));
  });
  await check('rest claw sessions', async () => {
    const result = await get('/api/claw/sessions?limit=5');
    assert.equal(result.status, 200);
    assert.ok(Array.isArray(result.body.sessions));
  });
  await check('rest claw agents', async () => {
    const result = await get('/api/claw/agents');
    assert.equal(result.status, 200);
    assert.ok(Array.isArray(result.body.agents));
  });
  await check('claw turn can be stopped', async () => {
    const result = await post('/api/claw/turn', { message: 'SLOW STOP_CLAW', timeout_sec: 60 });
    assert.equal(result.status, 200);
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 300));
    const stop = await post(`/api/tasks/${result.body.task.id}/stop`, {});
    assert.equal(stop.status, 200);
    const poll = await get(`/api/tasks/${result.body.task.id}`);
    assert.equal(poll.body.task.status, 'stopped');
  });
} finally {
  if (oldClawUrl === undefined) delete process.env.CLAW_BRIDGE_URL;
  else process.env.CLAW_BRIDGE_URL = oldClawUrl;
}

await check('mcp claw_health call', async () => {
  const old = process.env.CLAW_BRIDGE_URL;
  process.env.CLAW_BRIDGE_URL = stubUrl;
  try {
    const result = await post('/mcp', {
      jsonrpc: '2.0',
      id: 'c1',
      method: 'tools/call',
      params: { name: 'claw_health', arguments: {} },
    });
    assert.equal(result.status, 200);
    const text = result.body.result.content[0].text;
    assert.ok(text.includes('"ok": true'));
  } finally {
    if (old === undefined) delete process.env.CLAW_BRIDGE_URL;
    else process.env.CLAW_BRIDGE_URL = old;
  }
});

await check('file write/read', async () => {
  const target = join(tempDir, 'hello.txt');
  const writeResult = await post('/api/file/write', { path: target, content: 'hello bridge\n' });
  assert.equal(writeResult.status, 200);
  assert.equal(writeResult.body.bytesWritten, 13);
  const readResult = await get(`/api/file/read?path=${encodeURIComponent(target)}`);
  assert.equal(readResult.status, 200);
  assert.equal(readResult.body.content, 'hello bridge\n');
});

await check('file list', async () => {
  const result = await get(`/api/file/list?path=${encodeURIComponent(tempDir)}`);
  assert.equal(result.status, 200);
  assert.ok(result.body.entries.some((entry) => entry.name === 'hello.txt'));
});

await check('shell echo', async () => {
  const command = process.platform === 'win32' ? 'echo pc-bridge-ok' : 'echo pc-bridge-ok';
  const result = await post('/api/shell', { command, timeout_ms: 15000 });
  assert.equal(result.status, 200);
  assert.ok(result.body.stdout.includes('pc-bridge-ok'));
});

await check('git status', async () => {
  const repo = 'D:\\document\\work\\Aether';
  const result = await get(`/api/git/status?cwd=${encodeURIComponent(repo)}`);
  assert.equal(result.status, 200);
  assert.ok(result.body.branch.length > 0);
  assert.ok(Array.isArray(result.body.changes));
});

let execTaskId = '';
let execSessionId = '';
await check('codex exec starts', async () => {
  const result = await post('/api/exec', {
    prompt: 'Reply with exactly BRIDGE_OK and nothing else.',
    cwd: tempDir,
  });
  assert.equal(result.status, 200);
  assert.ok(result.body.task.id);
  execTaskId = result.body.task.id;
});

await check('codex exec completes and streams events', async () => {
  const deadline = Date.now() + 150_000;
  let lastBody = null;
  while (Date.now() < deadline) {
    const result = await get(`/api/tasks/${execTaskId}`);
    assert.equal(result.status, 200);
    lastBody = result.body;
    if (lastBody.task.status === 'completed') break;
    if (lastBody.task.status === 'failed') {
      throw new Error(`task failed: ${lastBody.task.error || lastBody.task.stderrTail}`);
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1500));
  }
  assert.equal(lastBody.task.status, 'completed', 'exec did not complete in time');
  assert.ok(lastBody.task.sessionId, 'session id missing');
  execSessionId = lastBody.task.sessionId;
  assert.ok(lastBody.task.events.length > 0, 'no events streamed');
  const found = lastBody.task.lastMessage.includes('BRIDGE_OK') ||
    lastBody.task.events.some((event) => event.item?.type === 'agent_message' && String(event.item.text).includes('BRIDGE_OK'));
  assert.ok(found, 'agent message did not contain BRIDGE_OK');
  const history = await get(`/api/sessions/${lastBody.task.sessionId}`);
  assert.ok(history.body.messages.some((message) => message.kind === 'assistant' && message.text.includes('BRIDGE_OK')));
});

await check('codex resume rest', async () => {
  const result = await post('/api/resume', {
    session_id: execSessionId,
    prompt: 'Reply with exactly RESUME_OK and nothing else.',
    cwd: tempDir,
  });
  assert.equal(result.status, 200);
  assert.ok(result.body.task.id);
  const deadline = Date.now() + 150_000;
  let lastBody = null;
  while (Date.now() < deadline) {
    const poll = await get(`/api/tasks/${result.body.task.id}`);
    assert.equal(poll.status, 200);
    lastBody = poll.body;
    if (lastBody.task.status === 'completed' || lastBody.task.status === 'failed') break;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1500));
  }
  assert.equal(lastBody.task.status, 'completed', `resume did not complete: ${lastBody.task.error || lastBody.task.stderrTail}`);
  assert.ok(lastBody.task.resumeSessionId, 'resume session id missing');
});

await check('stop task', async () => {
  const startResult = await post('/api/exec', {
    prompt: 'Reply with exactly STOP_ME and nothing else.',
    cwd: tempDir,
  });
  assert.equal(startResult.status, 200);
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 600));
  const stopResult = await post(`/api/tasks/${startResult.body.task.id}/stop`, {});
  assert.equal(stopResult.status, 200);
  const poll = await get(`/api/tasks/${startResult.body.task.id}`);
  assert.ok(['stopped', 'completed', 'failed', 'running'].includes(poll.body.task.status));
});

await new Promise((resolvePromise) => stub.close(resolvePromise));
await bridge.close();
try {
  await rm(tempDir, { recursive: true, force: true });
} catch {
  // Temp cleanup is best-effort.
}

if (failures > 0) {
  console.error(`${failures} smoke check(s) failed`);
  process.exit(1);
}
console.log('all smoke checks passed');
process.exit(0);
