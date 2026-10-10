# 简影 · Jianying

一个离线 Android 照片水印应用，使用 Kotlin、Jetpack Compose 和 Android Canvas。

面向日常照片出片：添加艺术签名或个性边框，保留照片比例，另存成片，不覆盖原图。支持 Android 10（API 29）及以上。

## 功能

- **两种水印**：照片内艺术签名；底部加条或四周留边的个性边框。
- **五种边框背景**：白色、黑色、浅色自适应、深色自适应、模糊。
- **逐图编辑**：跨相册多选、照片缩略图、逐图独立样式、单张与批量另存。
- **摄影信息**：读取 EXIF 参数、日期与机型；支持手动编辑、信息开关与个性短句。地点不联网反查。
- **素材库**：文字签名、手写签名、图片提取、圆图 / Logo，以及 TTF / OTF 字体导入与删除。
- **可复用配方**：分别保存艺术签名与边框设置。
- **数据管理**：素材、字体和配方的 ZIP 备份、合并导入与完整恢复。

应用没有网络权限或服务端依赖。导出 JPEG 质量 96，保存到 `Pictures/简影边框/`；导出不承诺保留 EXIF。内存分配失败时询问是否降采样，不保证所有设备均能导出任意大图。

## 构建

工程版本：`0.1.38` / `versionCode 39`，应用 ID：`com.sinkabyss.framewatermark`。

需要 JDK 17 或更高版本、Android SDK Platform 37 与 Build Tools 36。配置 `ANDROID_HOME` 或工程本地的 `android/local.properties`，不要提交本机 SDK 路径。Gradle Wrapper、Android Gradle Plugin 与依赖版本已在工程中固定。

Windows PowerShell：

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

Linux / macOS：

```bash
cd android
chmod +x gradlew
./gradlew :app:assembleDebug
```

APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。这是开发用 Debug 构建；发布自己的安装包时应使用自己的签名配置，并保管好签名密钥。

## 验证与贡献

不依赖个人照片的检查示例：

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest --tests com.sinkabyss.framewatermark.TextRulesLimitTest --tests com.sinkabyss.framewatermark.BatchSaverTest --tests com.sinkabyss.framewatermark.UserMaterialStoreTest
```

历史视觉检查依赖本地照片、字体及校准记录，这些文件不随开源仓库分发。直接运行全部测试可能因缺失夹具失败或跳过；不能把缺失素材的结果当作完整视觉验证。[验证说明](docs/validation.md)记录开源副本的实测边界。

欢迎通过 GitHub Issues 报告问题或提交 Pull Request。请注明应用版本、Android 版本、复现步骤和预期结果；分享照片或日志前移除私人信息。

## 源码与素材

`android/` 包含应用和测试；`tools/` 包含文档链接检查及原创几何示例生成脚本。首次开源从本地版本建立独立历史，未公开私人照片、开发设备日志、参考包或反编译资料。

七个预置示例已换成原创几何素材，演示风景沿用官方标为 Public Domain 的照片；因此预置素材外观与历史本地安装包不同，应用逻辑仅调整一个预置名称。[素材及第三方说明](THIRD_PARTY_NOTICES.md)记录出处与许可边界，[来源清单](docs/source-manifest.json)记录源提交、公开文件 SHA-256 和导出改动。

## 许可证

项目原创源码、文档和几何示例采用 [MIT License](LICENSE)。第三方照片、设备名称数据和构建组件按其各自来源与许可处理，见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。
