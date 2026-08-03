// gateway-up.mjs - start a standalone AutoClaw gateway when the desktop app is closed.
// Mirrors the AutoClaw desktop launcher: gateway-bundle.mjs + gateway run + token auth.
// The token is written to ~/.openclaw-autoclaw/.gateway-token so claw-bridge can use it.
import { resolveProfile } from '../lib/profiles.mjs'
import { checkGatewayHealth, ensureGateway } from '../lib/gateway.mjs'

const profileId = process.argv.includes('--profile') ? process.argv[process.argv.indexOf('--profile') + 1] : 'autoclaw'
const profile = resolveProfile(profileId)

const current = await checkGatewayHealth(profile)
if (current.running) {
  console.log(`[gateway-up] gateway already running: ${profile.healthUrl} (${current.status})`)
  process.exit(0)
}

const log = {
  info: (msg) => console.log(msg),
  warn: (msg) => console.warn(msg),
  error: (msg) => console.error(msg)
}
const result = await ensureGateway(profile, { autoStart: true, log, timeoutMs: 90000 })
console.log(`[gateway-up] standalone gateway ready pid=${result.pid} health=${profile.healthUrl}`)
console.log(`[gateway-up] token file=${profile.tokenFile}`)