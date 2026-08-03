import { spawn } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { mkdir, open, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import { truncate } from './bridge-core.mjs';

const DEFAULT_SHELL_TIMEOUT_MS = 60_000;
const MAX_SHELL_OUTPUT_CHARS = 200_000;
const MAX_READ_BYTES = 2 * 1024 * 1024;

function runCaptured(command, args, cwd, timeoutMs, maxOutputChars) {
  return new Promise((resolvePromise) => {
    let child;
    try {
      child = spawn(command, args, { cwd: cwd || process.cwd(), windowsHide: true });
    } catch (error) {
      resolvePromise({ exitCode: -1, stdout: '', stderr: error.message, timedOut: false, error: error.message });
      return;
    }
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    let settled = false;
    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill(); } catch { /* already gone */ }
    }, timeoutMs);
    child.stdout.on('data', (chunk) => {
      stdout = (stdout + chunk.toString('utf8')).slice(0, maxOutputChars + 4000);
    });
    child.stderr.on('data', (chunk) => {
      stderr = (stderr + chunk.toString('utf8')).slice(0, maxOutputChars + 4000);
    });
    child.on('error', (error) => {
      clearTimeout(timer);
      if (settled) return;
      settled = true;
      resolvePromise({ exitCode: -1, stdout, stderr, timedOut, error: error.message });
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (settled) return;
      settled = true;
      resolvePromise({
        exitCode: code,
        stdout: truncate(stdout, maxOutputChars),
        stderr: truncate(stderr, maxOutputChars),
        timedOut,
        error: '',
      });
    });
  });
}

export function runShell({ command, cwd, timeoutMs = DEFAULT_SHELL_TIMEOUT_MS } = {}) {
  if (!command || typeof command !== 'string') return Promise.reject(new Error('command is required.'));
  const shell = process.platform === 'win32' ? 'cmd.exe' : '/bin/sh';
  const args = process.platform === 'win32' ? ['/d', '/s', '/c', command] : ['-c', command];
  const capped = Math.max(1, Math.min(Number(timeoutMs) || DEFAULT_SHELL_TIMEOUT_MS, 600_000));
  return runCaptured(shell, args, cwd, capped, MAX_SHELL_OUTPUT_CHARS);
}

export async function readFileTool({ path, maxBytes = MAX_READ_BYTES, limitLines } = {}) {
  if (!path) throw new Error('path is required.');
  const resolved = resolve(String(path));
  const info = await stat(resolved);
  if (info.isDirectory()) throw new Error('Path is a directory, not a file.');
  const cap = Math.max(1, Math.min(Number(maxBytes) || MAX_READ_BYTES, 16 * 1024 * 1024));
  let content;
  let truncated = false;
  if (info.size > cap) {
    const handle = await open(resolved, 'r');
    try {
      const buffer = Buffer.alloc(cap);
      const { bytesRead } = await handle.read(buffer, 0, cap, 0);
      content = buffer.toString('utf8', 0, bytesRead);
    } finally {
      await handle.close();
    }
    truncated = true;
  } else {
    content = await readFile(resolved, 'utf8');
  }
  const requestedLines = Number(limitLines);
  if (requestedLines > 0) {
    const lines = content.split(/\r?\n/);
    if (lines.length > requestedLines) {
      content = lines.slice(0, requestedLines).join('\n');
      truncated = true;
    }
  }
  return {
    path: resolved,
    size: info.size,
    truncated,
    lineCount: content.split(/\r?\n/).length,
    content,
  };
}

export async function writeFileTool({ path, content, append = false } = {}) {
  if (!path) throw new Error('path is required.');
  const resolved = resolve(String(path));
  await mkdir(dirname(resolved), { recursive: true });
  const payload = String(content ?? '');
  await writeFile(resolved, payload, { flag: append ? 'a' : 'w', encoding: 'utf8' });
  return { path: resolved, bytesWritten: Buffer.byteLength(payload), append: Boolean(append) };
}

export async function listDirTool({ path, recursive = false, maxDepth = 2, limit = 500 } = {}) {
  if (!path) throw new Error('path is required.');
  const resolved = resolve(String(path));
  const info = await stat(resolved);
  if (!info.isDirectory()) throw new Error('Path is not a directory.');
  const depthCap = Math.max(0, Math.min(Number(maxDepth) || 0, 6));
  const entryLimit = Math.max(1, Math.min(Number(limit) || 500, 5000));
  const entries = [];
  async function walk(dir, depth) {
    if (entries.length >= entryLimit) return;
    let children;
    try {
      children = await readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const child of children) {
      if (entries.length >= entryLimit) break;
      const full = join(dir, child.name);
      const item = {
        name: child.name,
        path: full,
        type: child.isDirectory() ? 'dir' : 'file',
        size: 0,
        mtimeMs: 0,
      };
      if (child.isDirectory()) {
        try {
          item.mtimeMs = (await stat(full)).mtimeMs;
        } catch {
          // Ignore inaccessible directories.
        }
        entries.push(item);
        if (recursive && depth < depthCap) await walk(full, depth + 1);
      } else if (child.isFile()) {
        try {
          const childInfo = await stat(full);
          item.size = childInfo.size;
          item.mtimeMs = childInfo.mtimeMs;
        } catch {
          // Ignore inaccessible files.
        }
        entries.push(item);
      }
    }
  }
  await walk(resolved, 0);
  return { path: resolved, entries, truncated: entries.length >= entryLimit };
}

export async function gitStatusTool({ cwd } = {}) {
  const base = cwd || process.cwd();
  const status = await runCaptured('git', ['status', '--porcelain=v1', '-b'], base, 30_000, 100_000);
  const log = await runCaptured(
    'git',
    ['log', '-1', '--format=%h|%an|%s|%ad', '--date=iso'],
    base,
    30_000,
    10_000,
  );
  const lines = status.stdout.split(/\r?\n/).filter(Boolean);
  return {
    cwd: base,
    branch: lines[0] || '',
    changes: lines.slice(1),
    lastCommit: log.stdout.trim(),
    gitError: status.error || log.error || (status.exitCode !== 0 ? status.stderr : '') || (log.exitCode !== 0 ? log.stderr : ''),
  };
}