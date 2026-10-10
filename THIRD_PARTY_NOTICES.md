# 素材与第三方说明

MIT 许可证覆盖本项目原创部分，不改变下列第三方材料的权利或许可证。

## 内置风景照片

- 文件：`android/app/src/main/res/drawable-nodpi/help_landscape.jpg` 和 `landscape_demo.jpg`。
- 作者署名：Hagerty, Ryan / U.S. Fish & Wildlife Service。
- 来源：[Lake McDonald, MT](https://www.fws.gov/media/lake-mcdonald-mt)。该官方页面在 2026-10-10 核对时明确标为 **Public Domain**。
- [原始 JPEG](https://www.fws.gov/sites/default/files/images/2008-10/6423.jpg)：1200×900，SHA-256 `2af23d695096c133741d48ce899108f6bbc282a5efd30f1d4122a7ea295abfcd`。
- 应用 JPEG：原件于本地开发阶段去除 EXIF 并重编码，未裁剪或缩放；SHA-256 `0fdbaf5da5ed497bdafcbec1b93a6304c1233ae423354d0c4c1629f091448519`。两个公开资源采用相同字节。

## 原创几何示例

`circle_a.png`、`circle_b.png`、`circle_c.png` 和 `sig_a.png` 至 `sig_d.png` 由[生成脚本](tools/generate-open-source-assets.ps1)绘制，使用圆、线、矩形和贝塞尔曲线，不使用原参考照片、厂商 Logo、签名图或字体。原创脚本及生成结果采用项目 MIT 许可证。

重新生成需 Windows PowerShell 与 System.Drawing：

```powershell
.\tools\generate-open-source-assets.ps1 -OutputDirectory android/app/src/main/res/drawable-nodpi
```

## 离线设备名称数据

`android/app/src/main/res/raw/device_market_names.tsv` 是从 [Google Play 官方支持设备列表](https://storage.googleapis.com/play_public/supported_devices.csv)提取的机型代号与市场名称对应事实。

来源获取时间为 2026-10-07；原 CSV SHA-256 `d5d64c0e531f99081378a17124e96527b29892c2cd8f1f4cf9f952f4ed7c1332`。映射只保留大小写无关的 Model 唯一对应且非空、非代号本身的 Marketing Name，共 23,181 项；原本地 TSV SHA-256 `b75a45c4cb6ae4a09c51739832e5b3850a18a223fe8f7568304dcbbc86c7001e`。公开副本统一为 LF 换行，TSV SHA-256 `aab20993a7fcd033878068141a92fe7df919222c054b8e440044612c1207f3c5`，字段及条目保持。品牌及产品名归各自权利人所有，此表不表明合作或背书，也不将第三方数据主张为项目原创 MIT 内容。

## 构建工具和依赖

- Gradle Wrapper：[Gradle](https://github.com/gradle/gradle)，Apache-2.0；附带[许可证](licenses/gradle-LICENSE.txt)及[NOTICE](licenses/gradle-NOTICE.txt)，保留 Wrapper 脚本内版权声明。
- Kotlin / Compose 编译插件：[Kotlin](https://github.com/JetBrains/kotlin)，Apache-2.0。
- Android Gradle Plugin / AndroidX：[Android 开源项目](https://source.android.com/docs/setup/about/licenses)、[AndroidX](https://github.com/androidx/androidx)，依相应组件许可证。
- JUnit 4：[JUnit](https://github.com/junit-team/junit4)，EPL-1.0。
- Robolectric：[Robolectric](https://github.com/robolectric/robolectric)，MIT。

依赖由 Gradle 获取，源码仓库不重新分发它们的完整实现。自行分发 APK 时，请核对包括传递依赖在内的实际组件并保留适用许可声明。
