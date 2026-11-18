<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

# 同文输入法 · 本地语音版

基于 [Trime](https://github.com/osfans/trime) / [Rime](https://github.com/rime) 的 Android 输入法增强版。在完整保留方案、词库与 YAML 主题生态的基础上，加入**离线本地语音识别**、悬浮键盘与单手模式等能力。

> **全程以开源代码 + AI 编写**
> 本仓库全部代码均源自开源项目；本分支的每一行改动都在 AI 对话中编写、编译、重签与验证。源码完全公开。

## 特性

- **离线本地语音识别**：内置 sherpa-onnx 离线整段 / 原生流式双引擎，模型本地加载与推理，不联网上传音频
- **悬浮键盘**：透明 IME 窗口 + 触摸穿透，支持长按顶部整条或四向箭头把手拖动、下角条缩放；编码栏与软光标跟随卡片
- **单手键盘**：以屏幕短边为基准约束宽度，窄 / 标准 / 宽三档可调；横屏与宽屏设备自动降级为普通布局
- **模式图标**：工具栏在悬浮 / 单手开启时显示对应图标，点击即返回普通键盘（顺序：四向箭头把手 → 更多 → 模式图标）
- **Rime 生态**：方案、词库、YAML 主题等上游能力完整保留

## 下载与分支

| 项目 | 说明 |
| --- | --- |
| 最新签名 APK | [Releases](https://github.com/mrjay4377/Trime-LocalVoice/releases/latest)（arm64-v8a，debug 重签，可覆盖安装） |
| 默认分支 `localvoice` | 完整源码 + 上游历史（约 3100 个提交），始终以此分支为准 |
| `main` | 早期源码快照 + v21 APK 存档，无上游历史，仅作存档，不要用于合并上游 |

## 全程以开源代码 + AI 编写

| 项目 | 说明 |
| --- | --- |
| 上游开源代码 | [Trime](https://github.com/osfans/trime)、[Rime / librime](https://github.com/rime)、librime-lua、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 等，均遵循各自开源许可证 |
| 本分支改动 | 本地语音引擎接入与模型管理、悬浮键盘、单手模式、工具栏模式图标、编码栏跟随、简繁跟随、空闲卸载等，**全程由 AI 辅助编写** |
| 开发流程 | 需求 → 代码 → `assembleRelease` 编译 → zipalign / debug 重签 → 出包 → 提交同步，全流程在 AI 对话中完成并逐步验证 |
| 许可证 | GPL-3.0-or-later；源码公开，欢迎审阅、复现与二次开发 |

## 构建

需要 JDK 17、Android SDK（含 NDK / CMake）与 Gradle。

```bash
export JAVA_HOME=<path-to-jdk17>
export ANDROID_HOME=<path-to-android-sdk>
./gradlew :app:assembleRelease --no-daemon
python scripts/repack_apk.py <unsigned.apk> <signed.apk>   # zipalign + debug 重签 v1/v2/v3
```

> 注：子模块 `app/src/main/jni/librime-lua-deps` 指向本项目的 [fork](https://github.com/mrjay4377/librime-lua)（`trime-localvoice` 分支），内含一个 Android `minSdk 21` 缺少 `fseeko/ftello` 的编译兼容补丁；上游原仓库为 `hchunhui/librime-lua`。`git clone --recursive` 会自动取得该补丁，无需手动处理。

更完整的分支基线、改动清单与构建说明见 [LOCALVOICE.md](LOCALVOICE.md)。

## 鸣谢

本分支用到了下面这些开源项目、数据与模型，感谢作者们的工作。APK 内「设置 → 关于 → 开源许可证」列出了随包依赖的完整许可证；各方案词库自带的 NOTICE 与许可证以其目录内文件为准。

### 输入引擎

| 项目 | 作者 | 用在哪里 | 许可证 |
| --- | --- | --- | --- |
| [librime](https://github.com/rime/librime) | 佛振（lotem）及 Rime 社区 | 输入引擎核心 | BSD-3-Clause |
| [librime-lua](https://github.com/hchunhui/librime-lua) | hchunhui | 方案里的 Lua 脚本（本仓子模块指向带 Android 编译补丁的 [fork](https://github.com/mrjay4377/librime-lua)） | BSD-3-Clause |
| [OpenCC](https://github.com/BYVoid/OpenCC) | BYVoid（Carbo Kuo） | 简繁 / 台湾 / 香港字形转换 | Apache-2.0 |
| [Boost](https://www.boost.org/) / glog / yaml-cpp / LevelDB / marisa-trie / Snappy | 各自作者 | librime 上游依赖 | BSL-1.0、BSD-3-Clause、MIT 等（见各项目） |

### 离线本地语音

| 项目 | 作者 | 用在哪里 | 许可证 |
| --- | --- | --- | --- |
| [说点啥 BiBi Keyboard](https://github.com/BryceWG/BiBi-Keyboard) | BryceWG | 语音识别方法移植：引擎抽象、录音、模型目录、下载安装、ZIP 导入；离线模型包来源 | Apache-2.0 |
| [咩版 trime-mie](https://github.com/qxm888/trime-mie) / CatTrime | 各自作者 | 语音设置交互、模型规格与下载源；悬浮键盘缩放角条外观与手势 | Apache-2.0 / GPL-3.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | k2-fsa | 本地推理引擎；SenseVoice / Paraformer / Zipformer / X-ASR 等模型架构 | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Microsoft | sherpa-onnx 底层推理运行时（随 AAR 附带，1.28.2） | MIT |
| [Foxlet Translate](https://github.com/yinvoke/foxlet-translate) | yinvoke | 离线翻译；底层 Bergamot / Marian 与 Mozilla Firefox Translations 官方模型 | MIT / MPL-2.0 |

### 离线手写

| 项目 | 作者 | 用在哪里 | 许可证 |
| --- | --- | --- | --- |
| [ochwpro](https://github.com/ximeiorg/ochwpro) | Xime（ximeiorg） | StrokeTransformer 手写识别模型；叠写切分算法参考其 stroke_segmenter.py；模型经 [ModelScope](https://www.modelscope.cn/models/bikeand/ochwpro) 分发（INT8 约 2.1 MB） | MIT |
| [CyIME](https://github.com/Cyletix/CyIME) | Cyletix | 手写模型与字表共用来源之一（本实现未使用其公式手写模型） | 见各项目 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Microsoft | 手写推理运行时（独立 1.29.0 构建，与语音运行时版本隔离） | MIT |

### 应用与界面依赖

| 项目 | 作者 | 用在哪里 | 许可证 |
| --- | --- | --- | --- |
| [同文输入法 Trime](https://github.com/osfans/trime) | osfans 及 Rime 社区 | 本分支基线：Android 输入法框架、主题与候选栏 | GPL-3.0-or-later |
| AndroidX / Kotlin / Material 等 | Google / JetBrains 等 | 界面、协程、序列化、Room、WorkManager 等 | Apache-2.0 |

本分支本身以 GPL-3.0-or-later 开源，源码见默认分支 `localvoice`。上游原版 README 保留在 [README_upstream.md](README_upstream.md)，中文版本见 [README_sc.md](README_sc.md) 与 [README_tc.md](README_tc.md)。

> 说明：CyIME 提到的 `app/src/main/assets/NOTICE.txt` 与 `app/src/main/assets/licenses/` 在本仓库中不存在；本仓库随包依赖的许可证全文以 APK 内「设置 → 关于 → 开源许可证」页（AboutLibraries 生成）为准，方案词库的版权声明以各自目录内文件为准。
