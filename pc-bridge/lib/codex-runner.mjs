import { spawn, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { safeJsonParse, truncate } from './bridge-core.mjs';
import { isDesktopSyncUnavailableError, sendFollowerStartTurn } from './codex-ipc.mjs';
import { normalizeCwd, readSession, readSessionSafety } from './codex-sessions.mjs';

const MAX_EVENTS_PER_TASK = 500;
const MAX_ITEM_CHARS = 20000;
const MAX_PLAIN_OUTPUT = 40000;
const MAX_TASKS = 200;

let resolvedCodexPath = null;

export async function resolveCodexPath() {
  if (resolvedCodexPath) return resolvedCodexPath;
  const candidates = [];
  if (process.env.CODEX_CLI_PATH) candidates.push(process.env.CODEX_CLI_PATH);
  try {
    const config = await readFile(join(homedir(), '.codex', 'config.toml'), 'utf8');
    const match = config.match(/^\s*CODEX_CLI_PATH\s*=\s*'([^']+)'/m);
    if (match) candidates.push(match[1]);
  } catch {
    // Config file is optional.
  }
  const probe = process.platform === 'win32' ? 'where.exe' : 'which';
  try {
    const result = spawnSync(probe, ['codex'], { encoding: 'utf8', windowsHide: true });
    if (result.status === 0) {
      for (const line of result.stdout.split(/\r?\n/)) {
        const path = line.trim();
        if (path && /codex(\.exe)?$/i.test(path)) candidates.push(path);
      }
    }
  } catch {
    // Fall through to the default command name.
  }
  candidates.sort((a, b) => {
    const aExe = /\.exe$/i.test(a) ? 1 : 0;
    const bExe = /\.exe$/i.test(b) ? 1 : 0;
    return bExe - aExe;
  });
  resolvedCodexPath = candidates.find((path) => path && existsSync(path)) || 'codex';
  return resolvedCodexPath;
}

export class CodexRunner {
  constructor() {
    this.tasks = new Map();
  }

  async startExec({ prompt, cwd, model, sandbox } = {}) {
    const text = String(prompt ?? '').trim();
    if (!text) throw new Error('prompt is required.');
    const codex = await resolveCodexPath();
    const effectiveSandbox = String(sandbox || process.env.PC_BRIDGE_DEFAULT_SANDBOX || 'danger-full-access');
    const task = this.#createTask('exec', { prompt: text, cwd, model, sandbox: effectiveSandbox });
    const args = ['exec'];
    if (text.startsWith('-')) args.push('--');
    args.push(text);
    args.push('--json', '--skip-git-repo-check');
    if (cwd) args.push('--cd', cwd);
    if (model) args.push('-m', model);
    args.push('-s', effectiveSandbox);
    this.#spawn(task, codex, args, cwd);
    // codex exec reads additional input from stdin until EOF; close it so the task can start.
    try { task.child?.stdin?.end(); } catch { /* stdin may already be closed */ }
    return this.#publicTask(task);
  }

  async startResume({ sessionId, prompt, cwd, model } = {}) {
    const text = String(prompt ?? '').trim();
    if (!text) throw new Error('prompt is required.');
    if (!sessionId) throw new Error('sessionId is required.');
    const safeCwd = await this.#assertSafeResume(sessionId, cwd);
    const codex = await resolveCodexPath();
    const task = this.#createTask('resume', { sessionId, prompt: text, cwd: safeCwd, model });
    const args = ['exec', 'resume', sessionId, '-', '--json', '--skip-git-repo-check'];
    if (model) args.push('-m', model);
    this.#spawn(task, codex, args, safeCwd);
    if (task.child?.stdin) {
      task.child.stdin.write(text + '\n');
      task.child.stdin.end();
    }
    return this.#publicTask(task);
  }

  async startDesktopSync({ sessionId, prompt, cwd, model } = {}) {
    const text = String(prompt ?? '').trim();
    if (!text) throw new Error('prompt is required.');
    if (!sessionId) throw new Error('sessionId is required.');
    const safeCwd = await this.#assertSafeResume(sessionId, cwd);
    let baselineMs = Date.now();
    try {
      const baseline = await readSession(sessionId);
      baselineMs = baseline.updatedAtMs || baselineMs;
    } catch {
      // Session file may be mid-write; fall back to the current time.
    }
    try {
      await sendFollowerStartTurn(sessionId, text, { timeoutMs: 15000 });
    } catch (error) {
      if (isDesktopSyncUnavailableError(error)) {
        return this.startResume({ sessionId, prompt: text, cwd, model });
      }
      throw new Error(`Desktop sync failed for session '${sessionId}': ${error.message}`);
    }
    const task = this.#createTask('desktop-sync', {
      sessionId,
      prompt: text,
      cwd: safeCwd,
      model,
      baselineMs,
    });
    task.status = 'running';
    task.startedAt = Date.now();
    task.sessionId = sessionId;
    task.threadId = sessionId;
    this.#pollDesktopSync(task);
    return this.#publicTask(task);
  }

  async #assertSafeResume(sessionId, requestedCwd) {
    let safety;
    try {
      safety = await readSessionSafety(sessionId);
    } catch (error) {
      throw new Error(`Cannot resume session '${sessionId}': ${error.message}`);
    }
    if (safety.blocked) {
      throw new Error(
        `Refusing to resume session '${sessionId}': ${safety.reason || 'restricted read-only sandbox'}. ` +
        'Resuming would inherit the restriction and can leave the session spinning. ' +
        'Start a new task with /api/exec instead.',
      );
    }
    const sessionCwd = safety.cwd || '';
    const requested = String(requestedCwd || '').trim();
    if (requested) {
      const sessionKey = normalizeCwd(sessionCwd);
      const requestedKey = normalizeCwd(requested);
      if (sessionKey && requestedKey && sessionKey !== requestedKey) {
        throw new Error(
          `Refusing to resume session '${sessionId}': requested cwd '${requested}' does not match the session cwd '${sessionCwd}'. ` +
          'Use /api/exec to start a new task in that directory instead.',
        );
      }
    }
    return sessionCwd || requested || process.cwd();
  }

  getTask(id) {
    const task = this.tasks.get(id);
    return task ? this.#publicTask(task) : null;
  }

  listTasks() {
    return [...this.tasks.values()]
      .sort((a, b) => b.createdAt - a.createdAt)
      .map((task) => this.#publicTask(task));
  }

  stopTask(id) {
    const task = this.tasks.get(id);
    if (!task) return null;
    if (task.status !== 'running' || !task.child) {
      if (task.status !== 'completed' && task.status !== 'failed') {
        task.status = 'stopped';
        task.stopping = true;
        if (task.pollTimer) {
          clearTimeout(task.pollTimer);
          task.pollTimer = null;
        }
      }
      return this.#publicTask(task);
    }
    task.stopping = true;
    if (process.platform === 'win32') {
      try {
        spawn('taskkill.exe', ['/pid', String(task.child.pid), '/T', '/F'], {
          windowsHide: true,
          stdio: 'ignore',
        });
      } catch {
        try { task.child.kill(); } catch { /* already gone */ }
      }
    } else {
      try { task.child.kill('SIGTERM'); } catch { /* already gone */ }
      setTimeout(() => {
        try { task.child?.kill('SIGKILL'); } catch { /* already gone */ }
      }, 3000).unref();
    }
    return this.#publicTask(task);
  }

  stopAll() {
    for (const task of this.tasks.values()) {
      if (task.status === 'running' && task.child) this.stopTask(task.id);
    }
  }

  #createTask(kind, options) {
    const task = {
      id: randomUUID(),
      kind,
      ...options,
      status: 'queued',
      events: [],
      stderrTail: '',
      createdAt: Date.now(),
      startedAt: null,
      completedAt: null,
      exitCode: null,
      threadId: null,
      sessionId: null,
      usage: null,
      lastMessage: '',
      error: '',
      stopping: false,
      child: null,
      pollTimer: null,
      baselineMs: null,
    };
    this.tasks.set(task.id, task);
    this.#cleanup();
    return task;
  }

  #spawn(task, codex, args, cwd) {
    let child;
    try {
      child = spawn(codex, args, {
        cwd: cwd || process.cwd(),
        env: process.env,
        windowsHide: true,
        stdio: ['pipe', 'pipe', 'pipe'],
      });
    } catch (error) {
      task.status = 'failed';
      task.error = error.message;
      task.completedAt = Date.now();
      task.lastMessage = `Failed to start codex: ${error.message}`;
      return;
    }
    task.child = child;
    task.status = 'running';
    task.startedAt = Date.now();

    let lineBuffer = '';
    const plainLines = [];
    child.stdout.on('data', (chunk) => {
      lineBuffer += chunk.toString('utf8');
      let newlineIndex;
      while ((newlineIndex = lineBuffer.indexOf('\n')) >= 0) {
        const line = lineBuffer.slice(0, newlineIndex).replace(/\r$/, '');
        lineBuffer = lineBuffer.slice(newlineIndex + 1);
        this.#handleLine(task, line, plainLines);
      }
    });
    child.stderr.on('data', (chunk) => {
      task.stderrTail = (task.stderrTail + chunk.toString('utf8')).slice(-8000);
    });
    child.on('error', (error) => {
      task.error = error.message;
      task.status = task.stopping ? 'stopped' : 'failed';
      task.completedAt = Date.now();
      task.lastMessage = task.lastMessage || `Failed to start codex: ${error.message}`;
    });
    child.on('close', (code) => {
      const remaining = lineBuffer.trim();
      if (remaining) this.#handleLine(task, remaining, plainLines);
      task.exitCode = code;
      if (plainLines.length && !task.lastMessage) {
        task.lastMessage = truncate(plainLines.join('\n').trim(), MAX_PLAIN_OUTPUT);
      }
      if (task.status === 'running') {
        if (task.stopping) {
          task.status = 'stopped';
        } else if (code === 0) {
          task.status = 'completed';
        } else {
          task.status = 'failed';
        }
      }
      task.completedAt = Date.now();
      task.child = null;
    });
  }

  #pollDesktopSync(task) {
    const pollIntervalMs = 1200;
    const maxWaitMs = 10 * 60 * 1000;
    const tick = async () => {
      if (task.stopping || task.status === 'stopped') {
        task.status = 'stopped';
        task.completedAt = Date.now();
        task.pollTimer = null;
        return;
      }
      if (Date.now() - (task.startedAt || task.createdAt) > maxWaitMs) {
        task.status = 'failed';
        task.error = 'Desktop sync timed out waiting for an assistant reply.';
        task.completedAt = Date.now();
        task.lastMessage = task.error;
        task.pollTimer = null;
        return;
      }
      try {
        const session = await readSession(task.sessionId, { afterMs: task.baselineMs });
        const assistant = (session.messages || []).filter((message) => message.kind === 'assistant');
        if (assistant.length > 0) {
          task.lastMessage = truncate(assistant[assistant.length - 1].text, MAX_PLAIN_OUTPUT);
          this.#pushEvent(task, {
            type: 'item.completed',
            item: { type: 'agent_message', text: task.lastMessage },
          });
          task.status = 'completed';
          task.exitCode = 0;
          task.completedAt = Date.now();
          task.pollTimer = null;
          return;
        }
      } catch {
        // Session file may be mid-write; retry until the deadline.
      }
      task.pollTimer = setTimeout(tick, pollIntervalMs);
    };
    task.pollTimer = setTimeout(tick, pollIntervalMs);
  }

  #handleLine(task, rawLine, plainLines) {
    const line = rawLine.trim();
    if (!line) return;
    if (line[0] !== '{') {
      if (line.startsWith('Reading additional input') || line.startsWith('WARN') || line.startsWith('ERROR')) return;
      plainLines.push(line);
      this.#pushEvent(task, { type: 'stdout', text: truncate(line, 4000) });
      return;
    }
    const event = safeJsonParse(line);
    if (!event) {
      plainLines.push(line);
      return;
    }
    if (event.type === 'thread.started') {
      task.threadId = event.thread_id || null;
      task.sessionId = event.thread_id || null;
    } else if (event.type === 'thread.completed') {
      task.status = 'completed';
    } else if (event.type === 'turn.completed') {
      task.usage = event.usage || null;
    } else if (event.type === 'item.completed') {
      const item = event.item || {};
      if (item.type === 'agent_message' && typeof item.text === 'string' && item.text.trim()) {
        task.lastMessage = truncate(item.text, MAX_PLAIN_OUTPUT);
      }
    }
    this.#pushEvent(task, event);
  }

  #pushEvent(task, event) {
    const normalized = { ...event };
    if (normalized.item && typeof normalized.item === 'object') {
      normalized.item = {
        ...normalized.item,
        text: truncate(normalized.item.text, MAX_ITEM_CHARS),
        aggregated_output: truncate(normalized.item.aggregated_output, MAX_ITEM_CHARS),
      };
    }
    task.events.push(normalized);
    if (task.events.length > MAX_EVENTS_PER_TASK) {
      task.events.splice(0, task.events.length - MAX_EVENTS_PER_TASK);
    }
  }

  #publicTask(task) {
    return {
      id: task.id,
      kind: task.kind,
      prompt: task.prompt,
      sessionId: task.sessionId,
      threadId: task.threadId,
      resumeSessionId: task.sessionId || task.resumeSessionId || '',
      cwd: task.cwd || '',
      model: task.model || '',
      sandbox: task.sandbox || '',
      status: task.status,
      createdAt: task.createdAt,
      startedAt: task.startedAt,
      completedAt: task.completedAt,
      exitCode: task.exitCode,
      usage: task.usage,
      lastMessage: task.lastMessage,
      error: task.error,
      stderrTail: task.stderrTail,
      events: task.events,
    };
  }

  #cleanup() {
    if (this.tasks.size <= MAX_TASKS) return;
    const finished = [...this.tasks.values()]
      .filter((task) => ['completed', 'failed', 'stopped'].includes(task.status))
      .sort((a, b) => a.completedAt - b.completedAt);
    while (this.tasks.size > MAX_TASKS && finished.length) {
      const task = finished.shift();
      this.tasks.delete(task.id);
    }
  }
}
