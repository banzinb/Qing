// sessions.mjs - session and agent listing via gateway RPC.
import { openClient } from './turn.mjs'

function normalizeSessions(payload) {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.sessions)) return payload.sessions
  return []
}

export async function listSessions(profile, { limit = 50, token, env } = {}) {
  const client = await openClient(profile, { token, env })
  try {
    const payload = await client.request('sessions.list', {
      limit: Number(limit) || 50,
      includeDerivedTitles: true,
      includeLastMessage: true
    })
    return normalizeSessions(payload)
  } finally {
    client.close()
  }
}

export async function listAgents(profile, { token, env } = {}) {
  const client = await openClient(profile, { token, env })
  try {
    const payload = await client.request('agents.list', {})
    return Array.isArray(payload?.agents) ? payload.agents : []
  } finally {
    client.close()
  }
}

export async function resolveSessionKey(profile, { agentId = 'main', sessionKey, token, env } = {}) {
  if (sessionKey?.trim()) return sessionKey.trim()
  const sessions = await listSessions(profile, { limit: 100, token, env })
  const matches = sessions.filter((s) => !agentId || s.agentId === agentId || s.key?.includes(`:${agentId}:`))
  matches.sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
  return matches[0]?.key ?? null
}