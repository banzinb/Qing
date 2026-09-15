# Qing

**English** | [中文](README.md)

Qing is a ready-to-use Chinese mobile agent for Android. One APK, a bundled Linux runtime, skills that produce visible results — and a browser plus a phone it can actually operate.

## Highlights

- Embedded Termux (proot + bash/apt/python) in a single APK; no root required
- Bundled Chinese skills: bookkeeping, todos, clips, web summarization, PPT generation
- Local memory with priority, time decay, and automatic context injection
- Active pushes (Presence) with active/quiet hours, no model tokens spent
- Offline ECharts result cards and a data overview screen
- A driven browser with a window you can watch: one shared WebView pool, up to three tabs, an address
  bar, tab switching and stop. Take over whenever you like — no Alpine or Chromium download needed
- Phone automation through Android accessibility: read the screen, find a control by its text or id,
  tap, type Chinese, scroll, wait for something to appear or go away, and back / home / recents
- 17 device actions in one tool: device, battery, storage and screen info; open a URL or an app;
  weather; location; contacts; the calendar; alarms and timers; the clipboard; text-to-speech;
  list photos and pull one into the workspace so it can really be looked at
- An approval gate plus an audit log for sensitive tools, and a self-check screen for usage,
  context budget and crashes
- Completion alerts on a high-priority channel, and an optional "bring Qing back to the front when a
  run finishes" switch (off by default)
- Pi extension ecosystem compatible
- Optional Shizuku / Termux host capabilities
- Built on Aether (GPL-3.0)

## Quick start

Install the APK, configure a model provider, then try:

- 「记一笔账」 or 「加个待办」 — skills that end in a result card
- 「帮我打开设置里的 WiFi 页面」 — accessibility phone automation. Switch it on first in
  Settings → Accessibility → "Qing screen control"
- 「查一下明天北京天气，再放首歌」 — device actions
- 「打开浏览器搜一下 …」 — the agent browses in the embedded browser; a "browsing" card appears
  under the message and opens the visible window on tap

Photos, contacts, calendar and location ask for the matching Android permission the first time.

## Install

Download the newest `qing-*-release.apk` from [Releases](https://github.com/banzinb/Qing/releases)
and install it. Android will warn that the app comes from outside a store — that is expected.

## Build

JDK 17+, Android SDK, NDK r28+, and Node.js are required.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## Credits

Qing is forked from [Zhou-Shilin/Aether](https://github.com/Zhou-Shilin/Aether).
Upstream: <https://github.com/Zhou-Shilin/Aether>

## License

GPL-3.0. Keep upstream License and NOTICE files.
