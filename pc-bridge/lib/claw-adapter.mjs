// claw-adapter.mjs - HTTP client for the local claw-bridge service.
// Defaults to 127.0.0.1:8900 and can be overridden with CLAW_BRIDGE_URL.

const DEFAULT_CLAW_BRIDGE_URL = 'http://127.0.0.1:8900';

export function resolveClawBridgeUrl(explicitUrl) {
  const value = String(explicitUrl || process.env.CLAW_BRIDGE_URL || DEFAULT_CLAW_BRIDGE_URL).trim();
  return value.replace(/\/+$/, '');
}

async function clawRequest(path, options = {}) {
  const method = options.method || 'GET';
  const base = resolveClawBridgeUrl(options.baseUrl);
  const timeoutMs = options.timeoutMs ?? 15000;
  const headers = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const signal = options.signal || AbortSignal.timeout(timeoutMs);
  try {
    const response = await fetch(base + path, {
      method,
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal,
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const detail = payload && typeof payload.error === 'string' ? `: ${payload.error}` : '';
      throw new Error(`claw-bridge ${path} failed (HTTP ${response.status})${detail}`);
    }
    return payload;
  } catch (error) {
    if (error && error.name === 'TimeoutError') {
      throw new Error(`claw-bridge ${method} ${path} timed out after ${timeoutMs}ms`);
    }
    if (error && error.name === 'AbortError') {
      throw new Error(`claw-bridge ${method} ${path} aborted`);
    }
    throw new Error(`Cannot reach claw-bridge at ${base}: ${error && error.message ? error.message : String(error)}`);
  }
}

export async function clawHealth(options = {}) {
  return clawRequest('/api/health', { baseUrl: options.baseUrl, timeoutMs: options.timeoutMs, signal: options.signal });
}

export async function clawTurn(options = {}) {
  const message = String(options.message ?? '').trim();
  if (!message) throw new Error('message is required.');
  const timeoutSec = Number(options.timeoutSec) || 600;
  const body = { message, agentId: options.agentId || 'main', timeoutSec };
  if (options.sessionKey && String(options.sessionKey).trim()) body.sessionKey = String(options.sessionKey).trim();
  if (options.thinking && String(options.thinking).trim()) body.thinking = String(options.thinking).trim();
  return clawRequest('/api/turn', {
    method: 'POST',
    body,
    baseUrl: options.baseUrl,
    timeoutMs: options.timeoutMs ?? timeoutSec * 1000 + 15000,
    signal: options.signal,
  });
}

export async function clawSessions(options = {}) {
  const limit = Number(options.limit);
  const query = limit > 0 ? `?limit=${limit}` : '';
  return clawRequest('/api/sessions' + query, { baseUrl: options.baseUrl, timeoutMs: options.timeoutMs, signal: options.signal });
}

export async function clawAgents(options = {}) {
  return clawRequest('/api/agents', { baseUrl: options.baseUrl, timeoutMs: options.timeoutMs, signal: options.signal });
}
