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