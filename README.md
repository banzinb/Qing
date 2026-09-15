# 青 Qing

[English](README_en.md) | **中文**

青是一个装完就有用的 Android 中文技能 Agent：单 APK、自带完整 Linux 运行时、开箱即用，把日常任务做成看得见结果的工作流；还能开浏览器、替你操作手机。

## 特点

- 单 APK 自带内嵌 Termux（proot + bash/apt/python），无需 root，无需额外安装
- 预置中文技能：记账、待办、剪藏、网页提炼、PPT 生成，技能结果以图表/统计/清单卡片直接呈现
- 本地记忆库：账单/待办/剪藏/偏好自动沉淀，支持优先级、时间衰减与自动上下文注入
- 主动推送（Presence）：按活跃时段与勿扰规则，定时把记忆摘要推给你，不消耗模型 token
- 离线 ECharts 结果卡片：数据总览与技能结果可视化，不需要网络
- 青自己开浏览器，你能看着它浏览：内嵌 WebView 池、最多三个标签、地址栏可手动输、标签可切、
  随时能停；不用装 Alpine 或 Chromium
- 手机自动化（无障碍）：读当前屏幕、按文字或控件标识找控件、点击、填中文、滚动、等某个东西
  出现或消失，还有返回 / 主页 / 多任务
- 一个工具 17 个设备动作：设备/电池/存储与屏幕信息、打开链接或 App、天气、定位、联系人、
  日历、闹钟与计时器、剪贴板、朗读、相册列图并把某张拉进工作区真看起来
- 危险工具审批门 + 审计记录；用量、上下文预算与崩溃自检页
- 完成提醒走高优先级通道，另有「干完活自动回到 青」开关（默认关，因为它会打断你手上的事）
- 兼容 Pi 扩展生态：支持标准 Pi 扩展、MCP 适配器与子 Agent 扩展
- 可选 Shizuku / Termux 主机能力，为后续手机操控预留底座
- 基于 Aether（GPL-3.0）构建，保留上游扩展体系与交互打磨

## 快速开始

1. 安装 APK（见下面「安装」），按引导配置模型服务商（DeepSeek、OpenAI 兼容端点等）
2. 直接说「记一笔账」「加个待办」「剪藏这个网页」，青会调用技能并产出结果卡片
3. 想让它操作手机：「帮我打开设置里的 WiFi 页面」——先在 设置 → 无障碍 → 「青 · 屏幕操作」里开启
4. 想让它查东西：「查一下明天北京天气，再放首歌」
5. 想让它上网：「打开浏览器搜一下 …」，浏览时消息下方会出现「正在浏览」卡片，点开就是同一个
   浏览器窗口，你能看着、也能接手

相册、联系人、日历、定位这几个动作第一次用会要对应的 Android 权限。

## 安装

到 [Releases](https://github.com/banzinb/Qing/releases) 下载最新的 `qing-*-release.apk` 安装。
系统会提示「来自未知来源」——这是正常现象。

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
