// gateway.mjs - health detection and optional standalone gateway spawning.
// Prefer the AutoClaw desktop gateway (it injects API keys). Only spawn a
// standalone gateway when AutoClaw is not running and the user asks for it.
import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { writeFileSync, readFileSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'

export async function checkGatewayHealth(profile, { timeoutMs = 3000 } = {}) {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const res = await fetch(profile.healthUrl, { signal: ctrl.signal })
    const body = await res.json().catch(() => null)
    return { running: res.ok, status: res.status, health: body }
  } catch {
    return { running: false, status: null, health: null }
  } finally {
    clearTimeout(timer)
  }
}

export function resolveGatewayToken(profile, { env = process.env, explicit } = {}) {
  if (explicit?.trim()) return explicit.trim()
  if (env.OPENCLAW_GATEWAY_TOKEN?.trim()) return env.OPENCLAW_GATEWAY_TOKEN.trim()
  try {
    if (existsSync(profile.tokenFile)) {
      const token = readFileSync(profile.tokenFile, 'utf8').trim()
      if (token) return token
    }
  } catch {
  }
  return null
}

export async function waitForGatewayHealth(profile, { timeoutMs = 90000, pollMs = 1000 } = {}) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    last = await checkGatewayHealth(profile, { timeoutMs: Math.min(3000, Math.max(500, deadline - Date.now())) })
    if (last.running) return { ...last, ready: true }
    await new Promise((resolve) => setTimeout(resolve, pollMs))
  }
  return { ...last, ready: false }
}

export async function ensureGateway(profile, { autoStart = false, token, timeoutMs = 90000, log = console } = {}) {
  const current = await checkGatewayHealth(profile)
  if (current.running) {
    return { running: true, source: 'existing', pid: null, health: current.health, started: false }
  }
  if (!autoStart) {
    return { running: false, source: 'none', pid: null, health: null, started: false }
  }
  const child = spawnStandaloneGateway(profile, { token, log })
  const result = await waitForGatewayHealth(profile, { timeoutMs })
  if (!result.ready) {
    try {
      child.kill()
    } catch {
    }
    throw new Error(`standalone gateway did not become healthy within ${timeoutMs}ms`)
  }
  return { running: true, source: 'standalone', pid: child.pid, health: result.health, started: true, child }
}

export function spawnStandaloneGateway(profile, { token, log = console } = {}) {
  if (!profile.nodeBin || !profile.launcherPath || !existsSync(profile.launcherPath)) {
    throw new Error(`profile ${profile.id} has no AutoClaw gateway launcher at ${profile.launcherPath ?? '(none)'}`)
  }
  const gatewayToken = token?.trim() || randomBytes(32).toString('hex')
  const env = {
    ...process.env,
    OPENCLAW_STATE_DIR: profile.stateDir,
    OPENCLAW_CONFIG_PATH: profile.configPath,
    OPENCLAW_BUNDLED_PLUGINS_DIR: profile.bundledPluginsDir,
    OPENCLAW_PLUGIN_STAGE_DIR: profile.pluginStageDir,
    OPENCLAW_DISABLE_BONJOUR: '1',
    JITI_FS_CACHE: 'false',
    NODE_COMPILE_CACHE: join(profile.stateDir, '.compile-cache')
  }
  const args = [
    '--no-warnings',
    profile.launcherPath,
    'gateway',
    'run',
    '--port',
    String(profile.gatewayPort),
    '--bind',
    'loopback',
    '--force',
    '--allow-unconfigured',
    '--auth',
    'token',
    '--token',
    gatewayToken
  ]
  log.info?.(`[claw-bridge] spawning standalone gateway: ${profile.nodeBin} ${args.slice(0, 6).join(' ')} ...`)
  const child = spawn(profile.nodeBin, args, {
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    detached: false
  })
  child.stdout?.on('data', (chunk) => log.info?.(`[gateway] ${String(chunk).trimEnd()}`))
  child.stderr?.on('data', (chunk) => log.warn?.(`[gateway] ${String(chunk).trimEnd()}`))
  child.on('error', (err) => log.error?.(`[gateway] spawn error: ${err.message}`))
  child.on('exit', (code, signal) => log.warn?.(`[gateway] exited code=${code} signal=${signal}`))
  try {
    writeFileSync(profile.tokenFile, gatewayToken, 'utf8')
  } catch (err) {
    log.warn?.(`[claw-bridge] could not write ${profile.tokenFile}: ${err.message}`)
  }
  return child
}

export function safeReadTokenFile(profile) {
  try {
    return existsSync(profile.tokenFile) ? readFileSync(profile.tokenFile, 'utf8').trim() : null
  } catch {
    return null
  }
}