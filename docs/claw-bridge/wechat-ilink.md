# 微信 iLink 协议要点（来自 openclaw-weixin README.zh_CN.md）

- 基础 URL：https://ilinkai.weixin.qq.com
- 通用头：AuthorizationType=ilink_bot_token；Authorization=Bearer <token>；X-WECHAT-UIN=随机 uint32 的 base64
- 接口（POST JSON）：
  - getupdates：长轮询，body { get_updates_buf }，返回 msgs + 新游标
  - sendmessage：body { msg: { to_user_id, context_token, item_list } }
  - getuploadurl / getconfig / sendtyping
- 消息：WeixinMessage（seq/message_id/from/to/context_token/item_list）；MessageItem type 1-5（text/image/voice/file/video）
- CDN 媒体：AES-128-ECB 加密，encrypt_query_param + aes_key
- 完整协议看插件源码 src/api/types.ts、src/api/api.ts