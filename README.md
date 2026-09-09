# Qing

Qing is a ready-to-use Chinese mobile agent for Android. One APK, a bundled Linux runtime, and skills that produce visible results.

## Highlights

- Embedded Termux (proot + bash/apt/python) in a single APK; no root required
- Bundled Chinese skills: bookkeeping, todos, clips, web summarization, PPT generation
- Local memory with priority, time decay, and automatic context injection
- Active pushes (Presence) with active/quiet hours, no model tokens spent
- Offline ECharts result cards and a data overview screen
- Pi extension ecosystem compatible
- Optional Shizuku / Termux host capabilities
- Built on Aether (GPL-3.0)

## Quick start

Install the APK, configure a model provider, then try "记一笔账" or "加个待办".

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
