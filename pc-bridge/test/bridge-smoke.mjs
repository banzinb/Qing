import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readSessionSafety, scanSessionSafety } from '../lib/codex-sessions.mjs';
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
  for (const expected of ['codex_list_sessions', 'codex_read_session', 'codex_exec', 'codex_resume', 'pc_shell', 'pc_file_read', 'pc_git_status']) {
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
