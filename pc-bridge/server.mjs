import { createServer } from 'node:http';
import { homedir } from 'node:os';
import { join } from 'node:path';
import {
  BRIDGE_VERSION,
  DEFAULT_PORT,
  MCP_PROTOCOL_VERSION,
  codexHome,
  isUuid,
  newSessionId,
  parseArgs,
  readJsonBody,
  sendEmpty,
  sendJson,
  sendText,
  timingSafeEqualString,
} from './lib/bridge-core.mjs';
import { listSessions, readSession } from './lib/codex-sessions.mjs';
import { CodexRunner, resolveCodexPath } from './lib/codex-runner.mjs';
import { clawAgents, clawHealth, clawSessions, clawTurn } from './lib/claw-adapter.mjs';
import { gitStatusTool, listDirTool, readFileTool, runShell, writeFileTool } from './lib/pc-tools.mjs';
import { handleMcpRequest } from './lib/mcp.mjs';

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, MCP-Protocol-Version, Mcp-Session-Id, Accept',
};

function landingPage(tokenRequired) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Aether PC Bridge</title>
<style>
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#0f1115;color:#e6e9ef;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:640px;padding:40px 24px}
h1{font-size:28px;margin:0 0 8px}
p{line-height:1.6;color:#aab2c0}
code{background:#1c2129;padding:2px 7px;border-radius:6px;font-size:13px}
.status{display:inline-block;margin-top:8px;padding:5px 12px;border-radius:999px;background:#123a26;color:#46d47b;font-size:13px}
ul{color:#aab2c0;line-height:1.8}
</style>
</head>
<body><main>
<h1>Aether PC Bridge</h1>
<p class="status">online &middot; v${BRIDGE_VERSION} &middot; MCP ${MCP_PROTOCOL_VERSION}</p>
<p>PC Codex bridge for the Aether phone agent. REST API at <code>/api</code>, MCP Streamable HTTP at <code>/mcp</code>.</p>
${tokenRequired ? '<p>Token authentication is enabled on this server.</p>' : '<p>Token authentication is disabled. Use it on a private LAN or a Tailscale network.</p>'}
<ul>
<li>手机端 Aether 连接地址: <code>http://本机IP:${DEFAULT_PORT}/</code></li>
<li>MCP 服务地址: <code>http://本机IP:${DEFAULT_PORT}/mcp</code></li>
<li>健康检查: <code>/api/health</code></li>
</ul>
</main></body></html>`;
}

function jsonError(res, status, message) {
  sendJson(res, status, { ok: false, error: message });
}

export async function startBridge(options = {}) {
  const port = options.port ?? DEFAULT_PORT;
  const host = options.host || '0.0.0.0';
  const token = options.token ?? '';
  const runner = new CodexRunner();
  const startedAt = Date.now();
  const desktopSync = options.desktopSync ?? (process.env.PC_BRIDGE_DESKTOP_SYNC === '1' || process.env.PC_BRIDGE_DESKTOP_SYNC === 'true');
  const clawEnv = process.env.PC_BRIDGE_CLAW;
  const claw = options.claw ?? (clawEnv === undefined ? true : !['false', '0', 'off', 'no'].includes(String(clawEnv).toLowerCase().trim()));

  const handlers = {
    codex_list_sessions: async (args) => {
      const result = await listSessions({ limit: args.limit, search: args.search });
      return { ok: true, sessions: result.sessions, total: result.total };
    },
    codex_read_session: async (args) => {
      if (!args.session_id) throw new Error('session_id is required.');
      const result = await readSession(args.session_id, { afterMs: Number(args.after) || 0 });
      return { ok: true, ...result };
    },
    codex_exec: async (args) => {
      const task = await runner.startExec({
        prompt: args.prompt,
        cwd: args.cwd,
        model: args.model,
        sandbox: args.sandbox,
      });
      return { ok: true, task };
    },
    codex_resume: async (args) => {
      const task = desktopSync
        ? await runner.startDesktopSync({
            sessionId: args.session_id,
            prompt: args.prompt,
            cwd: args.cwd,
            model: args.model,
          })
        : await runner.startResume({
            sessionId: args.session_id,
            prompt: args.prompt,
            cwd: args.cwd,
            model: args.model,
          });
      return { ok: true, task };
    },
    codex_poll: async (args) => {
      const task = runner.getTask(args.task_id);
      if (!task) throw new Error('Task not found.');
      return { ok: true, task };
    },
    codex_stop: async (args) => {
      const task = runner.stopTask(args.task_id);
      if (!task) throw new Error('Task not found.');
      return { ok: true, task };
    },
    claw_health: async () => {
      if (!claw) throw new Error('Claw bridge is disabled on this server.');
      const result = await clawHealth();
      return { ok: true, ...result };
    },
    claw_turn: async (args) => {
      if (!claw) throw new Error('Claw bridge is disabled on this server.');
      const task = await runner.startClawTurn({
        message: args.message,
        agentId: args.agent_id,
        sessionKey: args.session_key,
        thinking: args.thinking,
        timeoutSec: args.timeout_sec,
        clawUrl: args.claw_url,
      });
      return { ok: true, task };
    },
    claw_sessions: async (args) => {
      if (!claw) throw new Error('Claw bridge is disabled on this server.');
      const result = await clawSessions({ limit: args.limit });
      return { ok: true, ...result };
    },
    claw_agents: async () => {
      if (!claw) throw new Error('Claw bridge is disabled on this server.');
      const result = await clawAgents();
      return { ok: true, ...result };
    },
    pc_shell: async (args) => {
      const result = await runShell({
        command: args.command,
        cwd: args.cwd,
        timeoutMs: args.timeout_ms,
      });
      return { ok: true, ...result };
    },
    pc_file_read: async (args) => {
      const result = await readFileTool({
        path: args.path,
        maxBytes: args.max_bytes,
        limitLines: args.limit_lines,
      });
      return { ok: true, ...result };
    },
    pc_file_write: async (args) => {
      const result = await writeFileTool({
        path: args.path,
        content: args.content,
        append: args.append,
      });
      return { ok: true, ...result };
    },
    pc_file_list: async (args) => {
      const result = await listDirTool({
        path: args.path,
        recursive: args.recursive,
        maxDepth: args.max_depth,
        limit: args.limit,
      });
      return { ok: true, ...result };
    },
    pc_git_status: async (args) => {
      const result = await gitStatusTool({ cwd: args.cwd });
      return { ok: true, ...result };
    },
  };

  async function handleApi(req, res, url) {
    const pathname = url.pathname;
    if (req.method === 'OPTIONS') {
      sendEmpty(res, 204, CORS_HEADERS);
      return true;
    }
    if (pathname === '/api/health') {
      const codex = await resolveCodexPath().catch(() => 'codex');
      sendJson(res, 200, {
        ok: true,
        name: 'aether-pc-bridge',
        version: BRIDGE_VERSION,
        protocolVersion: MCP_PROTOCOL_VERSION,
        requiresToken: Boolean(token),
        tokenOptional: true,
        resumeGuard: true,
        desktopSync,
        claw,
        codexPath: codex,
        sessionsDir: join(codexHome(), 'sessions'),
        node: process.version,
        uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
      });
      return true;
    }
    if (!authorized(req)) {
      jsonError(res, 401, 'Unauthorized: missing or invalid Bearer token.');
      return true;
    }
    if (req.method === 'GET' && pathname === '/api/sessions') {
      const result = await listSessions({
        limit: Number(url.searchParams.get('limit')) || 50,
        search: url.searchParams.get('search') || '',
      });
      sendJson(res, 200, { ok: true, ...result });
      return true;
    }
    const sessionMatch = /^\/api\/sessions\/([0-9a-f-]+)$/i.exec(pathname);
    if (req.method === 'GET' && sessionMatch) {
      if (!isUuid(sessionMatch[1])) {
        jsonError(res, 400, 'Invalid session id.');
        return true;
      }
      try {
        const result = await readSession(sessionMatch[1], {
          afterMs: Number(url.searchParams.get('after')) || 0,
        });
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 404, error.message);
      }
      return true;
    }
    if (req.method === 'POST' && pathname === '/api/exec') {
      const body = await readJsonBody(req);
      try {
        const task = await runner.startExec(body);
        sendJson(res, 200, { ok: true, task });
      } catch (error) {
        jsonError(res, 400, error.message);
      }
      return true;
    }
    if (req.method === 'POST' && pathname === '/api/resume') {
      const body = await readJsonBody(req);
      try {
        const task = desktopSync
          ? await runner.startDesktopSync({
              sessionId: body.session_id,
              prompt: body.prompt,
              cwd: body.cwd,
              model: body.model,
            })
          : await runner.startResume({
              sessionId: body.session_id,
              prompt: body.prompt,
              cwd: body.cwd,
              model: body.model,
            });
        sendJson(res, 200, { ok: true, task });
      } catch (error) {
        jsonError(res, 400, error.message);
      }
      return true;
    }
    if (pathname === '/api/claw/health') {
      if (!claw) {
        jsonError(res, 503, 'Claw bridge is disabled.');
        return true;
      }
      try {
        const result = await clawHealth();
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 502, error.message);
      }
      return true;
    }
    if (req.method === 'POST' && pathname === '/api/claw/turn') {
      if (!claw) {
        jsonError(res, 503, 'Claw bridge is disabled.');
        return true;
      }
      const body = await readJsonBody(req);
      try {
        const task = await runner.startClawTurn({
          message: body.message,
          agentId: body.agent_id ?? body.agentId,
          sessionKey: body.session_key ?? body.sessionKey,
          thinking: body.thinking,
          timeoutSec: body.timeout_sec ?? body.timeoutSec,
          clawUrl: body.claw_url,
        });
        sendJson(res, 200, { ok: true, task });
      } catch (error) {
        jsonError(res, 400, error.message);
      }
      return true;
    }
    if (req.method === 'GET' && pathname === '/api/claw/sessions') {
      if (!claw) {
        jsonError(res, 503, 'Claw bridge is disabled.');
        return true;
      }
      try {
        const result = await clawSessions({ limit: Number(url.searchParams.get('limit')) || undefined });
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 502, error.message);
      }
      return true;
    }
    if (req.method === 'GET' && pathname === '/api/claw/agents') {
      if (!claw) {
        jsonError(res, 503, 'Claw bridge is disabled.');
        return true;
      }
      try {
        const result = await clawAgents();
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 502, error.message);
      }
      return true;
    }
    const taskMatch = /^\/api\/tasks\/([0-9a-f-]+)$/i.exec(pathname);
    const stopMatch = /^\/api\/tasks\/([0-9a-f-]+)\/stop$/i.exec(pathname);
    if (req.method === 'GET' && taskMatch) {
      const task = runner.getTask(taskMatch[1]);
      if (!task) {
        jsonError(res, 404, 'Task not found.');
      } else {
        sendJson(res, 200, { ok: true, task });
      }
      return true;
    }
    if (req.method === 'POST' && stopMatch) {
      const task = runner.stopTask(stopMatch[1]);
      if (!task) {
        jsonError(res, 404, 'Task not found.');
      } else {
        sendJson(res, 200, { ok: true, task });
      }
      return true;
    }
    if (req.method === 'POST' && pathname === '/api/shell') {
      const body = await readJsonBody(req);
      try {
        const result = await runShell(body);
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 400, error.message);
      }
      return true;
    }
    if (req.method === 'GET' && pathname === '/api/file/read') {
      try {
        const result = await readFileTool({
          path: url.searchParams.get('path'),
          maxBytes: Number(url.searchParams.get('maxBytes')) || undefined,
          limitLines: Number(url.searchParams.get('limitLines')) || undefined,
        });
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 400, error.message);
      }
      return true;
    }
    if (req.method === 'POST' && pathname === '/api/file/write') {
      const body = await readJsonBody(req);
      try {
        const result = await writeFileTool(body);
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 400, error.message);
      }
      return true;
    }
    if (req.method === 'GET' && pathname === '/api/file/list') {
      try {
        const result = await listDirTool({
          path: url.searchParams.get('path'),
          recursive: url.searchParams.get('recursive') === 'true' || url.searchParams.get('recursive') === '1',
          maxDepth: Number(url.searchParams.get('maxDepth')) || undefined,
          limit: Number(url.searchParams.get('limit')) || undefined,
        });
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 400, error.message);
      }
      return true;
    }
    if (req.method === 'GET' && pathname === '/api/git/status') {
      try {
        const result = await gitStatusTool({ cwd: url.searchParams.get('cwd') || undefined });
        sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        jsonError(res, 400, error.message);
      }
      return true;
    }
    return false;
  }

  async function handleMcp(req, res, url) {
    if (req.method === 'OPTIONS') {
      sendEmpty(res, 204, CORS_HEADERS);
      return;
    }
    if (req.method === 'DELETE') {
      sendEmpty(res, 204, CORS_HEADERS);
      return;
    }
    if (req.method === 'GET') {
      sendText(res, 405, 'Use POST for MCP JSON-RPC.', { Allow: 'POST, DELETE, OPTIONS', ...CORS_HEADERS });
      return;
    }
    if (req.method !== 'POST') {
      sendEmpty(res, 405, { Allow: 'POST, DELETE, OPTIONS', ...CORS_HEADERS });
      return;
    }
    if (!authorized(req)) {
      jsonError(res, 401, 'Unauthorized: missing or invalid Bearer token.');
      return;
    }
    const body = await readJsonBody(req);
    const response = await handleMcpRequest(body, handlers);
    if (response.notification) {
      sendEmpty(res, 202, CORS_HEADERS);
      return;
    }
    sendJson(res, 200, response, {
      'Mcp-Session-Id': newSessionId(),
      ...CORS_HEADERS,
    });
  }

  function authorized(req) {
    if (!token) return true;
    const header = req.headers.authorization || '';
    return timingSafeEqualString(header, `Bearer ${token}`);
  }

  const server = createServer(async (req, res) => {
    const started = Date.now();
    let url;
    try {
      url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    } catch {
      jsonError(res, 400, 'Invalid URL.');
      return;
    }
    try {
      let handled = false;
      if (req.method === 'OPTIONS') {
        sendEmpty(res, 204, CORS_HEADERS);
        handled = true;
      } else if (url.pathname === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(landingPage(Boolean(token)));
        handled = true;
      } else if (url.pathname === '/mcp') {
        await handleMcp(req, res, url);
        handled = true;
      } else if (url.pathname.startsWith('/api/')) {
        handled = await handleApi(req, res, url);
      }
      if (!handled) {
        jsonError(res, 404, 'Not found.');
      }
    } catch (error) {
      if (!res.headersSent) {
        jsonError(res, 500, error.message || 'Internal error.');
      } else {
        res.destroy();
      }
    }
    const elapsed = Date.now() - started;
    console.log(`${new Date().toISOString()} ${req.method} ${url.pathname} ${res.statusCode} ${elapsed}ms`);
  });

  const listenHost = host || '0.0.0.0';
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(port, listenHost, resolvePromise);
  });
  const address = server.address();
  const actualPort = typeof address === 'object' && address ? address.port : port;
  const url = `http://127.0.0.1:${actualPort}`;

  async function close() {
    runner.stopAll();
    await new Promise((resolvePromise) => server.close(resolvePromise));
  }

  return { server, url, port: actualPort, token, runner, close, handlers };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const token = args.token || process.env.PC_BRIDGE_TOKEN || process.env.AUTH_TOKEN || '';
  const port = Number(args.port) || DEFAULT_PORT;
  const host = args.host || '0.0.0.0';
  const desktopSyncArg = args['desktop-sync'] ?? args.desktopSync;
  const desktopSyncFlag = desktopSyncArg === undefined
    ? undefined
    : desktopSyncArg === true || String(desktopSyncArg).toLowerCase() !== 'false';
  const desktopSync = desktopSyncFlag ?? (process.env.PC_BRIDGE_DESKTOP_SYNC === '1' || process.env.PC_BRIDGE_DESKTOP_SYNC === 'true');
  const clawArg = args.claw;
  const clawFlag = clawArg === undefined
    ? undefined
    : clawArg === true || String(clawArg).toLowerCase() !== 'false';
  const clawEnv = process.env.PC_BRIDGE_CLAW;
  const claw = clawFlag ?? (clawEnv === undefined ? true : !['false', '0', 'off', 'no'].includes(String(clawEnv).toLowerCase().trim()));
  const bridge = await startBridge({ port, host, token, desktopSync, claw });
  console.log('');
  console.log('  Aether PC Bridge v' + BRIDGE_VERSION);
  console.log('  Listening: http://' + host + ':' + bridge.port);
  console.log('  MCP endpoint: http://' + host + ':' + bridge.port + '/mcp');
  console.log('  Desktop sync: ' + (desktopSync ? 'on' : 'off'));
  console.log('  Claw bridge: ' + (claw ? 'on' : 'off'));
  console.log(token
    ? '  Token: ' + token + ' (send as Authorization: Bearer <token>)'
    : '  Token: disabled (use on private LAN or Tailscale)');
  console.log('  Codex sessions: ' + join(codexHome(), 'sessions'));
  console.log('');
  const shutdown = async () => {
    await bridge.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

const isMain = process.argv[1] && (
  import.meta.url === `file://${process.argv[1].replace(/\\/g, '/')}`
  || import.meta.url.endsWith(process.argv[1].split(/[\\/]/).pop())
);
if (isMain) {
  main().catch((error) => {
    console.error('Failed to start bridge:', error);
    process.exit(1);
  });
}
