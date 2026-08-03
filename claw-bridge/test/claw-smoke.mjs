// claw-smoke.mjs - smoke test for the gateway WS bridge.
// Requires a running gateway on 18789 (AutoClaw desktop or `node tools/gateway-up.mjs`).
import { resolveProfile } from '../lib/profiles.mjs'
import { checkGatewayHealth } from '../lib/gateway.mjs'
import { openClient, runAgentTurn } from '../lib/turn.mjs'
import { listSessions } from '../lib/sessions.mjs'

const profileId = process.argv.includes('--profile') ? process.argv[process.argv.indexOf('--profile') + 1] : 'autoclaw'
const profile = resolveProfile(profileId)

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` - ${detail}` : ''}`)
}

const health = await checkGatewayHealth(profile)
if (!health.running) {
  console.log(`SKIP gateway not running at ${profile.healthUrl}; start AutoClaw desktop or run: node tools/gateway-up.mjs`)
  process.exit(0)
}

const client = await openClient(profile)
check('connect device auth', client.connected === true)
const scopes = client.hello?.auth?.scopes ?? []
check('connect scopes include operator.write', scopes.includes('operator.write'), scopes.join(','))
client.close()

const turn = await runAgentTurn(profile, { message: 'reply with one short word: OK', agentId: 'main', timeoutSec: 120 })
check('agent rpc final response', Boolean(turn.runId && turn.status === 'ok'), `runId=${turn.runId} status=${turn.status}`)
check('agent rpc returns text field', typeof turn.text === 'string', JSON.stringify(turn.text).slice(0, 120))
if (/401|invalid api key|api key/i.test(turn.text)) {
  console.log('WARN model call failed (standalone gateway has no AutoClaw API keys); protocol still verified')
}

const sessions = await listSessions(profile, { limit: 20 })
check('sessions.list returns array', Array.isArray(sessions), `count=${Array.isArray(sessions) ? sessions.length : 'n/a'}`)

const failed = checks.filter((c) => !c.ok)
console.log(failed.length === 0 ? 'ALL_PASS' : `FAILED ${failed.length}/${checks.length}`)
process.exit(failed.length === 0 ? 0 : 1)