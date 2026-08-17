# pc-bridge 项目记忆（续作手册）

> 本文件是唯一恢复入口。上下文被压缩或丢失后，先完整读本文件再继续，不要重新排查。
> 记录时间：2026-08-03（第六次更新；手机端实测通过，桌面实时同步可用）

## 当前目标
让手机 Aether 经 pc-bridge 发给 Codex 桌面会话的消息实时显示在桌面端，不报错、不破坏会话。
用户已确认方案：备份 -> 改 pc-bridge -> 加开关 -> 重启 pc-bridge -> 实测。

## 一句话现状
- 桌面实时同步已完整打通：手机端发的消息实时显示在当前打开的桌面会话，功能可用。
- Claw 链路已接通：pc-bridge `/api/claw/*` + MCP `claw_*` 转发到 claw-bridge（127.0.0.1:8900），再走 AutoClaw/OpenClaw gateway。
- 冒烟测试（pc-bridge stub + claw-bridge smoke）通过；服务已用 `--desktop-sync --claw` 重启。

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
- pc-bridge 当前进程：`node server.mjs --port 8899 --desktop-sync --claw`，PID 22564；日志：pc-bridge.out.log / pc-bridge.err.log
- claw-bridge 当前进程：`node server.mjs --profile autoclaw --port 8900`，PID 30912，只监听 127.0.0.1；日志：claw-bridge.out.log / claw-bridge.err.log
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
## AutoClaw/OpenClaw 微信入口探索结论（2026-08-03，只摸不改）
### 目标修正（用户原话）
- 不做 Aether 到微信的直连：Claw 自己已有微信桥接（AutoClaw 内置 openclaw-weixin）。
- 要做的是 Aether 接 AutoClaw（本机）/ OpenClaw（班主任），最大程度保留龙虾操控能力。
### AutoClaw 本机事实
- 安装：`C:\Program Files\AutoClaw\AutoClaw.exe`（Electron，188MB），当前未运行。
- 内置 OpenClaw gateway：`C:\Program Files\AutoClaw\resources\gateway\openclaw\`，版本 2026.4.23；CLI 入口 `openclaw.mjs`。
- 状态目录：`C:\Users\0000\.openclaw-autoclaw\`（不是 ~/.openclaw）；配置 `openclaw.json`（10KB，含 channels/bindings/models）。
- 微信通道：openclaw-weixin 2.4.3（`resources\gateway\openclaw\extensions\openclaw-weixin\`），配置 enabled=true，账号 `d1ec9a99620b-im-bot` 已绑定 main agent；另有 `9815e2482f46-im-bot` 未绑定；QQ 通道 qqbot 也 enabled。
- 微信账号落盘：`~/.openclaw-autoclaw\openclaw-weixin\accounts.json` + `accounts\<id>.json`（token/baseUrl/userId）+ `<id>.sync.json`（get_updates_buf 长轮询游标）+ `<id>.context-tokens.json`。
- 微信后端协议：iLink（ilinkai.weixin.qq.com），头 AuthorizationType=ilink_bot_token / Authorization=Bearer <token> / X-WECHAT-UIN=随机uint32的base64；接口 getupdates/sendmessage/getuploadurl/getconfig/sendtyping；完整协议在插件 README.zh_CN.md。
- gateway：端口 18789（%APPDATA%\AutoClaw\settings.json：ws://127.0.0.1:18789），mode=local，reload=hot；当前无监听（AutoClaw 未开）。
- 启动器：`~/.openclaw-autoclaw\gateway-launcher.cjs` 加载 `resources\gateway\openclaw\gateway-bundle.mjs`；env 关键项：OPENCLAW_STATE_DIR（默认 ~/.openclaw-autoclaw）、OPENCLAW_CONFIG_PATH、OPENCLAW_PLUGIN_STAGE_DIR、OPENCLAW_SHELL。
- 雷：裸跑 `node openclaw.mjs` 只设 OPENCLAW_STATE_DIR 时 openclaw-weixin 报 unknown channel id（插件未注入）；必须用 gateway-bundle.mjs / AutoClaw 完整启动环境。health/status 可能挂住或尝试拉起 gateway，探测必须加超时。
- CLI 可用入口（供 Aether 适配器参考）：`message send --target <id> --message ...`、`agent --message ... --thinking ... --json`、`sessions --json`、`directory peers/groups/self`、`channels list/status`、`gateway`。
### GitHub 参考（已确认在线仓库）
- 官方：Tencent/openclaw-weixin（728 星；npm latest 2.4.6 需 OpenClaw>=2026.5.12，本机 bundled 2.4.3 配 2026.4.23）。
- formulahendry/wechat-acp（794 星）：微信 iLink 到 ACP agent 的桥，内置 openclaw/codex preset，可参考。
- photon-hq/wechat-ilink-client（76 星）：纯 TS iLink 客户端，可复用登录凭证，但会和 AutoClaw 抢长轮询，默认不用。
- 其他：freestylefly/openclaw-wechat、fastclaw-ai/weclaw、corespeed-io/wechatbot、Wscats/wechat-claw。
### 推荐接法（下一轮实现）
1. pc-bridge 新增 autoclaw 适配器：先探测 18789 gateway 与 AutoClaw.exe；gateway 已跑就直用，未跑才考虑用 gateway-launcher.cjs 拉起（默认不抢，避免与 AutoClaw App 冲突或双写）。
2. Aether 消息走 `openclaw agent --message ... --json`（或 gateway RPC），回复通过 sessions/JSONL 轮询；微信收发全部留在 AutoClaw 内，Aether 不碰 iLink 凭证。
3. OpenClaw 版（班主任）做成同一适配器的配置变体：state dir ~/.openclaw，插件从 npm 安装。

## claw-bridge 模块进度（2026-08-03）
- 已完成：`D:\document\work\Aether\claw-bridge` 模块（profiles / ws-client / gateway / turn / sessions / server / tools / smoke）。
- 实测：设备签名 connect 全 scopes；agent RPC accepted -> final；sessions.list 返回数组；HTTP server 8900 health/turn/sessions 200。
- 已知：独立 gateway 无 AutoClaw 注入 API key，agent 文本 401；协议链路已验证，桌面 AutoClaw 跑起来应正常。
- CLI 已验证：`node openclaw.mjs --help` 正常（AutoClaw bundled node + openclaw.mjs）；CLI 可作备用入口，但裸跑不加载微信插件，桥接默认走桌面 gateway。
- 待办：AutoClaw 桌面运行时用真实 token 实测一轮；OpenClaw profile 本机验证；提交 git（codex-src/ 未跟踪不要动）。
## 下一阶段：pc-bridge 接 claw-bridge（2026-08-03 已接通）

### 目标
Aether 手机端 -> pc-bridge -> claw-bridge -> AutoClaw/OpenClaw gateway，保留龙虾操控；微信远网通道留在 Claw（DeepSeek 已由 AutoClaw 桌面注入，无需再配）。

### Aether 接法总结（已读源码确认）
- Aether 手机端连 pc-bridge：REST http://<IP>:8899 或 MCP /mcp；正确地址 http://192.168.1.41:8899（80 端口没有服务）。
- 现有 MCP 工具：codex_list_sessions / codex_read_session / codex_exec / codex_resume / codex_poll / codex_stop / pc_shell / pc_file_read / pc_file_write / pc_file_list / pc_git_status。
- 任务模型：CodexRunner 建 task -> 手机端轮询 /api/tasks/:id；desktopSync 开关走 thread-follower-start-turn，失败回退 CLI。
- claw-bridge 独立服务 8900：/api/health /api/turn /api/sessions /api/agents；turn 同步等 agent 返回；设备签名自动拿 operator.write。

### 确认的接法（开工顺序）
1. 新建 pc-bridge/lib/claw-adapter.mjs：HTTP 客户端，默认 http://127.0.0.1:8900，env CLAW_BRIDGE_URL 覆盖；只连本机。
2. CodexRunner 加 startClawTurn（kind='claw'）：后台调 claw-bridge /api/turn，完成后更新 task；手机端复用 codex_poll / codex_stop。
3. REST：/api/claw/health、/api/claw/turn、/api/claw/sessions、/api/claw/agents。
4. MCP：claw_turn / claw_sessions / claw_agents / claw_health，注册到 mcp.mjs + server.mjs handlers。
5. 开关：--claw / PC_BRIDGE_CLAW=1（默认开；claw-bridge 未起时明确报错，不影响 Codex）。
6. 测试：bridge-smoke 加 claw adapter 构造/错误用例；README + MEMORY 更新；重启 pc-bridge 实测 health/turn。
7. 安全：建议 claw-bridge 改绑 127.0.0.1（当前 0.0.0.0），不暴露局域网。

### 进程现状（使用前确认）
- pc-bridge PID 22564，8899，--desktop-sync --claw；health 正常，claw=true。
- claw-bridge PID 30912，8900（只监听 127.0.0.1）；health 正常；gateway 未运行（AutoClaw 桌面未开）。
- standalone gateway 未运行；需要真实 turn 时先起 AutoClaw 桌面或 gateway-up，文本 401 是缺 key 不是 bug。
- 本机 IP：WLAN 192.168.1.41；Radmin VPN 26.97.215.167。
- 手机 Aether 填 80 端口会失败，应填 8899。

### 雷区
- 不碰 codex-src/（未跟踪，勿 add）。
- 不抢微信长轮询；不并发两个 gateway 共用 18789；CLI 与 gateway 不共享 plugin stage dir。
- 不要改 client-discovery-request canHandle。
- 独立 gateway 401 是缺 key，不是桥接 bug；桌面 AutoClaw 跑起来应正常。

### 本阶段完成记录（2026-08-03）
1. 已完成：claw-adapter.mjs（HTTP 客户端，默认 http://127.0.0.1:8900，CLAW_BRIDGE_URL 可覆盖）、CodexRunner.startClawTurn（kind='claw'，复用 codex_poll/codex_stop，stopTask 可 abort）、server.mjs claw 开关（默认开）+ 4 个 REST 路由 + 4 个 MCP handler、mcp.mjs 4 个工具、bridge-smoke 用本地 stub 覆盖 adapter/REST/MCP/stop、claw-bridge 改绑 127.0.0.1。
2. 测试：pc-bridge npm test 全过（含 claw stub 用例）；claw-bridge node test\claw-smoke.mjs 因独立 gateway 未启动显示 SKIP（协议链路之前已验证过，不是本次回归）。
3. 服务重启：pc-bridge `--port 8899 --desktop-sync --claw`，claw-bridge `--profile autoclaw --port 8900`；health 验证通过。
4. 提交：`feat(pc-bridge): route Aether turns to claw-bridge`（只 add pc-bridge/ + claw-bridge/，未碰 codex-src/）。

## Tutti 研究 + 微信版 Agent OS 方向（2026-08-03，只摸不改）

### 起因
- 班主任想法：少即是多，一个入口，多重平台，入口唯一，功能接入。
- 用户分享微信文章：开源项目 Tutti（tutti-os/tutti，Apache-2.0，约 3.2k stars，macOS 桌面版，Windows 未出，VM 版待定）。它就是班主任想法的桌面实现。

### Tutti 是什么
- 定位：Agent OS / 多 Agent 共享工作区，不是新 Agent。
- 核心：实时共享工作区（上下文/文件/App/任务互相可见）、Big @（跨 Agent 引用）、+ 引用文件、Apps（AI Canvas/AI PPT/AI Doc/Prototype Design）、Goal to Tasks、Control Center、BYO Subscriptions（复用本机已登录的 Claude Code/Codex 订阅，不重复付费）。
- 现支持：Claude Code、Codex、Hermes；OpenClaw 在开发中且当前标记 unsupported/禁用；Tutti Agent 免费试用。
- 仓库形态：monorepo，apps/（cli, desktop, mobile, ui-storyboard），packages/（agent, workbench, workspace, appcli, device-link...），services/tuttid（Go 守护进程），docs/（architecture/adr/specs/plans），pnpm + Go。

### 关键架构结论
- Agent 接入走 ACP（Agent Client Protocol）：声明式扩展包（signed manifest + 配置 profile），标准 ACP 适配器，托管 runtime。
- Claude Code：官方 SDK sidecar（packages/agent/claude-sdk-sidecar）。
- Codex：codex app-server 协议（codexproto）。
- OpenClaw 适配（packages/agent/daemon/runtime/acp_provider_openclaw.go + providerregistry/providers.go openClawDescriptor）：
  - 启动命令：openclaw acp -v（标准 ACP 模式）。
  - sessionKey：openclawGatewayChatSessionKey 用 prefix + agentSessionID，避免 openclaw acp 默认 "acp:<uuid>" 导致 gateway 把聊天当 ACP-spawned session 要 sessions.json。
  - env：NODE_DISABLE_COMPILE_CACHE=1（否则 Node 编译缓存可能卡 ACP initialize；和之前 jiti EPERM 同源问题）。
  - auth marker：~/.openclaw/auth.json、~/.config/openclaw/auth.json；login 用 openclaw login；安装 npm install -g openclaw；skill root：.openclaw/skills。
  - 当前状态：openClawDescriptor 用 unsupportedACPDescriptor，Target enabled=false（Tutti 还没正式支持 OpenClaw）。

### 对咱们项目的意义
- 微信版 Agent OS 可行，且比 Tutti 更贴合班主任：Tutti 只有 macOS 桌面，OpenClaw 未放开；我们做 OpenClaw 桌面 + 微信 + skills。
- 形态：微信（唯一入口）-> OpenClaw gateway（调度层）-> skills 路由到 Claude Code / Codex / 浏览器等；长任务微信回“开始/完成/停止”。
- 不复制 Tutti 重型机制（签名扩展包/ACP daemon），只抄思路：BYO 订阅、goal to tasks、control center（微信任务状态）、共享上下文（sessionKey 延续）。
- 已确认 OpenClaw 自带 exec/process 工具，能起 TTY 命令（pty=true 可跑 coding agent），所以“微信触发 Claude Code”可行。

### 状态
- 只做了研究和记忆，未写任何代码/未改 App。
- 待办（等用户叫开始再干）：
  1. 写 docs/tutti-research.md（如需要更详细引用）。
  2. 原型 skill：微信发 “claude 帮我…” -> 调 Claude Code CLI -> 结果回微信（先在 AutoClaw 本机验证）。
  3. 兼容包：检测 OpenClaw 版本/微信插件/claude CLI，再交付班主任。
- 雷区沿用：不抢微信长轮询、不并发两个 gateway 共用 18789、不动 codex-src、不擅自开干。

## 收尾定位讨论（2026-08-03，已记录，未动代码）

### 班主任建议分析（用户原话）
- “在 github 上找到的手机 agent 项目”就是 Aether 自己（“我的就是现在做个 aether / 我当时看到的就是 aether”），不用再仿造，直接继续做 Aether 即可。
- 配 DeepSeek：用户没有 CC/Codex 模型 key，但 AutoClaw 桌面已注入 DeepSeek，支持直接用。
- 网络局限：Aether + Codex 需要同一网络（或 Radmin VPN 26.97.215.167）；微信接 Claw 走 iLink 远网，不需要同网。买云服务器可解决跨网，但目前不买。
- 项目状态：Aether 主体接近收尾，剩余 = 微信生态 + 远程（跨网）生态。

### 定位结论（两条线分开交付）
- 微信已经能连 Claw，再做 App 内微信入口 ≈ 造轮子，不做。
- 班主任要的是“微信直接发给 Claude Code”：OpenClaw 自带 exec/process + pty 可跑 Claude Code CLI，可行。
- OpenClaw agent 编程能力不如 Claude Code，所以“微信触发 Claude Code”是班主任线的核心价值。
- 自己线：Aether App 继续做本机/手机操作生态（pc-bridge + claw-bridge 已通）。
- 班主任线：OpenClaw 桌面 + 微信 + skill（微信版 Agent OS，参考 Tutti），独立交付。
- 兼容风险：用户只有 AutoClaw、班主任用 OpenClaw，中间层不同，适配器要做 profile 变体（autoclaw / openclaw），两边分别验证后再交付。

### 下一步（等用户叫开始再干）
1. 写 docs/tutti-research.md（含 ACP、OpenClaw 适配参数、sessionKey / NODE_DISABLE_COMPILE_CACHE 处理）。
2. 原型 skill：微信发 “claude 帮我…” -> 调 Claude Code CLI -> 结果回微信（先在 AutoClaw 本机验证）。
3. 兼容交付包：检测 OpenClaw 版本/微信插件/claude CLI，自动化验证后再给班主任。
- 雷区沿用：不抢微信长轮询、不并发两个 gateway 共用 18789、不动 codex-src、不擅自开干。

## Tutti 聚合思路的现成参考项目（2026-08-03 联网调研，只记录不动代码）

### 核心结论
- “Tutti 式聚合”在开源界已有多条成熟路线，最对口的是「OpenClaw 当引擎 + 产品层/入口层自己包」。
- OpenClaw 本身就具备多 Agent（独立身份/模型/skills/工作区）、微信/飞书等渠道、skills、MCP、定时任务、gateway、subagents 委派；缺的是产品层：统一工作区 UI、跨 Agent 会话聚合、@委派、工件渲染、IM 控制台、配置可视化。
- 所以“用龙虾做聚合” = OpenClaw runtime + 产品层，现有项目已把这条路线全部走过一遍，直接抄比从零造稳。

### 直接参考清单（按相关度）
1. netease-youdao/LobsterAI（5.7k★，MIT，Electron+React，Win/mac）：网易有道开源，完全就是 OpenClaw 之上的产品层。Cowork 会话、多 Agent 工作流、28 内置 skills、MCP 同步、定时任务、IM 遥控（微信/企微/钉钉/飞书/QQ/Telegram…）、工件渲染、SQLite 本地记忆。OpenClaw 集成层：openclawEngineManager / openclawConfigSync / openclawRuntimeAdapter / coworkEngineRouter；自带 openclaw-extensions（ask-user-question、media-generation、mcp-bridge）。
2. xintaofei/codeg（2.5k★，Win/mac/自托管/Docker + iOS/Android 客户端）：多 Agent 编码工作台，聚合 Claude Code/Codex/OpenCode/OpenClaw/Hermes/Pi/Grok 等全部会话到一个可搜索工作区；主 Agent 用 @ 委派给其它类型子 Agent；支持任意 ACP 注册。最接近 Tutti 的“一个入口多重平台”。
3. golutra/golutra（3.8k★，Tauri+Vue3+Rust，Win/mac/Linux，BSL1.1）：把 Claude Code/Codex/OpenClaw/任意 CLI 统一成 AI 员工编排台：并行执行、工作流模板、stealth terminal、结果交接。
4. nexu-io/nexu（3.2k★，MIT，Win/mac）：OpenClaw 桌面客户端，一键把 OpenClaw 接到微信/飞书/Slack/Discord；controller-first：Hono 编译 OpenClaw 配置（agents/channels/bindings/models）→ OPENCLAW_CONFIG_PATH → 热重载。实现了我们之前“适配器编译 OpenClaw 配置”的思路。
5. formulahendry/wechat-acp（794★，MIT）：微信 iLink 直连任意 ACP Agent（内置 claude/codex/openclaw/hermes 等 preset）；微信扫码、每个用户一个 session、文件/图片/音频回传、daemon、多实例。班主任要的“微信直发 Claude”最小实现。
6. zhayujie/CowAgent（46k★，原 chatgpt-on-wechat）：多模型多渠道 Agent 平台，一行安装。
7. langbot-app/LangBot（17k★）：生产级多平台 IM 机器人平台，已集成 openclaw/hermes agent。
8. wzdavid/openclaw-desktop（40★）：OpenClaw 桌面工作区（chat/配置/skills/文件/终端/定时/多 Agent），Win/mac 安装包，可看产品形态。
9. zhimaAi/ChatClaw（302★）：Go 写的 30MB 轻量 OpenClaw 类 Agent，Win/mac，接微信企微/QQ/飞书/Telegram 等。
10. 其它：AaronWong1999/hermesclaw（705★，同一微信号跑 Hermes+OpenClaw）、sunnoy/openclaw-plugin-wecom（706★，企微插件）、fastclaw-ai/weclaw（1.6k★）、terragon-labs/terragon-oss（253★，云端编排 Claude Code/Codex）。

### 对咱们的落地建议
- 班主任线（微信入口）：最快是 wechat-acp（`npx wechat-acp --agent claude/codex`）或 nexu/LobsterAI 的产品壳；不抢 AutoClaw 长轮询，用独立微信号/实例。
- 自己线（Aether + claw-bridge）：参考 codeg 的 @委派与会话聚合、LobsterAI 的引擎管理分层，在已有 claw-bridge 上加「Agent 目录 + 任务状态 + 结果回传」，OpenClaw 仍当唯一引擎。
- 不复制 Tutti 重型机制，沿用：BYO 订阅、goal to tasks、control center、共享上下文。
- 雷区不变：不抢微信长轮询、不并发两个 gateway 共用 18789、不动 codex-src。

## OpenClaw 原生多 agent 会话命令（2026-08-03 发现，已记录，今晚实测）

### 核心结论（修正旧结论）
- OpenClaw 网关自带"一个聊天窗口管理多 agent 会话"的原生命令层，微信通道也会把斜杠命令交给网关处理，不是只能当普通对话发给 AI。
- 修正之前"微信端做不到多 agent 会话"的说法：AutoClaw 打包的 gateway 就是同一套 OpenClaw，理论上微信里 /subagents spawn ... 就能开多 agent 会话。
- AutoClaw 自己的 openclaw-weixin 只实现 /echo、/toggle-debug；其它斜杠命令会带着 CommandBody/CommandAuthorized 走 dispatchReplyFromConfig，由网关 reply 管线先处理命令，不进 AI 对话。

### 证据（AutoClaw bundled gateway dist，已查）
- 命令注册：dist/commands-registry.data-SU8xq2YS.js 的 buildBuiltinChatCommands，命令表含：
  - /subagents spawn <agentId> <task> [--model <model>] [--thinking <level>]
  - /subagents list | agents | info | log | kill | send | steer | help
  - /focus /unfocus /agents /new /reset /session /tasks /model /acp 等
- 执行器：dist/commands-handlers.runtime-DgA_CtL9.js 的 handleSubagentsCommand；action-spawn/list/kill/send/focus 等独立模块。
- 微信链路：extensions/openclaw-weixin/src/messaging/process-message.ts 设置 ctx.CommandBody + ctx.CommandAuthorized，走 channelRuntime.reply.dispatchReplyFromConfig；网关 get-reply -> handleCommands 先处理命令。
- 官方 Tencent/openclaw-weixin 最新版 process-message.ts 同样如此（GitHub 已确认）。
- 子 agent 限制：maxSpawnDepth 默认 1、maxChildrenPerAgent 默认 5、requireAgentId / allowAgents 可配。
- /focus 依赖通道 conversationBindings 能力，微信插件可能不支持；spawn/list/steer/kill 不依赖。
- 命令授权按 pairing/allowFrom 校验，微信发送者必须配对/在允许列表。

### 今晚计划（用户已点头做这个）
1. 备份：~/.openclaw-autoclaw/openclaw.json + 状态目录；MEMORY.md 先记本段。
2. 在 openclaw.json 加第二个 agent（如 codex/claude/deepseek 变体），确认模型 key 来源（AutoClaw 桌面注入 DeepSeek）。
3. 起 AutoClaw gateway（gateway-launcher.cjs 或 AutoClaw.exe），确认 18789 监听。
4. 微信实测：/subagents spawn <agentId> 测试任务 -> /subagents list -> steer/send -> kill。
5. 记录实测输出与失败原因（授权、插件版本、gateway 注入、模型 key）。
6. 成功：班主任线直接走 OpenClaw 原生命令层，LobsterAI/codeg fork 降级为备选；Aether 微信生态扩展停掉（用户原话：那边就不用做了）。
7. 失败：记录失败点，再决定升级 openclaw-weixin 或转 LobsterAI。

### 项目影响（用户当前判断）
- Aether 主体接近收尾；如果 OpenClaw 原生微信多 agent 会话跑通，Aether 不需要再做微信入口/聚合那套。
- claw-bridge 保留（已是 Aether 生态一部分），不再扩展微信桥。
- 班主任线交付形态：OpenClaw + openclaw-weixin + 微信扫码 + /subagents 命令，配置兼容包仍可做。

### 雷区沿用
- 不抢微信长轮询、不并发两个 gateway 共用 18789、不动 codex-src、改配置前先备份。
- 先备份再改 ~/.openclaw-autoclaw/openclaw.json。

## 今晚开工决策（2026-08-03 晚，压缩前固化）

### 已定方向（用户确认）
- 做独立 OpenClaw CLI/gateway 版，验证微信原生多 agent 会话（/subagents 命令层）。
- 先装 CLI，不装桌面壳；AutoClaw 保留为 Aether 引擎，两边状态目录分开，不互踩。
- 成功标准：微信里 /subagents spawn <agentId> 任务 -> /subagents list -> steer/send -> kill 全部可用。
- 成功后：班主任线直接走 OpenClaw 原生命令层，LobsterAI/codeg fork 降级为备选，Aether 微信生态扩展停掉。

### 关键事实（别重新查）
- openclaw npm 最新版：2026.7.1-2；tarball 约 19.7MB，unpacked 约 83.4MB。
- Node 要求：>=24.15.0 <25 或 >=25.9.0；本机 Node 24.12.0，差小版本，可能要升 Node 或用 AutoClaw 自带 node runtime。
- 官方 openclaw-weixin 最新 2.4.6 要求 OpenClaw >= 2026.5.12；AutoClaw 打包的是 2026.4.23 + weixin 2.4.3（旧）。
- openclaw-desktop（wzdavid，40★）最新 v0.4.4：Windows Setup exe 141.2MB，暂缓。
- 独立 OpenClaw 微信登录凭证在 ~/.openclaw，与 AutoClaw 的 ~/.openclaw-autoclaw 不是同一份，同一 bot 账号两边不能同时开长轮询。
- 本机目前无独立 openclaw CLI；~/.openclaw 已存在（只有 exec-approvals.json + qqbot 数据目录）。
- AutoClaw bundled gateway（2026.4.23）手动启动若卡 jiti 写缓存 EPERM，设 JITI_FS_CACHE=false 可绕过；独立 CLI 走用户目录一般不触发。

### 今晚执行顺序
1. 备份：~/.openclaw-autoclaw/openclaw.json + 状态目录；记录 MEMORY.md 变更。
2. 安装独立 OpenClaw CLI（npm i -g openclaw 或指定版本 2026.7.1-2）；确认 Node 版本满足或选定 runtime。
3. 安装/配置最新 openclaw-weixin（2.4.6，OpenClaw>=2026.5.12 已满足则直接用）。
4. 配置多 agent：openclaw.json 加第二个 agent（如 DeepSeek 变体），确认模型 key 注入方式。
5. 起 gateway，确认 18789 监听；微信扫码登录。
6. 微信实测 /subagents 命令族，逐条记录输出/报错。
7. 更新 MEMORY.md 实测结果与下一步（成功/失败分支）。

### 雷区沿用
- 不抢微信长轮询、不并发两个 gateway 共用 18789、不动 codex-src、改配置前先备份。
- 先备份再改 ~/.openclaw-autoclaw/openclaw.json；独立 OpenClaw 配置用 ~/.openclaw/openclaw.json。
- 写入中文文件用 base64 环境变量方式（PowerShell 管道会乱码）。
## 2026-08-06 方案1实测：OpenClaw 召唤外部引擎（Codex/Claude Code）

### 已定方向（用户确认）
- 不做三引擎平等伪群聊（方案2，OpenClaw 原生做不到真共享会话）。
- 走方案1：OpenClaw main 是微信唯一入口，Codex CLI / Claude Code 是被点名调用的外部执行引擎，结果由 main 回传微信。
- OpenClaw 原生子 agent 继续可用（分模型/角色），与外部引擎召唤不冲突。

### 本机状态（2026-08-06 实测）
- 独立 OpenClaw gateway：D:\document\runtime\node-v24.19.0-win-x64\openclaw.cmd gateway run --port 18789；首次启动可能慢 30-60s，JITI_FS_CACHE=false 可绕过 jiti 卡点。
- 微信通道 openclaw-weixin 46e0866d5a4c-im-bot：enabled/configured/running。
- codex CLI 已登录，但实际配置是 CodexPlusPlus provider -> https://api.deepseek.com/v1，模型 deepseek-v4-flash，别误判成 OpenAI。
- claude CLI 未登录：claude auth status 返回 loggedIn:false；要接 Claude Code 必须先登录/配 ANTHROPIC_API_KEY。

### 已落地
- skill：~/.openclaw/skills/external-codex-claude/SKILL.md（openclaw-managed，modelVisible=true，commandVisible=true）。
- 实测：OpenClaw main 按 skill 先查登录态，再 codex exec 回答 1+1/2+2，均正常返回；工具调用 0 失败。
- 踩坑：exec/process 的 cwd 不要传字面 $env:TEMP，要先解析成绝对路径（skill 已写明）。

### 下一步
- 用户登录 Claude Code 后即可把 claude -p 接入同一 skill。
- 微信里直接说“让 Codex 看看/让 Claude Code 看看”，main 会按 skill 执行并回传。
- 暂不做多引擎并发辩论；如需要再看 LobsterAI/codeg 产品层。

## 2026-08-09 多 agent 协作定案 + Claude 接入暂停

### Claude 接入现状（用户原话/决定）
- 现在 Claude 弄不了；目标不是 Claude Code CLI，而是 Claude Desktop 桌面端，后续再研究。
- claude CLI 与桌面端不互通（登录/会话不共享），所以 DeepSeek Anthropic 兼容端点（ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic + ANTHROPIC_AUTH_TOKEN）只对 CLI 有效，解决不了桌面端诉求；本机 claude CLI 仍 loggedIn:false，暂不折腾。
- 外部引擎（codex/claude）整体标 experimental；codex 本机实际借 DeepSeek（CodexPlusPlus -> https://api.deepseek.com/v1），是本地特例，不能写进通用交付当功能卖。

### 多 agent 产品方向（用户确认）
- 最终目标像 CLI-WeChat-Bridge/CowAgent/wechat-acp 那样做群聊，但真微信群聊目前做不到（openclaw-weixin 3.0.1 写死 isGroup=false），求其次做多 agent 协作/单聊伪群聊。
- 形态：OpenClaw 接微信提供便携度；main 当群主/协调，/指令点名（/agent-b 等），/debate 编排轮流发言，共享转写 JSONL + MEMORY.md 做记忆，全部回复回到同一微信会话。
- 定位：轻量化、开发者友好（只加 JSON 配置 + Markdown skill）、可长期跑（单一 gateway 常驻、状态/登录不丢、日志、可换宿主）；不 fork 插件源码、不装桌面壳。

### 交付形态（班主任线）
- 班主任已有 OpenClaw：只发差异包，不打包安装包、不做 exe。
- 差异包内容：agents 配置片段（key 用占位符，不发真实 key）、skills/ 目录（agent-b/agent-c/debate 等，不发 external-codex-claude 本地特例）、中文怎么合说明 + 可选 start-gateway.ps1。
- 发之前先对版本：openclaw --version + openclaw plugin list；要求 OpenClaw >=2026.5.12（现用 2026.7.1-2）+ openclaw-weixin；若是 AutoClaw 桌面壳旧版（2026.4.23 + weixin 2.4.3）不能直接贴差异，需先升级。
- 先零安装演示（班主任只用手机扫码/发消息），确认要长期用再谈装哪台常开机器；bot 用专门小号，key 用班主任自己的。

### 下一步（等用户叫开始）
1. 先做 /debate 最小编排：OpenClaw 子 agent 或外部引擎轮流发言 + 共享转写文件，跑 3 轮验证记得对方说过的话。
2. 微信实测 /agent-b、/debate 斜杠命令路由。
3. 跑通后给班主任演示；再考虑 GitHub 模板（范围收窄：脚本 + 脱敏配置模板 + README + FAQ，MIT，不拷 openclaw-weixin 源码，README 里 openclaw plugins install npm:openclaw-weixin --force 即可）。
4. Claude Desktop 接入另行研究（暂缓）。

### 雷区沿用
- 不抢微信长轮询、不并发两个 gateway 共用 18789、不动 codex-src、改配置前先备份。
- ~/.openclaw（独立）与 ~/.openclaw-autoclaw（AutoClaw）不混用；写中文记忆用 base64 环境变量。
## 2026-08-09 睡前定稿：明日实现多 agent 协作（班主任线）

### 已定方案
- 做 OpenClaw 接微信的多 agent 协作/单聊伪群聊：main 当群主，/指令点名（/agent-b 等），/debate 编排轮流发言，共享转写 JSONL + MEMORY.md 做记忆，回复回同一微信会话。
- 真微信群聊不做（openclaw-weixin 3.0.1 写死 isGroup=false），交付时明确是单聊伪群聊。

### 明日清单（工作量不大）
1. 搭 /debate 最小编排：共享转写文件 + 2-3 个 agent 轮流发言，跑 3 轮，验证记得对方说过的话。
2. 加 Claude 引擎适配器：skill 读本地配置 ~/.openclaw/engines/claude.json（baseUrl/authToken/model），不写死任何值。
   - 本机：baseUrl=https://api.deepseek.com/anthropic，model=deepseek-v4-flash，key 用 ccswitch 桌面端那套（已直连实测 200，1+1 -> 2）。
   - 班主任：Anthropic API key 走 http，或 Claude 订阅登录走 claude -p（模式再定）。
3. 微信实测 /agent-b、/debate 斜杠命令，逐条记录输出/报错。
4. 跑通后给班主任零安装演示；确认后再发差异包（agents 配置片段 + skills + 中文说明，不含 key、不含 ccswitch 配置）。

### 今晚摸清的事实（别重查）
- Claude Desktop 是 GUI，没有程序化调用接口；适配桌面端 = 复用其 ccswitch/DeepSeek 后端（Anthropic 格式 HTTP），不是驱动桌面窗口；桌面窗口不会显示编排对话。
- 本机 claude -p 当前不可用：ccswitch 代理 127.0.0.1:15721 在跑，但 claude（CLI）app_type 当前选中 Claude Official 且无 base_url，报 400 缺少 base_url 配置。
- ccswitch 配置在 ~/.cc-switch/cc-switch.db，Claude Code 与 Claude Desktop 是两条独立 provider 线；桌面端 DeepSeek provider（6e8ffdee-269e-4f22-b535-792ffc8aa8d0，https://api.deepseek.com/anthropic）健康。
- codex 本机仍借 DeepSeek（deepseek-v4-flash），是本地特例，不进通用交付。
- 班主任用正常官方 Claude；差异包不含 ccswitch/DeepSeek 特例。

### 雷区沿用
- 不抢微信长轮询、不并发两个 gateway 共用 18789、不动 codex-src、改配置前先备份。
- ~/.openclaw（独立）与 ~/.openclaw-autoclaw（AutoClaw）不混用；写中文记忆文件避免 PowerShell 管道直写。
## 2026-08-09 已实现：多 agent 协作 v1（本机跑通，待微信实测）

### 新增文件（都在 ~/.openclaw 下）
- scripts/team-lib.mjs、team-ask.mjs、team-debate.mjs：共享转写 + 单点点名 + 多轮辩论。
- engines/team.json：参与者 agent-b(planner/pro)、agent-c(worker/flash)、claude(HTTP)；转写文件；mirrorTo C:\Users\0000\Claude\team-memory.md。
- engines/claude.json：走桌面端同款 DeepSeek Anthropic 端点（key 取自 ccswitch 桌面端 provider）。
- skills/agent-b、agent-c、claude、debate、team-status：全部 ready，斜杠命令可用（whoami 与内置命令重名已改 team-status）。

### 本机验证（已过）
- agent-b / claude 单点：1+1 -> [id | model] 2。
- /debate 1 轮三参与者：都记得前面讨论，带 [id | model] 标签。
- mirror 镜像：转写复制到 C:\Users\0000\Claude\team-memory.md。
- gateway 18789 已起，微信账号 46e0866d5a4c-im-bot 在线；openclaw agent 端到端调 agent-b skill 返回 [agent-b | deepseek-v4-pro] 2。

### 待办
- 微信实测 /team-status /agent-b /agent-c /claude /debate。
- 班主任差异包（配置模板不含 key）与 MCP 记忆回桌面端（第二步）。
- 注意：skill 让 main 把 ~/.openclaw/scripts 解析成绝对路径再执行，班主任机器同样适用。
- 2026-08-09 追加：codex 已接入，/codex 命令可用；辩论默认 codex + Claude，模型统一 deepseek-v4-flash（便宜性能强）；已实测 codex/claude 互辩并共享转写。
- 2026-08-09 封装：差异包已生成 D:\document\project\openclaw-team-diff（zip 同名），内容 scripts + engines 模板（无 key）+ skills + README/FAQ + start-gateway.ps1；已扫描无真实 key。发班主任前需确认：他的 OpenClaw 版本（>=2026.5.12）和 Claude 方式（官方 API key 或订阅 cli 模式）。

## 2026-08-09 Codex 桌面端接入 OpenClaw 团队（已实测通过）

### 结果
- 微信 OpenClaw 的 /codex（以及 /debate 里的 codex 回合）现在优先推送到 Codex 桌面端专用会话：消息实时显示在桌面窗口，回复经 pc-bridge 回传微信；桌面会话保留完整聊天记录，团队共享转写文件 ~/.openclaw/team/transcripts/wechat-team.md 会随回合提示给桌面模型。
- 桌面会话未打开时自动回退 CLI resume（同一会话，带 -s danger-full-access），输出带 [via: cli-fallback]；走桌面时带 [via: desktop]。

### 实现与配置
- team.json 的 codex 参与者新增 desktop 配置：enabled / baseUrl / sessionId / memoryFile / timeoutMs。当前 sessionId = 019fe5ed-a47b-7843-9aca-49362dc06702（桌面端 wx 文件夹内新建，标题“我是微信团队 Codex 桌面端成员…”）。
- team-lib.mjs 新增 callCodexDesktop：POST pc-bridge /api/resume -> 轮询 /api/tasks/:id 取 lastMessage；pc-bridge 不可用或 start 被拒时回退 callCodexCli（新 CLI 会话）。
- team-ask.mjs / team-debate.mjs 输出增加 [via: desktop|cli-fallback]。
- pc-bridge lib/codex-runner.mjs 修复：startResume 现在带 -s <会话 sandboxPolicy 或 danger-full-access>，避免 CLI 回退把会话降级成 workspace-write，进而被 resume 守卫拒绝续跑。

### 当前进程
- pc-bridge：node server.mjs --port 8899 --desktop-sync，运行中；gateway 18789 运行中；微信 bot 在线。
- 备份：D:\document\backups\pc-bridge-2026-08-09、D:\document\backups\openclaw-team-codex-desktop-2026-08-09。

### 雷区/教训（新）
- Codex 桌面 UI 不显示 CLI 创建的会话；团队桌面会话必须在桌面 UI 新建并保持打开。thread-follower-start-turn 只接受桌面 owner 状态的会话，会话没打开时返回 no-client-found（pc-bridge 自动回退 CLI resume）。
- CLI 创建的会话即使文件存在、pc-bridge 列表可见，桌面端也打不开；且 resume 不带 -s 会把会话沙箱记录降级成 workspace-write（已修复，但仍建议用桌面 UI 建的会话）。
- 别用 Windows PowerShell Invoke-RestMethod 直发中文 JSON body（会变成 ?）；用 UTF-8 bytes 或 Node fetch。


## 2026-08-09 补充：微信不回消息 + 上下文过重修复

### 问题 1：桌面回复到了主 agent，微信却没收到
- 根因：/codex 的 tool 输出已回到 OpenClaw 主 agent，但主 agent 最终文本输出了字面量 NO_REPLY（OpenClaw 约定表示不回消息），微信被静默。
- 修复：team skills（agent-b/agent-c/claude/codex/debate）统一追加 Final reply rules：脚本执行完必须给用户最终中文文本回复；永远不要以工具调用/空文本结束；永远不要输出 NO_REPLY。codex skill 还要求按 [via: desktop] / [via: cli-fallback] 说明来源。
- 改完需重启 gateway 才会重新编译 skill 进系统提示（已重启）。

### 问题 2：上下文越滚越爆
- 原 memoryTailLines=120，每轮把团队历史全文贴给桌面 agent，几轮就 16KB+。
- 现在：memoryTailLines=40 + memoryTailChars=6000（非桌面参与者仍带紧凑尾部）；codex desktop inlineTailChars=0，桌面提示词只含任务 + 团队记忆文件路径（160 字符），桌面会话自身历史 + 按需读 ~/.openclaw/team/transcripts/wechat-team.md 提供上下文。
- team-lib 新增 buildCodexDesktopPrompt；team-ask/team-debate 对桌面 codex 用它，其它参与者用 buildPrompt + 字符上限。

### 当前进程/命令
- gateway：D:\document\runtime\node-v24.19.0-win-x64\node.exe D:\document\runtime\node-v24.19.0-win-x64\node_modules\openclaw\openclaw.mjs gateway run --port 18789（重启后微信自动重连，无需扫码）。
- pc-bridge：node server.mjs --port 8899 --desktop-sync。
- 备份：D:\document\backups\openclaw-team-lite-2026-08-09、openclaw-team-noinline-2026-08-09。
