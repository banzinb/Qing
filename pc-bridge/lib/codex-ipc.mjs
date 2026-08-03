import { randomUUID } from 'node:crypto';
import net from 'node:net';

const PIPE_NAME = '\\\\.\\pipe\\codex-ipc';
const MAX_FRAME_BYTES = 256 * 1024 * 1024;
const DEFAULT_TIMEOUT_MS = 5000;

export class CodexIpcClient {
  constructor({ clientType = 'aether-pc-bridge', pipeName = PIPE_NAME } = {}) {
    this.clientType = clientType;
    this.pipeName = pipeName;
    this.clientId = 'initializing-client';
    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.pendingResponses = new Map();
    this.initialized = null;
    this.closed = false;
  }

  async connect({ timeoutMs = 10000 } = {}) {
    if (this.socket) return this;
    this.socket = net.connect(this.pipeName);
    this.socket.setNoDelay(true);
    this.socket.on('data', (chunk) => this.#onData(chunk));
    this.socket.on('error', (error) => {
      this.#rejectAll(error);
    });
    this.socket.on('close', () => {
      this.#rejectAll(new Error('codex-ipc connection closed'));
    });
    await new Promise((resolvePromise, reject) => {
      const timer = setTimeout(() => reject(new Error('codex-ipc connect timed out')), timeoutMs);
      this.socket.once('connect', () => {
        clearTimeout(timer);
        resolvePromise();
      });
      this.socket.once('error', (error) => {
        clearTimeout(timer);
        reject(error);
      });
    });
    const response = await this.sendRequest('initialize', { clientType: this.clientType });
    if (response.resultType !== 'success' || !response.result?.clientId) {
      throw new Error(`codex-ipc initialize failed: ${response.error || JSON.stringify(response.result)}`);
    }
    this.clientId = response.result.clientId;
    this.initialized = response;
    return this;
  }

  sendRequest(method, params, { version = 0, targetClientId = null, timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
    if (!this.socket || !this.socket.writable) return Promise.reject(new Error('not-connected'));
    if (this.clientId === 'initializing-client' && method !== 'initialize') {
      return Promise.reject(new Error('not-initialized'));
    }
    const request = {
      type: 'request',
      requestId: randomUUID(),
      sourceClientId: this.clientId,
      version,
      method,
      params,
      ...(targetClientId ? { targetClientId } : {}),
      timeoutMs,
    };
    return new Promise((resolvePromise, reject) => {
      const timer = setTimeout(() => {
        this.pendingResponses.delete(request.requestId);
        reject(new Error(`codex-ipc request '${method}' timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pendingResponses.set(request.requestId, {
        resolve: resolvePromise,
        reject,
        timer,
      });
      this.#write(request);
    });
  }

  sendBroadcast(method, params, { version = 0, targetClientIds = null } = {}) {
    if (!this.socket || !this.socket.writable) return Promise.reject(new Error('not-connected'));
    const message = {
      type: 'broadcast',
      method,
      sourceClientId: this.clientId,
      version,
      params,
      ...(targetClientIds?.length ? { targetClientIds } : {}),
    };
    return Promise.resolve(this.#write(message));
  }

  close() {
    this.closed = true;
    this.#rejectAll(new Error('codex-ipc client closed'));
    this.socket?.destroy();
    this.socket = null;
  }

  #write(message) {
    const payload = Buffer.from(JSON.stringify(message), 'utf8');
    const frame = Buffer.alloc(4 + payload.length);
    frame.writeUInt32LE(payload.length, 0);
    payload.copy(frame, 4);
    this.socket.write(frame);
  }

  #onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 4) {
      const length = this.buffer.readUInt32LE(0);
      if (length === 0 || length > MAX_FRAME_BYTES) {
        this.#rejectAll(new Error(`[IPC] Invalid frame length (${length} bytes)`));
        this.close();
        return;
      }
      if (this.buffer.length < 4 + length) return;
      const payload = this.buffer.subarray(4, 4 + length).toString('utf8');
      this.buffer = this.buffer.subarray(4 + length);
      let message;
      try {
        message = JSON.parse(payload);
      } catch (error) {
        this.#rejectAll(error);
        this.close();
        return;
      }
      this.#handleMessage(message);
    }
  }

  #handleMessage(message) {
    if (!message || typeof message !== 'object') return;
    if (message.type === 'response') {
      const pending = this.pendingResponses.get(message.requestId);
      if (!pending) return;
      this.pendingResponses.delete(message.requestId);
      clearTimeout(pending.timer);
      pending.resolve(message);
      return;
    }
    if (message.type === 'client-discovery-request') {
      // This client does not expose any app-server request handlers.
      this.#write({
        type: 'client-discovery-response',
        requestId: message.requestId,
        response: { canHandle: false },
      });
      return;
    }
    // Broadcasts and forwarded requests from other clients are intentionally
    // ignored; pc-bridge only sends requests/broadcasts into the router.
  }

  #rejectAll(error) {
    for (const [requestId, pending] of this.pendingResponses) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pendingResponses.delete(requestId);
    }
  }
}

export async function withCodexIpc(fn, options = {}) {
  const client = new CodexIpcClient(options);
  await client.connect();
  try {
    return await fn(client);
  } finally {
    client.close();
  }
}