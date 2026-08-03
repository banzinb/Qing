import { BRIDGE_VERSION, MCP_PROTOCOL_VERSION } from './bridge-core.mjs';

const stringSchema = (description) => ({ type: 'string', description });

export const MCP_TOOL_DEFINITIONS = [
  {
    name: 'codex_list_sessions',
    description: 'List Codex sessions from this PC, newest first.',
    inputSchema: {
      type: 'object',
      properties: {
        limit: { type: 'integer', description: 'Max sessions to return (default 50).' },
        search: stringSchema('Optional text to filter by title/path/id.'),
      },
    },
  },
  {
    name: 'codex_read_session',
    description: 'Read a Codex session full history as structured messages and markdown.',
    inputSchema: {
      type: 'object',
      properties: {
        session_id: stringSchema('Codex session id (UUID).'),
        after: { type: 'integer', description: 'Return only messages after this epoch millisecond timestamp.' },
      },
      required: ['session_id'],
    },
  },
  {
    name: 'codex_exec',
    description: 'Start a new Codex task on this PC with the given prompt.',
    inputSchema: {
      type: 'object',
      properties: {
        prompt: stringSchema('Task instructions for PC Codex.'),
        cwd: stringSchema('Optional working directory. Defaults to the bridge working directory.'),
        model: stringSchema('Optional model override.'),
        sandbox: stringSchema('Optional sandbox mode: read-only, workspace-write, or danger-full-access.'),
      },
      required: ['prompt'],
    },
  },
  {
    name: 'codex_resume',
    description: 'Continue an existing Codex session on this PC with a follow-up prompt. Refuses sessions running in a read-only or restricted sandbox.',
    inputSchema: {
      type: 'object',
      properties: {
        session_id: stringSchema('Codex session id (UUID) to resume.'),
        prompt: stringSchema('Follow-up instructions.'),
        cwd: stringSchema('Optional working directory.'),
        model: stringSchema('Optional model override.'),
      },
      required: ['session_id', 'prompt'],
    },
  },
  {
    name: 'codex_poll',
    description: 'Poll a running Codex task for live progress events (works for codex_exec, codex_resume, and claw_turn tasks).',
    inputSchema: {
      type: 'object',
      properties: {
        task_id: stringSchema('Task id returned by codex_exec/codex_resume.'),
      },
      required: ['task_id'],
    },
  },
  {
    name: 'codex_stop',
    description: 'Stop a running Codex task.',
    inputSchema: {
      type: 'object',
      properties: {
        task_id: stringSchema('Task id returned by codex_exec/codex_resume.'),
      },
      required: ['task_id'],
    },
  },
  {
    name: 'claw_turn',
    description: 'Send a message to the AutoClaw/OpenClaw gateway through the local claw-bridge and start a pollable task. Poll with codex_poll, stop with codex_stop.',
    inputSchema: {
      type: 'object',
      properties: {
        message: stringSchema('Message for the Claw agent.'),
        agent_id: stringSchema('Optional agent id (default main).'),
        session_key: stringSchema('Optional Claw session key to continue.'),
        thinking: stringSchema('Optional thinking guidance, or "off" to disable.'),
        timeout_sec: { type: 'integer', description: 'Turn timeout in seconds (default 600).' },
        claw_url: stringSchema('Optional claw-bridge base URL override (default http://127.0.0.1:8900).'),
      },
      required: ['message'],
    },
  },
  {
    name: 'claw_sessions',
    description: 'List sessions known to the AutoClaw/OpenClaw gateway through claw-bridge.',
    inputSchema: {
      type: 'object',
      properties: {
        limit: { type: 'integer', description: 'Max sessions to return (default 50).' },
      },
    },
  },
  {
    name: 'claw_agents',
    description: 'List agents registered on the AutoClaw/OpenClaw gateway through claw-bridge.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'claw_health',
    description: 'Check claw-bridge and the AutoClaw/OpenClaw gateway health.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'pc_shell',
    description: 'Run a shell command on this PC.',
    inputSchema: {
      type: 'object',
      properties: {
        command: stringSchema('Shell command to execute.'),
        cwd: stringSchema('Optional working directory.'),
        timeout_ms: { type: 'integer', description: 'Timeout in milliseconds (default 60000, max 600000).' },
      },
      required: ['command'],
    },
  },
  {
    name: 'pc_file_read',
    description: 'Read a text file from this PC.',
    inputSchema: {
      type: 'object',
      properties: {
        path: stringSchema('Absolute file path.'),
        max_bytes: { type: 'integer', description: 'Max bytes to read (default 2097152).' },
        limit_lines: { type: 'integer', description: 'Optional line limit.' },
      },
      required: ['path'],
    },
  },
  {
    name: 'pc_file_write',
    description: 'Write (or append to) a text file on this PC.',
    inputSchema: {
      type: 'object',
      properties: {
        path: stringSchema('Absolute file path.'),
        content: stringSchema('Text content to write.'),
        append: { type: 'boolean', description: 'Append instead of overwrite.' },
      },
      required: ['path', 'content'],
    },
  },
  {
    name: 'pc_file_list',
    description: 'List files and directories on this PC.',
    inputSchema: {
      type: 'object',
      properties: {
        path: stringSchema('Absolute directory path.'),
        recursive: { type: 'boolean', description: 'Recurse into subdirectories.' },
        max_depth: { type: 'integer', description: 'Max recursion depth (default 2).' },
        limit: { type: 'integer', description: 'Max entries (default 500).' },
      },
      required: ['path'],
    },
  },
  {
    name: 'pc_git_status',
    description: 'Read git status and last commit from a repository on this PC.',
    inputSchema: {
      type: 'object',
      properties: {
        cwd: stringSchema('Repository directory. Defaults to the bridge working directory.'),
      },
    },
  },
];

export async function handleMcpRequest(body, handlers) {
  if (!body || typeof body !== 'object') {
    return { id: null, error: { code: -32700, message: 'Invalid request.' } };
  }
  const { id, method, params } = body;
  const isNotification = id === undefined || id === null;
  if (!method || typeof method !== 'string') {
    return { id: isNotification ? null : id, error: { code: -32600, message: 'Invalid request.' } };
  }
  if (method.startsWith('notifications/')) {
    return { notification: true };
  }
  if (isNotification) {
    return { notification: true };
  }
  switch (method) {
    case 'initialize':
      return {
        id,
        result: {
          protocolVersion: MCP_PROTOCOL_VERSION,
          capabilities: {
            tools: { listChanged: false },
          },
          serverInfo: {
            name: 'aether-pc-bridge',
            version: BRIDGE_VERSION,
          },
        },
      };
    case 'ping':
      return { id, result: {} };
    case 'tools/list':
      return { id, result: { tools: MCP_TOOL_DEFINITIONS } };
    case 'tools/call': {
      const name = params?.name;
      const args = params?.arguments && typeof params.arguments === 'object' ? params.arguments : {};
      const handler = handlers[name];
      if (!handler) {
        return { id, error: { code: -32602, message: `Unknown tool: ${name}` } };
      }
      try {
        const result = await handler(args);
        return {
          id,
          result: {
            content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
            isError: false,
          },
        };
      } catch (error) {
        return {
          id,
          error: {
            code: -32603,
            message: error?.message || String(error),
          },
        };
      }
    }
    default:
      return { id, error: { code: -32601, message: `Method not found: ${method}` } };
  }
}
