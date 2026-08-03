import { readFile, readdir, stat } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { codexHome, safeJsonParse, truncate } from './bridge-core.mjs';

const SESSION_FILE_RE = /rollout-.+?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.jsonl$/i;
const NOISE_PREFIXES = [
  '<environment_context>',
  '<permissions instructions>',
  '<app-context>',
  '<skills_instructions>',
  '<plugins_instructions>',
  '<collaboration_mode>',
  '<developer_instructions>',
  '<goal>',
];

const filesCache = new Map();
const metaCache = new Map();

function sessionsRoot() {
  return join(codexHome(), 'sessions');
}

async function walkDir(dir, depth, out) {
  if (depth > 6) return;
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      await walkDir(full, depth + 1, out);
    } else if (entry.isFile()) {
      const match = SESSION_FILE_RE.exec(entry.name);
      if (match) out.push({ id: match[1], path: full });
    }
  }
}

export async function findSessionFiles({ force = false } = {}) {
  const home = codexHome();
  const now = Date.now();
  const entry = filesCache.get(home);
  if (!force && entry && now - entry.at < 10_000) return entry.files;
  const files = [];
  await walkDir(sessionsRoot(), 0, files);
  filesCache.set(home, { files, at: now });
  return files;
}

export async function loadSessionIndex() {
  const indexPath = join(codexHome(), 'session_index.jsonl');
  const map = new Map();
  try {
    const text = await readFile(indexPath, 'utf8');
    for (const line of text.split(/\r?\n/)) {
      if (!line.trim()) continue;
      const obj = safeJsonParse(line);
      if (obj && typeof obj.id === 'string') {
        map.set(obj.id, {
          title: obj.thread_name || '',
          updatedAt: obj.updated_at || '',
          updatedAtMs: Date.parse(obj.updated_at) || 0,
        });
      }
    }
  } catch {
    // Index is optional; session files are authoritative.
  }
  return map;
}

function isNoiseBlock(text) {
  const trimmed = String(text ?? '').trimStart();
  return NOISE_PREFIXES.some((prefix) => trimmed.startsWith(prefix));
}

function extractContentText(content) {
  if (!Array.isArray(content)) return '';
  const parts = [];
  for (const item of content) {
    if (!item || typeof item !== 'object') continue;
    const type = item.type || '';
    if (!['input_text', 'output_text', 'text', 'refusal'].includes(type)) continue;
    const text = item.text;
    if (typeof text !== 'string' || !text.trim()) continue;
    if (isNoiseBlock(text)) continue;
    parts.push(text.trim());
  }
  return parts.join('\n');
}

function analyzeSessionText(text, id) {
  let meta = {};
  let model = '';
  let firstUserText = '';
  let lastUserText = '';
  let lastAssistantText = '';
  let messageCount = 0;
  let lastMessageAtMs = 0;
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line[0] !== '{') continue;
    const obj = safeJsonParse(line);
    if (!obj || !obj.payload) continue;
    const payload = obj.payload;
    if (obj.type === 'session_meta') {
      meta = {
        id: payload.id || payload.session_id || id,
        cwd: payload.cwd || '',
        modelProvider: payload.model_provider || '',
        originator: payload.originator || '',
        createdAt: payload.timestamp || obj.timestamp || '',
      };
    } else if (obj.type === 'turn_context' && payload.model) {
      model = payload.model;
    } else if (obj.type === 'event_msg' && payload.type) {
      const ts = Date.parse(obj.timestamp) || 0;
      if (payload.type === 'user_message' && typeof payload.message === 'string') {
        const textValue = payload.message.trim();
        messageCount += 1;
        if (ts > lastMessageAtMs) lastMessageAtMs = ts;
        if (!firstUserText && textValue) firstUserText = textValue;
        if (textValue) lastUserText = textValue;
      } else if (payload.type === 'agent_message' && typeof payload.message === 'string') {
        messageCount += 1;
        if (ts > lastMessageAtMs) lastMessageAtMs = ts;
        if (payload.message.trim()) lastAssistantText = payload.message.trim();
      }
    }
  }
  return {
    meta,
    model,
    firstUserText: truncate(firstUserText, 200),
    lastUserText: truncate(lastUserText, 500),
    lastAssistantText: truncate(lastAssistantText, 500),
    messageCount,
    lastMessageAtMs,
  };
}

async function scanSessionFile(path, id) {
  const info = await stat(path);
  const cacheKey = `${codexHome()}|${id}`;
  const cached = metaCache.get(cacheKey);
  if (cached && cached.path === path && cached.size === info.size && cached.mtimeMs === info.mtimeMs) {
    return cached.scan;
  }
  const text = await readFile(path, 'utf8');
  const scan = analyzeSessionText(text, id);
  metaCache.set(cacheKey, { path, size: info.size, mtimeMs: info.mtimeMs, scan });
  return scan;
}

export function normalizeCwd(value) {
  const raw = String(value ?? '').trim();
  if (!raw) return '';
  let path = raw.replace(/\\/g, '/');
  while (path.endsWith('/')) path = path.slice(0, -1);
  if (process.platform === 'win32' || /^[a-zA-Z]:\//.test(path)) path = path.toLowerCase();
  return path;
}

export function scanSessionSafety(text) {
  let cwd = '';
  let sandboxPolicy = '';
  let permissionProfile = '';
  let fileSystemAccess = '';
  for (const rawLine of String(text ?? '').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line[0] !== '{') continue;
    const obj = safeJsonParse(line);
    if (!obj || !obj.payload) continue;
    const payload = obj.payload;
    if (obj.type === 'session_meta') {
      if (!cwd && payload.cwd) cwd = String(payload.cwd);
      continue;
    }
    if (obj.type === 'turn_context') {
      if (payload.cwd) cwd = String(payload.cwd);
      if (payload.sandbox_policy) {
        sandboxPolicy = typeof payload.sandbox_policy === 'object'
          ? String(payload.sandbox_policy.type || payload.sandbox_policy.id || '')
          : String(payload.sandbox_policy);
      }
      if (payload.permission_profile) {
        permissionProfile = typeof payload.permission_profile === 'object'
          ? String(payload.permission_profile.type || '')
          : String(payload.permission_profile);
        if (typeof payload.permission_profile === 'object' && payload.permission_profile.file_system) {
          fileSystemAccess = String(payload.permission_profile.file_system.type || '');
        }
      }
      continue;
    }
    if (obj.type === 'event_msg' && payload.type === 'thread_settings_applied') {
      const settings = payload.thread_settings;
      if (settings && typeof settings === 'object') {
        if (settings.cwd) cwd = String(settings.cwd);
        const active = settings.active_permission_profile;
        if (active?.id) permissionProfile = String(active.id).replace(/^:/, '');
        if (settings.permission_profile) {
          const profile = settings.permission_profile;
          permissionProfile = typeof profile === 'object' ? String(profile.type || '') : String(profile);
          if (typeof profile === 'object' && profile.file_system) {
            fileSystemAccess = String(profile.file_system.type || '');
          }
        }
      }
    }
  }
  if (!sandboxPolicy) {
    const permissionMatch = /`sandbox_mode` is `([a-z-]+)`/i.exec(String(text ?? ''));
    if (permissionMatch) sandboxPolicy = permissionMatch[1].toLowerCase();
  }
  const sandboxMode = sandboxPolicy.toLowerCase();
  const blocked = sandboxMode === 'read-only'
    || sandboxMode === 'readonly'
    || fileSystemAccess === 'restricted'
    || /read-only|readonly/i.test(fileSystemAccess);
  return {
    cwd,
    sandboxPolicy,
    permissionProfile,
    fileSystemAccess,
    blocked,
    reason: blocked
      ? `session sandbox is '${sandboxPolicy || 'read-only'}' (${fileSystemAccess ? `filesystem: ${fileSystemAccess}` : 'restricted permissions'})`
      : '',
  };
}

export async function readSessionSafety(id) {
  if (!/^[0-9a-f-]+$/i.test(String(id))) {
    throw new Error('Invalid session id.');
  }
  const files = await findSessionFiles({ force: true });
  const file = files.find((item) => item.id === id);
  if (!file) throw new Error(`Session '${id}' was not found.`);
  const text = await readFile(file.path, 'utf8');
  return scanSessionSafety(text);
}

export async function listSessions({ limit = 50, search = '' } = {}) {
  const files = await findSessionFiles();
  const index = await loadSessionIndex();
  const statted = [];
  for (const file of files) {
    let mtimeMs = 0;
    try {
      mtimeMs = (await stat(file.path)).mtimeMs;
    } catch {
      continue;
    }
    const indexed = index.get(file.id);
    statted.push({ ...file, mtimeMs, indexed });
  }
  const sortKey = (item) => (item.indexed?.updatedAtMs || item.mtimeMs || 0);
  statted.sort((a, b) => sortKey(b) - sortKey(a));
  const query = String(search ?? '').trim().toLowerCase();
  let filtered = statted;
  if (query) {
    filtered = statted.filter((item) => {
      const haystack = [item.id, item.path, item.indexed?.title || '', item.mtimeMs].join(' ').toLowerCase();
      return haystack.includes(query);
    });
  }
  const top = filtered.slice(0, Math.min(Number(limit) || 50, 200));
  const sessions = [];
  for (const item of top) {
    const scan = await scanSessionFile(item.path, item.id);
    const preview = scan.lastAssistantText || scan.lastUserText || scan.firstUserText || '';
    const updatedAt = item.indexed?.updatedAt || (
      scan.lastMessageAtMs ? new Date(scan.lastMessageAtMs).toISOString() : new Date(item.mtimeMs).toISOString()
    );
    sessions.push({
      id: item.id,
      title: item.indexed?.title || scan.firstUserText || basename(item.path).replace(/^rollout-/, '').replace(/\.jsonl$/, ''),
      cwd: scan.meta.cwd || '',
      model: scan.model || '',
      modelProvider: scan.meta.modelProvider || '',
      originator: scan.meta.originator || '',
      createdAt: scan.meta.createdAt || new Date(item.mtimeMs).toISOString(),
      updatedAt,
      messageCount: scan.messageCount,
      preview: truncate(preview, 160),
    });
  }
  return { sessions, total: filtered.length, returned: sessions.length };
}

function collectEntries(text, maxToolOutput) {
  const entries = [];
  const toolByCallId = new Map();
  let seq = 0;
  let sawEventMessages = { user: false, assistant: false, reasoning: false };
  const rawLines = text.split(/\r?\n/);
  const parsed = [];
  for (const rawLine of rawLines) {
    const line = rawLine.trim();
    if (!line || line[0] !== '{') continue;
    const obj = safeJsonParse(line);
    if (!obj || !obj.payload) continue;
    parsed.push(obj);
  }
  for (const obj of parsed) {
    const payload = obj.payload;
    if (obj.type !== 'event_msg' || !payload.type) continue;
    if (payload.type === 'user_message') sawEventMessages.user = true;
    if (payload.type === 'agent_message') sawEventMessages.assistant = true;
    if (payload.type === 'agent_reasoning') sawEventMessages.reasoning = true;
  }
  const seen = new Set();
  for (const obj of parsed) {
    const payload = obj.payload;
    const ts = Date.parse(obj.timestamp) || 0;
    const add = (entry) => {
      entry.seq = seq++;
      const dedupeKey = `${entry.kind}|${entry.ts}|${String(entry.text || entry.toolName || '').slice(0, 80)}`;
      if (seen.has(dedupeKey)) return;
      seen.add(dedupeKey);
      entries.push(entry);
    };
    if (obj.type === 'event_msg') {
      if (payload.type === 'user_message' && typeof payload.message === 'string' && payload.message.trim()) {
        add({ kind: 'user', text: payload.message.trim(), ts });
      } else if (payload.type === 'agent_message' && typeof payload.message === 'string' && payload.message.trim()) {
        add({ kind: 'assistant', text: payload.message.trim(), ts });
      } else if (payload.type === 'agent_reasoning' && typeof payload.text === 'string' && payload.text.trim()) {
        add({ kind: 'reasoning', text: truncate(payload.text.trim(), 4000), ts });
      }
      continue;
    }
    if (obj.type !== 'response_item' || !payload.type) continue;
    const ptype = payload.type;
    if (ptype === 'message') {
      const role = payload.role || '';
      const text = extractContentText(payload.content).trim();
      if (!text) continue;
      if (role === 'user' && !sawEventMessages.user) {
        add({ kind: 'user', text, ts });
      } else if (role === 'assistant' && !sawEventMessages.assistant) {
        add({ kind: 'assistant', text, ts });
      }
    } else if (ptype === 'reasoning' && !sawEventMessages.reasoning) {
      const summary = Array.isArray(payload.summary)
        ? payload.summary.map((item) => item?.text || '').filter(Boolean).join('\n')
        : '';
      if (summary.trim()) add({ kind: 'reasoning', text: truncate(summary.trim(), 4000), ts });
    } else if (ptype === 'function_call') {
      const entry = {
        kind: 'tool',
        toolName: payload.name || 'tool',
        args: truncate(String(payload.arguments ?? ''), 6000),
        callId: payload.call_id || '',
        output: '',
        ts,
      };
      add(entry);
      if (entry.callId) toolByCallId.set(entry.callId, entry);
    } else if (ptype === 'function_call_output') {
      const target = toolByCallId.get(payload.call_id);
      const output = truncate(String(payload.output ?? ''), maxToolOutput);
      if (target) {
        target.output = output;
      } else {
        add({ kind: 'tool', toolName: 'tool', args: '', callId: payload.call_id || '', output, ts });
      }
    }
  }
  entries.sort((a, b) => a.ts - b.ts || a.seq - b.seq);
  return entries;
}

function formatTime(ts) {
  if (!ts) return '';
  return new Date(ts).toISOString().replace('T', ' ').replace(/\.\d{3}Z$/, 'Z');
}

function buildMarkdown(entries) {
  const lines = [];
  for (const entry of entries) {
    const stamp = formatTime(entry.ts);
    if (entry.kind === 'user') {
      lines.push(`### ${stamp} User\n\n${truncate(entry.text, 20000)}`);
    } else if (entry.kind === 'assistant') {
      lines.push(`### ${stamp} Codex\n\n${truncate(entry.text, 20000)}`);
    } else if (entry.kind === 'reasoning') {
      lines.push(`> ${stamp} reasoning: ${entry.text}`);
    } else if (entry.kind === 'tool') {
      lines.push(`### ${stamp} Tool ${entry.toolName}\n\n\`\`\`json\n${entry.args || '{}'}\n\`\`\``);
      if (entry.output) {
        lines.push(`\`\`\`text\n${truncate(entry.output, 8000)}\n\`\`\``);
      }
    }
    lines.push('');
  }
  return lines.join('\n').trim();
}

export async function readSession(id, { afterMs = 0, maxToolOutput = 16000 } = {}) {
  if (!/^[0-9a-f-]+$/i.test(String(id))) {
    throw new Error('Invalid session id.');
  }
  const files = await findSessionFiles();
  const file = files.find((item) => item.id === id);
  if (!file) throw new Error(`Session '${id}' was not found.`);
  const text = await readFile(file.path, 'utf8');
  const index = await loadSessionIndex();
  const entries = collectEntries(text, maxToolOutput);
  const filtered = afterMs ? entries.filter((entry) => entry.ts > afterMs) : entries;
  const scan = analyzeSessionText(text, id);
  const indexed = index.get(id);
  let updatedAtMs = 0;
  for (const entry of filtered) {
    if (entry.ts > updatedAtMs) updatedAtMs = entry.ts;
  }
  if (!updatedAtMs) {
    try {
      updatedAtMs = (await stat(file.path)).mtimeMs;
    } catch {
      updatedAtMs = 0;
    }
  }
  const title = indexed?.title || scan.firstUserText || id;
  const messages = filtered.map((entry, indexNumber) => ({
    id: `${entry.kind}-${indexNumber}`,
    kind: entry.kind,
    role: entry.kind === 'user' ? 'user' : entry.kind === 'assistant' ? 'assistant' : 'tool',
    text: entry.text || '',
    toolName: entry.toolName || '',
    args: entry.args || '',
    output: entry.output || '',
    timestamp: entry.ts || 0,
  }));
  const markdown = [
    `# Codex Session ${title}`,
    scan.meta.cwd ? `- cwd: ${scan.meta.cwd}` : '',
    scan.model ? `- model: ${scan.model}` : '',
    updatedAtMs ? `- updated: ${new Date(updatedAtMs).toISOString()}` : '',
    '',
    buildMarkdown(filtered),
  ].filter(Boolean).join('\n');
  return {
    id,
    title,
    cwd: scan.meta.cwd || '',
    model: scan.model || '',
    modelProvider: scan.meta.modelProvider || '',
    createdAt: scan.meta.createdAt || '',
    updatedAt: new Date(updatedAtMs).toISOString(),
    updatedAtMs,
    messageCount: messages.filter((message) => message.kind === 'user' || message.kind === 'assistant').length,
    summary: truncate(scan.lastAssistantText || scan.lastUserText || scan.firstUserText, 160),
    messages,
    markdown,
  };
}
