# 洞听播放器 - 实施计划

## 阶段 1：项目骨架
- 新建 Android Gradle 工程。
- 配置 applicationId、minSdk、targetSdk、依赖。
- 增加 README。

## 阶段 2：媒体播放核心
- MainActivity 初始化 ExoPlayer、PlayerView、播放队列。
- 实现打开文件夹、递归扫描、自然排序、播放、上一首/下一首。
- 实现倍速、音量、循环模式、播放位置记忆。

## 阶段 3：列表与 AB 循环
- 新建/选择播放列表。
- 添加扫描结果到列表。
- 按列表播放。
- 设置/清除 AB 点并持久化。

## 阶段 4：增强与文本朗读
- LoudnessEnhancer 增益控制。
- TTS 导入 txt、语音选择、播放/停止。
- 背景音乐选择和独立播放器音量。

## 阶段 5：验证
- 生成 Gradle wrapper。
- 执行 `gradlew assembleDebug`。
- 修复编译错误。
