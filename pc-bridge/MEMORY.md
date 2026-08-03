# pc-bridge 项目记忆（续作手册）

> 本文件是唯一恢复入口。上下文被压缩或丢失后，先完整读本文件再继续，不要重新排查。
> 记录时间：2026-08-03（第六次更新；手机端实测通过，桌面实时同步可用）

## 当前目标
让手机 Aether 经 pc-bridge 发给 Codex 桌面会话的消息实时显示在桌面端，不报错、不破坏会话。
用户已确认方案：备份 -> 改 pc-bridge -> 加开关 -> 重启 pc-bridge -> 实测。

## 一句话现状
- 桌面实时同步已完整打通：手机端发的消息实时显示在当前打开的桌面会话，功能可用。
- pc-bridge 已用 `--desktop-sync` 重启（PID 52768，端口 8899）；冒烟测试与回退实测均通过。
- 代码改动已准备提交 git，后续保持开关默认关闭即可。

## 已确认的关键事实（别再重复排查）
1. 桌面 App Server 打开会话后不会实时重新加载外部写入的会话文件。手机消息由 `codex exec resume` 写入 JSONL 后，桌面 UI 不会自动刷新（即使消息已落盘）。
2. CLI 写的 user_message 没有 client_id，桌面自己写的有 client_id；但 client_id 不是显示的关键，关键是消息必须经过桌面 App Server 自己的 turn 流程。
3. 桌面主进程在 `\\.\pipe\codex-ipc` 上注册的正确方法名是 `thread-follower-start-turn`（version 1），不是 `turn/start`。
4. `turn/start`、`thread/read`、`thread/list` 从外部客户端直接发都会返回 `no-client-found`，因为桌面路由只把请求转发给注册了对应 handler 的客户端。
5. `thread-follower-start-turn` 的载荷：
   `{ conversationId: <uuid>, turnStartParams: { input: [{ type: "text", text: "..." }], clientUserMessageId: <uuid> } }`
6. 该方法的 canHandle 检查要求会话当前处于 desktop 端 owner 状态（即该会话正打开在桌面 UI）。会话没打开时返回 `no-client-found`，安全，不会破坏任何东西。
7. 之前的雷区：发错方法/参数（或参数缺失）会导致会话一打开就报错（019fb212 那次）。正确参数 + 会话打开时才可用，不会损坏会话。
8. 桌面 App Server 是 ChatGPT.exe 主进程拉起的 stdio 子进程（`codex.exe app-server`），无法从外部直接注入；唯一安全通道是 `\\.\pipe\codex-ipc` 的 follower 协议。

## 实现方案（用户已确认）
- pc-bridge 的 `/api/resume` 优先走桌面 IPC：发送 `thread-follower-start-turn`，让桌面 App Server 自己跑这轮，消息和回复都会实时显示。
- 开关：`PC_BRIDGE_DESKTOP_SYNC=1` 或 `--desktop-sync`，默认关闭；health 返回 `desktopSync`。
- 回退：IPC 返回 `no-client-found`（会话没打开）或管道连不上时，自动回退到原来的 `codex exec resume` CLI 路径，手机功能不受影响。
- 任务模型：IPC 成功后返回 running 任务，pc-bridge 轮询会话文件，检测到新的 assistant 回复后标记 completed，手机端照常轮询。
- 其他 IPC 错误（参数/权限等）直接失败并返回明确错误，不回退，避免双写。

## 已完成改动（未提交）
1. `pc-bridge/lib/codex-ipc.mjs`：
   - 新增 `FOLLOWER_START_TURN_METHOD = 'thread-follower-start-turn'`、`FOLLOWER_START_TURN_VERSION = 1`
   - 新增 `buildFollowerStartTurnRequest(threadId, text, { clientUserMessageId })`：校验 threadId 为 `[0-9a-f-]`、text 非空；`clientUserMessageId` 缺省用 `randomUUID()`
   - 新增 `isDesktopSyncUnavailableError(error)`：匹配 `no-client-found`、`client-not-found`、`ECONNREFUSED`、`connect timed out`、`not-connected`、`connection closed`
   - 新增 `sendFollowerStartTurn(threadId, text, { timeoutMs = 20000 })`：connect 后用 version 1 发送，`resultType === 'error'` 时 throw，finally close
   - `CodexIpcClient` 对 `client-discovery-request` 固定回复 `canHandle: false`（保持现状，勿改）
2. `pc-bridge/lib/codex-runner.mjs`：
   - 新增 `startDesktopSync({ sessionId, prompt, cwd, model })`：先 `#assertSafeResume`，取会话基线时间，`sendFollowerStartTurn` 成功则建 `kind: 'desktop-sync'` running 任务并轮询；`isDesktopSyncUnavailableError` 时回退 `startResume`；其他错误直接 throw
   - 新增 `#pollDesktopSync(task)`：每 1.2s 用 `readSession(sessionId, { afterMs: baselineMs })` 找新 assistant 消息，找到则 completed + lastMessage；10 分钟超时失败；支持停止
   - `stopTask` 对无 child 的轮询任务清 `pollTimer` 并置 stopped
3. `pc-bridge/server.mjs`：
   - `startBridge` 读取 `options.desktopSync ?? env PC_BRIDGE_DESKTOP_SYNC`
   - `/api/health` 返回 `desktopSync`
   - MCP `codex_resume` 与 REST `/api/resume`：开关开启时走 `startDesktopSync`，否则 `startResume`
   - `main()` 支持 `--desktop-sync` / `--desktop-sync=false`，启动日志显示 `Desktop sync: on/off`
   - 顺带修复测试端口问题：`options.port ?? DEFAULT_PORT`（原来 `||` 会把 0 当默认端口）
4. `pc-bridge/test/bridge-smoke.mjs`：
   - 新增 health 默认 desktopSync=false、开启实例 desktopSync=true
   - 新增 follower 请求载荷与不可用错误分类测试
   - 既有会话安全/排序测试保留（用户之前的改动，不要回退）
5. `pc-bridge/README.md`：
   - 参数表新增 `--desktop-sync` / `PC_BRIDGE_DESKTOP_SYNC=1`
   - 修正旧的错误说明：正确方法是 `thread-follower-start-turn`（v1），`turn/start` 外部调用会 no-client-found；补上回退与雷区说明

## 实测结果（已完成）
- 手机端实测通过：用户从手机向当前桌面已打开的会话发消息，桌面窗口实时显示并正常继续。
- 回退实测通过：未打开会话时自动走 CLI resume（kind=resume），完成且不报错。
- 后续别人要用：启动时加 `--desktop-sync` 或设 `PC_BRIDGE_DESKTOP_SYNC=1`；默认关闭，README 已写清。

## 回退实测记录（已完成）
- 临时会话 `019fc57c-4034-7e91-8f44-17c7be3cb5fd`：先 /api/exec 完成，再带开关 /api/resume。
- 结果：task kind=resume（说明 IPC 返回 no-client-found 后正确回退 CLI），status=completed，lastMessage=FALLBACK_OK。
- 临时目录已清理。

## 备份
- 本次备份：`D:\document\backups\step11-pc-bridge-follower-sync-20260803`（改前 18 个文件）
- 更早：step7/8/9/10 在 `D:\document\backups`；Aether 内还有 `.codex-backup`
- 不要手动编辑会话 JSONL 去补 client_id

## 进程/环境现状（可能变化，使用前确认）
- pc-bridge 当前进程：`node server.mjs --port 8899 --desktop-sync`，PID 52768；日志：pc-bridge.out.log / pc-bridge.err.log
- 桌面 App Server：`codex.exe app-server`，PID 49996；管道 `\\.\pipe\codex-ipc` 存在（刚确认）
- Codex 会话目录：`join(codexHome(), 'sessions')`，codexHome 通常是 `~/.codex`
- git 状态：pc-bridge 工作区未提交改动 = MEMORY.md、lib/codex-ipc.mjs、lib/codex-runner.mjs、lib/codex-sessions.mjs、server.mjs、test/bridge-smoke.mjs；另有未跟踪 `../codex-src/`（部分克隆，GitHub fetch 失败，不要动）

## 验证方法（同上，供恢复后直接执行）
1. 未打开会话 -> IPC 返回 `no-client-found` -> 自动回退 CLI resume，不报错。
2. 当前桌面已打开的会话 -> 手机发消息 -> 桌面应实时显示并自己回复；手机端也能轮询到回复。
3. 会话未打开时发消息应回退 CLI resume，桌面重启/重开后能看到。
4. 手动构造参数/权限类 IPC 错误 -> 明确失败，不回退（避免双写）。

## 雷区（不要再踩）
- 不要改 `client-discovery-request` 的 `canHandle` 为 true（会把桌面请求劫持到 pc-bridge，导致会话 UI 报错）。
- 不要在桌面 IPC 上发 `turn/start` 或旧方法名；只有 `thread-follower-start-turn`（v1）是主进程注册的。
- 不要手动改会话 JSONL 去补 client_id（并发写入有风险，且不是显示的关键）。
- 不要重启桌面 App 来刷新会话；会话文件持久，重启 App 是最后手段。
- 不要删/回退用户已有的 codex-sessions.mjs / bridge-smoke.mjs 改动。
- 不要把 desktopSync 默认开；未实测前默认关，避免影响手机端。

## 压缩后的恢复步骤
1. 读本文件完整内容。
2. `git -C D:\document\work\Aether\pc-bridge diff --stat` 确认当前改动。
3. 按“还没做的”顺序继续；每完成一步更新本文件的“已完成改动”。
4. 全部完成后按“验证方法”实测，最后把结果写回本文件。

## 代码位置速查
- `pc-bridge/lib/codex-ipc.mjs`：IPC 客户端 + follower payload（已改）
- `pc-bridge/lib/codex-runner.mjs`：任务执行（CLI resume / desktop turn，已改）
- `pc-bridge/server.mjs`：REST/MCP 入口 + 开关（已改）
- `pc-bridge/test/bridge-smoke.mjs`：冒烟测试（已改）
- 项目根：`D:\document\work\Aether\pc-bridge`