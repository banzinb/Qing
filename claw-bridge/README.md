# claw-bridge

Aether 与 AutoClaw / OpenClaw 的桥接模块。微信/QQ 收发完全留在 Claw 内（openclaw-weixin 等通道），Aether 只通过 gateway 的 WebSocket RPC 发提示词、收回复。

## 架构

手机 Aether -> pc-bridge / HTTP -> claw-bridge -> AutoClaw 桌面 gateway (127.0.0.1:18789) -> OpenClaw 通道 + 本地操控

- AutoClaw 桌面端运行 gateway，端口 18789，token 写在 `~/.openclaw-autoclaw/.gateway-token`。
- 桥接优先连接已运行的 gateway（AutoClaw 桌面或独立启动的 gateway），不抢微信长轮询、不重装插件。
- 认证用设备签名（`identity/device.json` + `identity/device-auth.json`），拿到 `operator.write` 等 scopes 后调用 `agent` RPC。
- 备用 CLI 路径（`openclaw agent --message ... --json`）也可用，但需要独立 `OPENCLAW_PLUGIN_STAGE_DIR`，避免和运行中的 gateway 抢 `apguard.node`；桥接默认不走 CLI。

## 用法

```powershell
# 1) AutoClaw 桌面已开：直接启动桥接
node server.mjs --profile autoclaw --port 8900

# 2) AutoClaw 没开：先起独立 gateway（复用同一状态目录）
node tools/gateway-up.mjs
node server.mjs --profile autoclaw --port 8900

# 3) 冒烟测试（需要 gateway 在 18789）
node test/claw-smoke.mjs

# 4) 协议验证脚本
node tools/ws-spike.mjs --url ws://127.0.0.1:18789 --message "hi" --agent main
```

## API

- `GET /api/health`：profile + gateway 状态
- `POST /api/turn`：`{ "message": "...", "agentId": "main", "sessionKey": "...", "thinking": "off", "timeoutSec": 600 }`
- `GET /api/sessions?limit=50`
- `GET /api/agents`

## Profile

- `autoclaw`：本机桌面，状态目录 `~/.openclaw-autoclaw`，bundled gateway 在 `C:\Program Files\AutoClaw\resources\gateway\openclaw`。
- `openclaw`：班主任 OpenClaw，状态目录 `~/.openclaw`，配置变体；尚未在本机实测。

## 关键雷区

- 不要用 Aether 直连微信 iLink 抢长轮询。
- 不要并发运行两个 gateway 共用 18789；先探测端口再决定是否启动。
- `JITI_FS_CACHE=false` 是必须的，否则打包版 gateway 会把 jiti 缓存写进 Program Files 触发 EPERM。
- 别在 CLI 与 gateway 共享 `OPENCLAW_PLUGIN_STAGE_DIR` 时并行执行（apguard.node 会被锁）。
- `.gateway-token` 与 `identity/` 里的 token 属于本机凭证，不进 git、不进备份。