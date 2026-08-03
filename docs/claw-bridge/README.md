# Claw Bridge 复用知识库

> 目标：Aether 接 AutoClaw / OpenClaw，最大程度保留龙虾操控能力。
> 结论：Claw 自己已有微信桥接（openclaw-weixin），Aether 不做微信直连。

## 一句话架构
手机 Aether -> pc-bridge -> claw-bridge（可独立模块）-> AutoClaw/OpenClaw gateway（CLI / WebSocket）-> 微信/QQ 通道 + 本地操控

## 关键结论（2026-08-03 探索）
1. AutoClaw 是 Electron 壳，内置完整 OpenClaw gateway（2026.4.23），并打了大量 AutoClaw 补丁。
2. 本机微信已登录：~/.openclaw-autoclaw/openclaw-weixin/，账号 d1ec9a99620b-im-bot 绑定 main agent。
3. gateway 端口 18789，mode=local，启动器 ~/.openclaw-autoclaw/gateway-launcher.cjs。
4. 裸跑 openclaw.mjs 需要 AutoClaw 的完整 env（OPENCLAW_STATE_DIR / OPENCLAW_CONFIG_PATH / OPENCLAW_PLUGIN_STAGE_DIR / OPENCLAW_SHELL），否则 openclaw-weixin 报 unknown channel id。
5. CLI 入口：openclaw agent --message ... --json / message send / sessions / channels / directory。
6. 微信协议是腾讯 iLink，要点见 wechat-ilink.md，完整协议在官方插件 README.zh_CN.md。

## 复用块
- 01-pc-bridge：Aether 到 Codex 的桥（REST + MCP + 桌面 IPC 实时同步），模式可复用到任意“手机入口 -> PC 引擎”。
- 02-pi-bridge：Pi agent 桥。
- 03-knowledge-claw-openclaw：本目录（AutoClaw/OpenClaw 微信接入知识 + 脱敏配置样例）。
- 04-scripts-tools：启动脚本等。

## 不做的事
- 不用 Aether 直接长轮询微信 iLink（会和 AutoClaw 抢游标/双写）。
- token/密钥不进备份、不进 git。
## claw-bridge 模块（2026-08-03 已实现）
- 位置：`D:\document\work\Aether\claw-bridge`
- 作用：Aether -> pc-bridge -> claw-bridge -> AutoClaw/OpenClaw gateway WS RPC（agent / sessions / agents）
- 认证：设备签名（`identity/device.json` + `identity/device-auth.json`），细节见 `ws-protocol.md`
- REST：`/api/health`、`/api/turn`、`/api/sessions`、`/api/agents`，默认端口 8900
- 启动：AutoClaw 桌面已开时 `node server.mjs --profile autoclaw --port 8900`；桌面没开先 `node tools/gateway-up.mjs`
- 冒烟：`node test/claw-smoke.mjs`（协议链路全 PASS；模型 401 只在独立 gateway 无 API key 时出现）
- CLI 备用：bundled `openclaw.mjs` 可用（`--help` 已实测），`agent --message ... --json` / `message send` / `sessions` 可作入口；桥接默认走桌面 gateway