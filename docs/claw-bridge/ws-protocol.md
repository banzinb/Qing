# AutoClaw Gateway WS RPC 协议（已实测）

## 连接流程
1. 客户端连接 `ws://127.0.0.1:18789`。
2. 服务器先发 `connect.challenge` 事件，payload 带 `nonce`。
3. 客户端回 `connect` 请求（protocol 3），带 scopes + 可选 device 签名。
4. 服务端回 `connect` 结果，成功后其他 RPC 才可用。

## 请求/响应帧
- 请求：`{ type: "req", id: <uuid>, method, params }`
- 响应：`{ type: "res", id, ok, payload | error }`
- 事件：`{ type: "event", event, payload }`
- `agent` 长任务：先回 `payload.status === "accepted"`，最终响应才是 final；expectFinal 模式要跳过 accepted、等 final。

## 设备签名认证（拿 operator.write）
- 文件：
  - `~/.openclaw-autoclaw/identity/device.json`：`deviceId` / `privateKeyPem` / `publicKeyPem`
  - `~/.openclaw-autoclaw/identity/device-auth.json`：`tokens.operator.token` + `scopes`
- payload 格式：`v3|<deviceId>|<clientId>|<clientMode>|operator|<scopes,>|<signedAtMs>|<token>|<nonce>|<platform>|<deviceFamily>`
- `params.device = { id, publicKey: <Ed25519 公钥 JWK x base64url>, signature: <payload 的 Ed25519 签名 base64url>, signedAt, nonce }`
- `params.auth = { token: <operator token>, deviceToken: <operator token> }`
- 必须带 `operator.write` 等 scopes，否则 connect 失败。

## 已验证 RPC
- `connect`：握手 + scopes
- `agent`：`{ message, agentId, idempotencyKey, timeout, sessionKey?, thinking? }`
- `sessions.list`：`{ limit, includeDerivedTitles, includeLastMessage }`
- `agents.list`：`{}`

## 已知限制
- gateway token（`.gateway-token`）scope 不够；桥接用设备 operator token。
- 独立 gateway 没有 AutoClaw 注入的模型 API key，`agent` 会返回 `HTTP 401 Invalid API Key`；协议链路已验证。
- 两个 gateway 不能同时监听 18789；先探测再决定是否独立启动。