import { randomBytes, timingSafeEqual } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';

export const BRIDGE_VERSION = '0.1.0';
export const MCP_PROTOCOL_VERSION = '2025-11-25';
export const DEFAULT_PORT = 8899;
export const DEFAULT_MAX_JSON_BODY_BYTES = 2 * 1024 * 1024;

export function codexHome() {
  return process.env.CODEX_HOME || join(homedir(), '.codex');
}

export function truncate(value, maxLength) {
  if (typeof value !== 'string') value = String(value ?? '');
  if (value.length <= maxLength) return value;
  return value.slice(0, maxLength) + '\n...[truncated]';
}

export function safeJsonParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const eq = arg.indexOf('=');
    if (eq >= 0) {
      args[arg.slice(2, eq)] = arg.slice(eq + 1);
      continue;
    }
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next !== undefined && !next.startsWith('--')) {
      args[key] = next;
      i++;
    } else {
      args[key] = true;
    }
  }
  return args;
}

export function timingSafeEqualString(a, b) {
  const left = Buffer.from(String(a ?? ''));
  const right = Buffer.from(String(b ?? ''));
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

export function newSessionId() {
  return randomBytes(16).toString('hex');
}

export function readJsonBody(req, limitBytes = DEFAULT_MAX_JSON_BODY_BYTES) {
  return new Promise((resolvePromise, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limitBytes) {
        reject(new Error(`Request body exceeds ${limitBytes} bytes.`));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (size === 0) {
        resolvePromise({});
        return;
      }
      const text = Buffer.concat(chunks).toString('utf8');
      try {
        resolvePromise(JSON.parse(text));
      } catch {
        reject(new Error('Invalid JSON body.'));
      }
    });
    req.on('error', reject);
  });
}

export function sendJson(res, status, body, extraHeaders = {}) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
    ...extraHeaders,
  });
  res.end(payload);
}

export function sendText(res, status, text, extraHeaders = {}) {
  res.writeHead(status, {
    'Content-Type': 'text/plain; charset=utf-8',
    ...extraHeaders,
  });
  res.end(text);
}

export function sendEmpty(res, status = 204, extraHeaders = {}) {
  res.writeHead(status, { 'Content-Length': 0, ...extraHeaders });
  res.end();
}

export function isUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(String(value ?? ''));
}