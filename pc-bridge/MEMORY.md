# pc-bridge 项目记忆

记录时间：2026-08-03

## 别踩的雷区

1. 桌面 App IPC **没有** `thread-follower-start-turn` 这个方法。正确续跑/发消息是 `turn/start`，参数必须带 `threadId` 和 `input: [{ "type": "text", "text": "..." }]`。上次就是发错方法，导致 `019fb212` 会话在桌面端一点开就报错。
2. `codex exec resume` 会继承目标会话的沙箱。如果会话是 `read-only`/受限权限，续跑后工具不可用、会话会空转写文件烧 token。现在 pc-bridge 会先扫描会话沙箱，受限会话直接拒绝续跑。
3. `/api/exec` 必须显式传 `-s danger-full-access`（现在默认已做），否则 CLI 可能默认 `read-only`。
4. 不要手改 `019fb212` 的会话文件；桌面端报错在重启 Codex App 后已恢复。

## 现状

- pc-bridge 恢复保护已上线（健康检查返回 `resumeGuard: true`）。
- 备份位置：
  - 改动前：`D:\document\backups\step7-pc-bridge-done-20260802\pc-bridge`
  - 改动后：`D:\document\backups\step8-pc-resume-guard-20260803\pc-bridge`
- 以后动 pc-bridge 前先做备份，且不要在桌面 IPC 上试错。
