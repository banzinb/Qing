// turn.mjs - run one agent turn through the gateway WS RPC.
import { randomUUID } from 'node:crypto'
import { GatewayClient, loadDeviceIdentity } from './ws-client.mjs'
import { resolveGatewayToken } from './gateway.mjs'

export async function openClient(profile, { token, env = process.env } = {}) {
  const deviceIdentity = loadDeviceIdentity(profile)
  const gatewayToken = resolveGatewayToken(profile, { env, explicit: token })
  const client = new GatewayClient({
    url: profile.gatewayUrl,
    token: gatewayToken,
    deviceIdentity
  })
  await client.connect()
  return client
}

export async function runAgentTurn(profile, opts = {}) {
  const {
    message,
    agentId = 'main',
    sessionKey,
    thinking,
    timeoutSec = 600,
    token,
    env = process.env
  } = opts
  if (!message?.trim()) throw new Error('message is required')
  const client = await openClient(profile, { token, env })
  try {
    const params = {
      message: message.trim(),
      agentId,
      idempotencyKey: randomUUID(),
      timeout: Number(timeoutSec) || 600
    }
    if (sessionKey?.trim()) params.sessionKey = sessionKey.trim()
    if (thinking?.trim()) params.thinking = thinking.trim()
    const resp = await client.request('agent', params, { expectFinal: true, timeoutMs: null })
    return parseAgentResult(resp)
  } finally {
    client.close()
  }
}

export function parseAgentResult(resp) {
  const runId = resp?.runId ?? null
  const status = resp?.status ?? 'unknown'
  const payloads = Array.isArray(resp?.result?.payloads) ? resp.result.payloads : []
  const text = payloads.map((p) => p?.text ?? '').join('')
  const meta = resp?.result?.meta ?? null
  const sessionId = meta?.agentMeta?.sessionId ?? null
  const provider = meta?.agentMeta?.provider ?? null
  const model = meta?.agentMeta?.model ?? null
  const errorLike = !text && status !== 'ok'
  return {
    ok: status === 'ok' && !errorLike,
    runId,
    status,
    text,
    sessionId,
    provider,
    model,
    raw: resp,
    error: errorLike ? (resp?.error?.message ?? 'agent turn returned no text') : null
  }
}