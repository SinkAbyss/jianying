# 开源副本验证

日期：2026-10-10，Asia/Shanghai。

来源版本：0.1.38 / code39。源提交、逐文件 SHA-256 和开源副本的资源 / 名称差异见[来源清单](source-manifest.json)。

本轮在 Windows、JDK 25.0.3、Android SDK Platform 37 环境，对替换示例资源后的公开副本执行 `:app:assembleDebug` 以及以下三组 `:app:testDebugUnitTest`：

| 检查 | 执行 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| TextRulesLimitTest | 6 | 0 | 0 | 0 |
| BatchSaverTest | 6 | 0 | 0 | 0 |
| UserMaterialStoreTest | 8 | 0 | 0 | 0 |
| 合计 | 20 | 0 | 0 | 0 |

Debug 构建成功；计数取最终 JUnit XML，历史安装包与设备检查不作为本次公开副本的测试结果。开源副本文档链接检查 4 个文档 / 12 个本地链接，缺失 0；公开文件逐项 SHA-256 核对通过。未运行全套、lint 或设备检查。

## 需要本地素材的历史检查

部分 `FrameRendererTest`、`FrameBackgroundSampleTest`、`R1*`、`R2VisualFeedbackTest`、`VivoForegroundTest` 等检查需要 `design/` 或 `artifacts/` 中的历史照片 / 清单；字体检查需要对应开放字体夹具，照片市场名检查含仅本地保留的用户原图。缺失素材时既有检查可能失败或跳过。开源不公开这些私人素材，也不声称全套视觉回归可仅靠克隆复现。

## 设备与视觉边界

本次开源不安装应用、不更改手机数据。不同设备的大图内存、手机文件选择 / ZIP 备份恢复操作、原创示例的手机视觉效果均未在本轮验证。
