# 青 Qing

青是一个装完就有用的 Android 中文技能 Agent：单 APK、自带完整 Linux 运行时、开箱即用，把日常任务做成看得见结果的工作流。

## 特点

- 单 APK 自带内嵌 Termux（proot + bash/apt/python），无需 root，无需额外安装
- 预置中文技能：记账、待办、剪藏、网页提炼、PPT 生成，技能结果以图表/统计/清单卡片直接呈现
- 本地记忆库：账单/待办/剪藏/偏好自动沉淀，支持优先级、时间衰减与自动上下文注入
- 主动推送（Presence）：按活跃时段与勿扰规则，定时把记忆摘要推给你，不消耗模型 token
- 离线 ECharts 结果卡片：数据总览与技能结果可视化，不需要网络
- 兼容 Pi 扩展生态：支持标准 Pi 扩展、MCP 适配器与子 Agent 扩展
- 可选 Shizuku / Termux 主机能力，为后续手机操控预留底座
- 基于 Aether（GPL-3.0）构建，保留上游扩展体系与交互打磨

## 快速开始

1. 安装 APK
2. 按引导配置模型服务商（DeepSeek、OpenAI 兼容端点等）
3. 直接说「记一笔账」「加个待办」「剪藏这个网页」，青会调用技能并产出结果卡片

## 构建

需要 JDK 17+、Android SDK、NDK r28+，以及 Node.js（用于构建 pi-bridge）。

```bash
./gradlew :app:assembleDebug   # 调试包
./gradlew :app:assembleRelease # 正式包（需要配置签名）
```

本地运行时资产位于 `app/src/main/assets/runtimes/`，pi-bridge 在 `pi-bridge/`，PC 联动在 `pc-bridge/`。

## 项目来源

青由 Aether 派生而来，核心 Agent 执行、UI 与扩展体系继承上游：

- 上游：[Zhou-Shilin/Aether](https://github.com/Zhou-Shilin/Aether)
- 本仓库：[banzinb/Qing](https://github.com/banzinb/Qing)

## License

GPL-3.0。请保留上游 License 与 NOTICE。
