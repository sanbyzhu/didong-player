# Contributing

感谢你愿意改进地洞播放器。

## 提交 Issue

请尽量提供：

- 问题现象和预期行为。
- Android 版本、设备型号和应用版本。
- 可复现步骤。
- 相关日志或截图。

## 提交代码

1. Fork 本仓库。
2. 从 `main` 创建功能分支。
3. 保持改动聚焦，避免混入无关格式化。
4. 本地运行构建：

```powershell
.\gradlew.bat assembleDebug
```

5. 提交 Pull Request，并说明改动内容和验证结果。

## 代码风格

- Android 端当前以 Java 为主。
- 尽量沿用现有项目结构和命名。
- 不要提交本机配置、构建产物、签名证书或密钥。

## 许可证

除非另有说明，你提交的贡献将按 Apache License 2.0 授权。
