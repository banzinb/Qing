// ws-client.mjs - minimal OpenClaw gateway WS RPC client with device-auth connect.
// Wire format (protocol 3): server sends connect.challenge event; client replies
// with a `connect` request containing auth + optionally a signed device identity.
// Requests are {type:"req", id, method, params}; responses are {type:"res", id, ok, payload|error}.
import { createRequire } from 'node:module'
import { randomUUID } from 'node:crypto'
import { createPrivateKey, createPublicKey, sign } from 'node:crypto'
import { readFileSync, existsSync } from 'node:fs'

const require = createRequire(import.meta.url)
const AUTOCLAW_GATEWAY_ROOT = 'C:/Program Files/AutoClaw/resources/gateway/openclaw'

function resolveWebSocket() {
  try {
    return require(AUTOCLAW_GATEWAY_ROOT + '/node_modules/ws')
  } catch {
    try {
      return require('ws')
    } catch {
      throw new Error('ws module not found; install ws or keep AutoClaw bundled node_modules available')
    }
  }
}

function b64url(buf) {
  return Buffer.from(buf).toString('base64url')
}
function publicKeyRawBase64Url(pem) {
  const jwk = createPublicKey(pem).export({ format: 'jwk' })
  return jwk.x
}
function signDevicePayload(privateKeyPem, payload) {
  const key = createPrivateKey(privateKeyPem)
  return b64url(sign(null, Buffer.from(payload, 'utf8'), key))
}
function buildDeviceAuthPayloadV3(params) {
  return [
    'v3',
    params.deviceId,
    params.clientId,
    params.clientMode,
    params.role,
    params.scopes.join(','),
    String(params.signedAtMs),
    params.token ?? '',
    params.nonce,
    params.platform,
    params.deviceFamily ?? ''
  ].join('|')
}

export function loadDeviceIdentity(profile) {
  if (!profile?.deviceFile || !profile?.deviceAuthFile) return null
  if (!existsSync(profile.deviceFile) || !existsSync(profile.deviceAuthFile)) return null
  try {
    const identity = JSON.parse(readFileSync(profile.deviceFile, 'utf8'))
    const auth = JSON.parse(readFileSync(profile.deviceAuthFile, 'utf8'))
    const operator = auth.tokens?.operator
    if (!identity.deviceId || !identity.privateKeyPem || !identity.publicKeyPem || !operator?.token) return null
    return {
      deviceId: identity.deviceId,
      privateKeyPem: identity.privateKeyPem,
      publicKeyPem: identity.publicKeyPem,
      token: operator.token,
      scopes: Array.isArray(operator.scopes) ? operator.scopes : []
    }
  } catch {
    return null
  }
}

export class GatewayClient {
  constructor(opts) {
    this.url = opts.url
    this.token = opts.token
    this.deviceIdentity = opts.deviceIdentity ?? null
    this.clientId = opts.clientId ?? 'cli'
    this.clientMode = opts.clientMode ?? 'cli'
    this.requestTimeoutMs = opts.requestTimeoutMs ?? 60000
    this.onEvent = opts.onEvent ?? null
    this.ws = null
    this.nonce = null
    this.connectSent = false
    this.hello = null
    this.connected = false
    this.pending = new Map()
    this.readyPromise = null
    this.resolveReady = null
    this.WebSocket = resolveWebSocket()
  }

  connect() {
    if (this.connected || this.ws) return this.readyPromise
    this.readyPromise = new Promise((resolve, reject) => {
      this.resolveReady = resolve
      this.rejectReady = reject
    })
    const ws = new this.WebSocket(this.url)
    this.ws = ws
    ws.on('open', () => {})
    ws.on('error', (err) => {
      this.rejectReady?.(err)
      this.rejectPending(new Error(`gateway ws error: ${err.message}`))
    })
    ws.on('close', (code, reason) => {
      this.connected = false
      this.rejectPending(new Error(`gateway ws closed (${code}) ${String(reason)}`))
      this.rejectReady?.(new Error(`gateway ws closed (${code}) ${String(reason)}`))
    })
    ws.on('message', (raw) => this.handleMessage(raw))
    return this.readyPromise
  }

  handleMessage(raw) {
    let frame
    try {
      frame = JSON.parse(String(raw))
    } catch {
      return
    }
    if (frame.type === 'event' && frame.event === 'connect.challenge') {
      this.nonce = frame.payload?.nonce ?? null
      if (!this.nonce) {
        this.rejectReady?.(new Error('gateway connect challenge missing nonce'))
        this.ws?.close(1008, 'missing nonce')
        return
      }
      this.sendConnect()
      return
    }
    if (frame.type === 'res') {
      const entry = this.pending.get(frame.id)
      if (!entry) return
      if (entry.expectFinal && frame.ok && frame.payload?.status === 'accepted') return
      this.pending.delete(frame.id)
      if (entry.timer) clearTimeout(entry.timer)
      if (frame.ok) entry.resolve(frame.payload)
      else entry.reject(new Error(`gateway rpc ${entry.method} failed: ${frame.error?.message ?? 'unknown error'}`))
      return
    }
    if (frame.type === 'event') {
      this.onEvent?.(frame)
    }
  }

  sendConnect() {
    if (this.connectSent) return
    this.connectSent = true
    const scopes = this.deviceIdentity?.scopes?.length
      ? this.deviceIdentity.scopes
      : ['operator.admin', 'operator.approvals', 'operator.pairing', 'operator.read', 'operator.talk.secrets', 'operator.write']
    const params = {
      minProtocol: 3,
      maxProtocol: 3,
      client: {
        id: this.clientId,
        displayName: 'aether-claw-bridge',
        version: '0.1.0',
        platform: process.platform,
        mode: this.clientMode,
        instanceId: randomUUID()
      },
      caps: [],
      role: 'operator',
      scopes
    }
    if (this.deviceIdentity) {
      const signedAt = Date.now()
      const payload = buildDeviceAuthPayloadV3({
        deviceId: this.deviceIdentity.deviceId,
        clientId: this.clientId,
        clientMode: this.clientMode,
        role: 'operator',
        scopes,
        signedAtMs: signedAt,
        token: this.deviceIdentity.token,
        nonce: this.nonce,
        platform: process.platform,
        deviceFamily: ''
      })
      params.device = {
        id: this.deviceIdentity.deviceId,
        publicKey: publicKeyRawBase64Url(this.deviceIdentity.publicKeyPem),
        signature: signDevicePayload(this.deviceIdentity.privateKeyPem, payload),
        signedAt,
        nonce: this.nonce
      }
      params.auth = { token: this.deviceIdentity.token, deviceToken: this.deviceIdentity.token }
    } else if (this.token) {
      params.auth = { token: this.token }
    }
    this.request('connect', params, { timeoutMs: 15000 })
      .then((hello) => {
        this.hello = hello
        this.connected = true
        this.resolveReady?.(hello)
      })
      .catch((err) => {
        this.connectSent = false
        this.rejectReady?.(err)
        this.ws?.close(1008, 'connect failed')
      })
  }

  request(method, params, opts = {}) {
    if (!this.ws || this.ws.readyState !== this.WebSocket.OPEN) throw new Error('gateway not connected')
    const id = opts.id ?? randomUUID()
    const frame = { type: 'req', id, method, params }
    const expectFinal = opts.expectFinal === true
    const timeoutMs = opts.timeoutMs === null ? null : opts.timeoutMs ?? this.requestTimeoutMs
    const promise = new Promise((resolve, reject) => {
      const timer = timeoutMs === null ? null : setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`gateway request timeout for ${method}`))
      }, timeoutMs)
      this.pending.set(id, { method, expectFinal, resolve, reject, timer })
    })
    this.ws.send(JSON.stringify(frame))
    return promise
  }

  rejectPending(err) {
    for (const [, entry] of this.pending) {
      if (entry.timer) clearTimeout(entry.timer)
      entry.reject(err)
    }
    this.pending.clear()
  }

  close() {
    this.connected = false
    try {
      this.ws?.close()
    } catch {
    }
    this.ws = null
  }
}