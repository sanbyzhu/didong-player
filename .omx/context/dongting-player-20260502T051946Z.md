# 洞听播放器 - 上下文快照

## Task statement
开发一个名为“洞听播放器”的本地音乐/视频播放器。安卓优先，后续支持 Windows；可播放多种音频和视频，扫描文件夹，按文件夹或自建列表播放，支持最高 8 倍速、播放记忆、文件夹级默认倍速、音量增强、立体感/质量增强、单曲循环、列表循环、AB 循环记忆、导入 txt 文本朗读、朗读时播放背景音乐。二期考虑荣耀投影仪无线投屏，手机端保留控制。

## Desired outcome
当前仓库从空目录变成可构建的 Android 工程，提供第一版可运行 App，覆盖核心本地播放和朗读功能，并保留 Windows/投屏后续路线。

## Known facts/evidence
- 仓库路径：`F:\dongting`，初始几乎为空。
- Android SDK 存在于 `C:\Users\Administrator\AppData\Local\Android\Sdk`。
- 本机有 Gradle 8.14.3 分发和 Android Gradle Plugin 缓存。
- Java 17/21 可用。
- 官方 Android 文档建议使用 AndroidX Media3/ExoPlayer 构建媒体播放；Media3 支持播放队列、倍速、重复模式等 Player 能力。
- Android TextToSpeech API 支持系统 TTS 引擎和语音选择。

## Constraints
- 遵守 `AGENTS.md` 引用的 `RTK.md`：shell 命令需以 `rtk` 开头。
- 先做安卓 App，Windows 先作为后续规划。
- 不直接复制 GitHub 项目代码；以官方 Media3 架构和开源播放器常见功能为参考自行实现，避免许可和耦合问题。

## Unknowns/open questions
- 用户手机 Android 版本、荣耀投影仪型号、是否支持 Miracast/Google Cast/DLNA 未知。
- 用户希望内置的“高质量人声”具体供应商未知；首版使用系统 TTS 语音列表，后续可接云端/离线高质量语音包。
- Windows 端是希望原生桌面、Electron、KMP/Compose Desktop 还是 Flutter 未定。

## Likely codebase touchpoints
- 新建 Gradle Android 工程。
- `app/src/main/java/com/dongting/player/MainActivity.java`：主界面、播放控制、扫描、列表、TTS。
- `README.md`：开发计划、构建方式、二期路线。
