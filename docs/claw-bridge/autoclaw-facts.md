# AutoClaw 本机事实（2026-08-03 探索记录）

## 程序与版本
- 安装：C:\Program Files\AutoClaw\AutoClaw.exe（Electron，当前未运行）
- 内置 OpenClaw gateway：C:\Program Files\AutoClaw\resources\gateway\openclaw\，版本 2026.4.23
- CLI：node openclaw.mjs（package.json bin 字段 = openclaw）
- 启动器：~/.openclaw-autoclaw/gateway-launcher.cjs -> gateway-bundle.mjs

## 状态与配置
- 状态目录：C:\Users\0000\.openclaw-autoclaw（不是 ~/.openclaw）
- 配置：openclaw.json（channels: qqbot + openclaw-weixin；bindings: main <-> openclaw-weixin/d1ec9a99620b-im-bot）
- gateway：127.0.0.1:18789，mode=local，reload=hot
- env 关键项：OPENCLAW_STATE_DIR / OPENCLAW_CONFIG_PATH / OPENCLAW_PLUGIN_STAGE_DIR / OPENCLAW_SHELL

## 微信通道（openclaw-weixin 2.4.3）
- 插件目录：resources\gateway\openclaw\extensions\openclaw-weixin
- 账号落盘：~/.openclaw-autoclaw\openclaw-weixin\accounts.json + accounts\<id>.json（token/baseUrl/userId）+ <id>.sync.json + <id>.context-tokens.json
- 账号：d1ec9a99620b-im-bot（已绑定 main）、9815e2482f46-im-bot（未绑定）
- 后端：iLink（ilinkai.weixin.qq.com），协议要点见 wechat-ilink.md

## CLI 可复用入口
- openclaw agent --message ... --thinking ... --json
- openclaw message send --target <id> --message ...
- openclaw sessions --json
- openclaw directory peers|groups|self
- openclaw channels list|status

## 雷区
- 裸跑 openclaw.mjs 只设 OPENCLAW_STATE_DIR 时 openclaw-weixin 报 unknown channel id；必须走 AutoClaw 完整启动环境（gateway-bundle.mjs）。
- health/status 可能尝试拉起 gateway，探测必须加超时。
- 不要用 Aether 直连 iLink 抢长轮询（会和 AutoClaw 双写/抢游标）。