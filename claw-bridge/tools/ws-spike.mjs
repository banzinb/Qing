#!/usr/bin/env node
// ws-spike.mjs - validate OpenClaw/AutoClaw gateway WS RPC round trip.
// Device flow: reads ~/.openclaw-autoclaw/identity/* and signs the connect challenge.
// Usage:
//   node ws-spike.mjs --url ws://127.0.0.1:18789 --message "hi" [--agent main] [--device-dir C:\Users\x\.openclaw-autoclaw] [--timeout 90]
//   node ws-spike.mjs --url ws://127.0.0.1:18789 --token <plain-token> --message "hi"
import { createRequire } from 'node:module'
import { randomUUID } from 'node:crypto'
import { createPrivateKey, createPublicKey, sign } from 'node:crypto'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { homedir } from 'node:os'

const args = process.argv.slice(2)
function argValue(name, fallback = '') {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}
const url = argValue('url', 'ws://127.0.0.1:18789')
const explicitToken = argValue('token')
const message = argValue('message', 'ping')
const agentId = argValue('agent', 'main')
const deviceDir = argValue('device-dir', join(homedir(), '.openclaw-autoclaw'))
const timeoutSec = Number(argValue('timeout', '90')) || 90

const require = createRequire(import.meta.url)
const gatewayRoot = 'C:/Program Files/AutoClaw/resources/gateway/openclaw'
let WebSocket
try {
  WebSocket = require(gatewayRoot + '/node_modules/ws')
} catch {
  WebSocket = require('ws')
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

let deviceIdentity = null
if (!explicitToken) {
  const identityPath = join(deviceDir, 'identity', 'device.json')
  const authPath = join(deviceDir, 'identity', 'device-auth.json')
  if (existsSync(identityPath) && existsSync(authPath)) {
    const identity = JSON.parse(readFileSync(identityPath, 'utf8'))
    const auth = JSON.parse(readFileSync(authPath, 'utf8'))
    const operator = auth.tokens?.operator
    if (identity.deviceId && identity.privateKeyPem && identity.publicKeyPem && operator?.token) {
      deviceIdentity = {
        deviceId: identity.deviceId,
        privateKeyPem: identity.privateKeyPem,
        publicKeyPem: identity.publicKeyPem,
        token: operator.token,
        scopes: operator.scopes ?? []
      }
      console.log('device identity loaded', deviceIdentity.deviceId.slice(0, 8))
    }
  }
}

const ws = new WebSocket(url)
let helloOk = null
let turnDone = false
let assistantText = ''
let lifecycleEnded = false
let replyError = null
const AGENT_REQ_ID = 'agent-req-1'
const CONNECT_REQ_ID = 'connect-req-1'
let nonce = null

function sendFrame(frame) {
  ws.send(JSON.stringify(frame))
}

function connect() {
  const scopes = deviceIdentity?.scopes ?? ['operator.admin', 'operator.approvals', 'operator.pairing', 'operator.read', 'operator.talk.secrets', 'operator.write']
  const params = {
    minProtocol: 3,
    maxProtocol: 3,
    client: {
      id: 'cli',
      displayName: 'aether-claw-bridge',
      version: '0.1.0',
      platform: process.platform,
      mode: 'cli',
      instanceId: randomUUID()
    },
    caps: [],
    role: 'operator',
    scopes
  }
  if (deviceIdentity) {
    const signedAt = Date.now()
    const payload = buildDeviceAuthPayloadV3({
      deviceId: deviceIdentity.deviceId,
      clientId: 'cli',
      clientMode: 'cli',
      role: 'operator',
      scopes,
      signedAtMs: signedAt,
      token: deviceIdentity.token,
      nonce,
      platform: process.platform,
      deviceFamily: ''
    })
    params.device = {
      id: deviceIdentity.deviceId,
      publicKey: publicKeyRawBase64Url(deviceIdentity.publicKeyPem),
      signature: signDevicePayload(deviceIdentity.privateKeyPem, payload),
      signedAt,
      nonce
    }
    params.auth = { token: deviceIdentity.token, deviceToken: deviceIdentity.token }
  } else if (explicitToken) {
    params.auth = { token: explicitToken }
  }
  sendFrame({ type: 'req', id: CONNECT_REQ_ID, method: 'connect', params })
}

function runAgentTurn() {
  const params = {
    message,
    agentId,
    idempotencyKey: randomUUID(),
    timeout: timeoutSec
  }
  sendFrame({ type: 'req', id: AGENT_REQ_ID, method: 'agent', params })
}

function extractChatText(payload) {
  const msg = payload?.message
  if (!msg) return ''
  if (typeof msg === 'string') return msg
  const content = Array.isArray(msg.content) ? msg.content : []
  return content
    .map((part) => (typeof part === 'string' ? part : part?.text ?? ''))
    .join('')
}

const timer = setTimeout(() => {
  if (!turnDone) {
    console.error(`TIMEOUT after ${timeoutSec}s (hello=${!!helloOk}, assistant=${JSON.stringify(assistantText)})`)
    process.exit(2)
  }
}, timeoutSec * 1000 + 5000)

ws.on('open', () => console.log('WS open', url))
ws.on('message', (raw) => {
  let frame
  try { frame = JSON.parse(String(raw)) } catch { return }
  if (frame.type === 'event' && frame.event === 'connect.challenge') {
    nonce = frame.payload?.nonce
    console.log('connect.challenge nonce len', nonce ? nonce.length : 0)
    connect()
    return
  }
  if (frame.type === 'res' && frame.id === CONNECT_REQ_ID) {
    helloOk = frame.ok ? frame.payload : frame.error
    console.log('connect response', JSON.stringify(frame.ok ? { ok: true, auth: frame.payload?.auth } : frame.error).slice(0, 300))
    if (!frame.ok) { console.error('connect failed'); process.exit(1) }
    runAgentTurn()
    return
  }
  if (frame.type === 'res' && frame.id === AGENT_REQ_ID) {
    const st = frame.payload?.status
    console.log('agent rpc response', st, JSON.stringify(frame).slice(0, 500))
    if (st === 'accepted') return
    turnDone = true
    clearTimeout(timer)
    if (!frame.ok || st === 'error' || st === 'failed') {
      replyError = frame.error?.message ?? frame.payload?.error?.message ?? 'agent rpc failed'
      console.error('agent rpc error:', replyError)
      ws.close()
      process.exit(1)
    }
    const payloads = frame.payload?.result?.payloads ?? []
    const text = payloads.map((p) => p?.text ?? '').join('')
    assistantText = text || assistantText
    console.log('FINAL', JSON.stringify(assistantText))
    console.log('RESULT_OK', !replyError)
    ws.close()
    process.exit(replyError ? 1 : 0)
  }
  if (frame.type === 'event') {
    const evt = frame.event
    const payload = frame.payload ?? {}
    if (evt === 'chat') {
      console.log('chat event', payload.state, 'run', payload.runId)
      if (payload.state === 'delta' || payload.state === 'final') {
        const text = extractChatText(payload)
        if (text) assistantText += text
      }
      if (payload.state === 'final' || payload.state === 'aborted' || payload.state === 'error') {
        if (payload.state === 'error' && !replyError) replyError = payload.errorMessage ?? 'chat error'
        lifecycleEnded = true
      }
    } else if (evt === 'agent') {
      const stream = payload.stream
      const data = payload.data ?? {}
      if (stream === 'assistant' && typeof data.text === 'string') {
        assistantText += data.delta ?? data.text
      }
      if (stream === 'lifecycle' && data.phase === 'end') lifecycleEnded = true
      if (stream === 'lifecycle' && data.phase === 'error' && !replyError) replyError = data.error ?? 'agent error'
    } else {
      console.log('event', evt, JSON.stringify(payload).slice(0, 120))
    }
    if (lifecycleEnded && !turnDone) {
      turnDone = true
      clearTimeout(timer)
      console.log('FINAL', JSON.stringify(assistantText))
      console.log('RESULT_OK', !replyError)
      ws.close()
      process.exit(replyError ? 1 : 0)
    }
  }
})
ws.on('error', (err) => { console.error('WS error', err.message); process.exit(1) })
ws.on('close', (code, reason) => {
  if (!turnDone) console.error('WS closed before turn done', code, String(reason))
})