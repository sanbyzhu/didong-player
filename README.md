# 洞听播放器

洞听播放器是一个安卓优先的本地音频/视频播放器。当前版本使用 AndroidX Media3/ExoPlayer 作为播放核心，支持扫描本地文件夹、按数字/文件名排序、文件夹播放、自建列表、最高 8 倍速、播放位置记忆、文件夹级音频/视频独立倍速记忆、单曲循环、列表循环、AB 循环记忆、音量增益、TXT 文本朗读和朗读背景音乐。

## 当前已实现

- 扫描 SAF 文件夹，递归识别常见音频/视频格式。
- 按文件名自然排序，文件名包含数字时优先按数字排序。
- 播放音频和视频，视频在内置画面区域播放。
- 播放/暂停、上一首、下一首。
- 0.25x 到 8.0x 倍速，按文件夹和媒体类型分别记忆默认倍速。
- 播放位置记忆。
- 单曲循环、列表循环。
- AB 循环，按媒体文件记忆。
- 自建播放列表，把当前扫描结果加入列表。
- 播放音量和 LoudnessEnhancer 增益，增强声音但不修改源文件。
- 导入 txt 文本，使用系统 TextToSpeech 语音朗读。
- 选择朗读背景音乐并独立调节背景音量。

## 构建

```powershell
.\gradlew.bat assembleDebug
```

生成的 APK 位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 参考方向

实现参考了 GitHub 上常见 Android 音乐/视频播放器的功能组织方式，并采用 Android 官方推荐的 Media3/ExoPlayer 播放栈。没有直接复制第三方项目代码，避免许可证、架构耦合和后续维护问题。

## 后续路线

1. 拆分架构：把当前 MainActivity 中的数据、播放控制、TTS、列表管理拆成独立类。
2. 数据库：从 SharedPreferences/JSON 迁移到 Room，支持更大的媒体库和更稳定的查询。
3. 高质量人声：接入可授权的离线语音包或云端 TTS，提供更自然的普通话/英文人声。
4. Windows：优先评估 Kotlin Multiplatform + Compose Desktop 或 Flutter，复用媒体库数据结构。
5. 投屏：根据设备能力接入 MediaRouter、Google Cast、DLNA 或 Miracast 路径；功能目标包括“只投画面、声音留手机”和“音画都投影”，控制仍保留在手机端。
