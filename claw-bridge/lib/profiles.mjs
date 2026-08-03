// profiles.mjs - AutoClaw / OpenClaw connection profiles.
// AutoClaw (local desktop): bundled gateway under Program Files, state in ~/.openclaw-autoclaw.
// OpenClaw (class-teacher server): plain OpenClaw install, state in ~/.openclaw (profile is a config variant).
import { homedir } from 'node:os'
import { join } from 'node:path'
import { existsSync } from 'node:fs'

const AUTOCLAW_HOME_NAME = '.openclaw-autoclaw'
const AUTOCLAW_RESOURCES = 'C:\\Program Files\\AutoClaw\\resources'

export function autoClawProfile(env = process.env) {
  const stateDir = env.OPENCLAW_STATE_DIR?.trim() || join(homedir(), AUTOCLAW_HOME_NAME)
  const runtimeConfig = join(stateDir, 'openclaw.runtime.json')
  const configPath = env.OPENCLAW_CONFIG_PATH?.trim() || (existsSync(runtimeConfig) ? runtimeConfig : join(stateDir, 'openclaw.json'))
  return {
    id: 'autoclaw',
    displayName: 'AutoClaw (本机桌面)',
    stateDir,
    configPath,
    gatewayUrl: env.OPENCLAW_GATEWAY_URL?.trim() || 'ws://127.0.0.1:18789',
    gatewayPort: Number(env.OPENCLAW_GATEWAY_PORT?.trim() || 18789),
    nodeBin: env.OPENCLAW_NODE_BIN?.trim() || join(AUTOCLAW_RESOURCES, 'node', 'node.exe'),
    bundleEntry: env.OPENCLAW_BUNDLE_ENTRY?.trim() || join(AUTOCLAW_RESOURCES, 'gateway', 'openclaw', 'gateway-bundle.mjs'),
    launcherPath: env.OPENCLAW_LAUNCHER?.trim() || join(stateDir, 'gateway-launcher.cjs'),
    bundledPluginsDir: env.OPENCLAW_BUNDLED_PLUGINS_DIR?.trim() || join(AUTOCLAW_RESOURCES, 'gateway', 'openclaw', 'extensions'),
    pluginStageDir: env.OPENCLAW_PLUGIN_STAGE_DIR?.trim() || join(stateDir, 'plugin-runtime-deps'),
    tokenFile: join(stateDir, '.gateway-token'),
    deviceFile: join(stateDir, 'identity', 'device.json'),
    deviceAuthFile: join(stateDir, 'identity', 'device-auth.json'),
    healthUrl: `http://127.0.0.1:${Number(env.OPENCLAW_GATEWAY_PORT?.trim() || 18789)}/health`
  }
}

export function openClawProfile(env = process.env) {
  const stateDir = env.OPENCLAW_STATE_DIR?.trim() || env.OPENCLAW_HOME?.trim() || join(homedir(), '.openclaw')
  const configPath = env.OPENCLAW_CONFIG_PATH?.trim() || join(stateDir, 'openclaw.json')
  const port = Number(env.OPENCLAW_GATEWAY_PORT?.trim() || 18789)
  return {
    id: 'openclaw',
    displayName: 'OpenClaw (班主任)',
    stateDir,
    configPath,
    gatewayUrl: env.OPENCLAW_GATEWAY_URL?.trim() || `ws://127.0.0.1:${port}`,
    gatewayPort: port,
    nodeBin: env.OPENCLAW_NODE_BIN?.trim() || 'node',
    bundleEntry: env.OPENCLAW_BUNDLE_ENTRY?.trim() || null,
    launcherPath: null,
    bundledPluginsDir: env.OPENCLAW_BUNDLED_PLUGINS_DIR?.trim() || null,
    pluginStageDir: env.OPENCLAW_PLUGIN_STAGE_DIR?.trim() || join(stateDir, 'plugin-runtime-deps'),
    tokenFile: join(stateDir, '.gateway-token'),
    deviceFile: join(stateDir, 'identity', 'device.json'),
    deviceAuthFile: join(stateDir, 'identity', 'device-auth.json'),
    healthUrl: `http://127.0.0.1:${port}/health`
  }
}

export function resolveProfile(id = 'autoclaw', env = process.env) {
  if (id === 'openclaw' || id === 'openclaw-oss') return openClawProfile(env)
  return autoClawProfile(env)
}