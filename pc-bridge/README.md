# Aether PC Bridge

PC 端桥接服务：让手机上的 Aether 直接读写这台电脑上的 Codex 会话、下发新任务、续聊旧会话、实时看进度，并通过 MCP 把 PC 能力接入 Aether Agent。

零依赖，只需要 Node.js 20+。

## 启动

```bash
cd pc-bridge
node server.mjs --port 8899
```

可选参数：

| 参数 | 说明 |
| --- | --- |
| `--port <port>` | 监听端口，默认 `8899` |
| `--host <host>` | 监听地址，默认 `0.0.0.0` |
| `--token <token>` | 可选 Token；不填则不做鉴权（建议只在家庭内网或 Tailscale 网络使用） |
| `PC_BRIDGE_TOKEN` | 环境变量方式设置 Token（与 `--token` 等价） |

## 手机端连接

1. 手机和电脑在同一 WiFi（或双方都装 Tailscale，人在外面也能连）时，用电脑的局域网 IP。
2. Aether 的 PC Codex 页面填写：
   - 地址：`http://<电脑IP>:8899`
   - Token：留空（未启用）或填写启动时设置的 Token
3. 页面会自动列出 PC 上 Codex 会话（最新在前），点开看完整历史，输入框可发新任务或续聊选中会话。

Tailscale 示例：电脑 IP 为 `100.64.0.1` 时，地址填 `http://100.64.0.1:8899`。

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查（无需 Token） |
| GET | `/api/sessions?limit=50&search=` | 会话列表，最新在前 |
| GET | `/api/sessions/:id?after=<ms>` | 会话完整历史；`after` 做增量轮询 |
| POST | `/api/exec` | 新任务 `{prompt, cwd?, model?, sandbox?}` |
| POST | `/api/resume` | 续聊 `{session_id, prompt, cwd?}` |
| GET | `/api/tasks/:id` | 任务进度与事件流 |
| POST | `/api/tasks/:id/stop` | 停止任务 |
| POST | `/api/shell` | 执行 Shell `{command, cwd?, timeout_ms?}` |
| GET | `/api/file/read?path=` | 读文件 |
| POST | `/api/file/write` | 写文件 `{path, content, append?}` |
| GET | `/api/file/list?path=&recursive=` | 列目录 |
| GET | `/api/git/status?cwd=` | Git 状态与最近提交 |

## MCP

Aether 设置 > MCP Servers 添加 HTTP 服务器：

- URL：`http://<电脑IP>:8899/mcp`
- 请求头：`Authorization: Bearer <token>`（启用 Token 时）

工具：`codex_list_sessions`、`codex_read_session`、`codex_exec`、`codex_resume`、`codex_poll`、`codex_stop`、`pc_shell`、`pc_file_read`、`pc_file_write`、`pc_file_list`、`pc_git_status`。

## 自测

```bash
cd pc-bridge
npm test
```

自测会真实启动一次小型 `codex exec`（会消耗少量模型额度），验证端到端可用。

## 安全与桌面同步

- `/api/resume` 会先检查目标会话的沙箱状态：如果会话是 `read-only` 或受限权限，桥接会**拒绝续跑**并返回明确错误，避免继承受限沙箱后空转写文件。
- `/api/exec` 默认使用 `danger-full-access` 沙箱（与桌面端一致），可用 `PC_BRIDGE_DEFAULT_SANDBOX` 环境变量覆盖。
- 续跑的 `cwd` 必须与会话记录的 `cwd` 一致，否则返回错误；建议用 `/api/exec` 在新目录开新任务。
- 手机端通过 CLI 续跑后，会话文件由 Codex CLI 更新，桌面端会照常显示新消息；桥接不再向桌面 App 的 IPC 注入 turn。
- 桌面 App 的命名管道 `\\.\pipe\codex-ipc` 正确方法是 `turn/start`，参数必须包含 `threadId` 和 `input: [{ "type": "text", "text": "..." }]`。不要使用 `thread-follower-start-turn`，该方法是错误参数，会导致桌面会话 UI 报错。
