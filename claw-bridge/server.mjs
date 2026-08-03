// server.mjs - claw-bridge REST server.
// Routes:
//   GET  /api/health              profile + gateway status
//   POST /api/turn                {message, agentId?, sessionKey?, thinking?, timeoutSec?}
//   GET  /api/sessions?limit=50
//   GET  /api/agents
import { createServer } from 'node:http'
import { resolveProfile } from './lib/profiles.mjs'
import { checkGatewayHealth, ensureGateway } from './lib/gateway.mjs'
import { runAgentTurn } from './lib/turn.mjs'
import { listSessions, listAgents } from './lib/sessions.mjs'

const DEFAULT_PORT = 8900

function parseArgs(argv) {
  const out = { profile: 'autoclaw', port: DEFAULT_PORT, autoStart: false, token: null }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (arg === '--profile') out.profile = argv[++i] ?? out.profile
    else if (arg === '--port') out.port = Number(argv[++i]) || DEFAULT_PORT
    else if (arg === '--auto-start-gateway') out.autoStart = true
    else if (arg === '--gateway-token') out.token = argv[++i] ?? null
  }
  return out
}

function json(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
  })
  res.end(payload)
}

async function readJsonBody(req) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  const raw = Buffer.concat(chunks).toString('utf8')
  if (!raw.trim()) return {}
  try {
    return JSON.parse(raw)
  } catch {
    throw new Error('invalid JSON body')
  }
}

export async function startBridge(options = {}) {
  const profile = resolveProfile(options.profile ?? 'autoclaw')
  const port = options.port ?? DEFAULT_PORT
  const gateway = await checkGatewayHealth(profile)
  let gatewayState = { running: gateway.running, health: gateway.health, source: gateway.running ? 'existing' : 'none' }
  if (!gateway.running && options.autoStart) {
    const started = await ensureGateway(profile, { autoStart: true, token: options.token })
    gatewayState = { running: true, health: started.health, source: 'standalone' }
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
    if (req.method === 'OPTIONS') {
      json(res, 204, {})
      return
    }
    try {
      if (req.method === 'GET' && url.pathname === '/api/health') {
        const current = await checkGatewayHealth(profile)
        json(res, 200, {
          ok: true,
          profile: profile.id,
          gateway: { running: current.running, health: current.health },
          time: Date.now()
        })
        return
      }
      if (req.method === 'POST' && url.pathname === '/api/turn') {
        const body = await readJsonBody(req)
        const result = await runAgentTurn(profile, {
          message: body.message,
          agentId: body.agentId,
          sessionKey: body.sessionKey,
          thinking: body.thinking,
          timeoutSec: body.timeoutSec,
          token: options.token
        })
        json(res, result.ok ? 200 : 502, result)
        return
      }
      if (req.method === 'GET' && url.pathname === '/api/sessions') {
        const limit = Number(url.searchParams.get('limit')) || 50
        const sessions = await listSessions(profile, { limit, token: options.token })
        json(res, 200, { ok: true, count: sessions.length, sessions })
        return
      }
      if (req.method === 'GET' && url.pathname === '/api/agents') {
        const agents = await listAgents(profile, { token: options.token })
        json(res, 200, { ok: true, agents })
        return
      }
      json(res, 404, { ok: false, error: 'not found' })
    } catch (err) {
      json(res, 500, { ok: false, error: err?.message ?? String(err) })
    }
  })
  await new Promise((resolve) => server.listen(port, '0.0.0.0', resolve))
  const address = server.address()
  return { server, port: typeof address === 'object' && address ? address.port : port, profile, gatewayState }
}

const main = async () => {
  const opts = parseArgs(process.argv.slice(2))
  try {
    const state = await startBridge(opts)
    console.log(`[claw-bridge] profile=${state.profile.id} gateway=${state.gatewayState.running ? 'running' : 'down'} (${state.gatewayState.source})`)
    console.log(`[claw-bridge] listening on http://127.0.0.1:${state.port}`)
  } catch (err) {
    console.error(`[claw-bridge] failed to start: ${err?.message ?? err}`)
    process.exit(1)
  }
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/'))) {
  main()
}