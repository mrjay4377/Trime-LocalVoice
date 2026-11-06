# Trime LocalVoice 分支说明

本分支 `localvoice` 是**可继续开发、可推送、可 clone 的功能分支**，不是源码快照。

> **当前状态（一次性限制）**：本机原工程是浅克隆（`--depth 1`），且网络代理拦截了
> `github.com` 的 git 协议，暂时拉不到上游完整历史。因此分支的第一个提交 `dd6a98c`
> 是一个**内容与上游基线完全一致的根提交**（tree `703cc6df` = `57feda7` 的 tree），
> 第二个提交才是 LocalVoice 全部改动。推送 / 克隆 / 继续开发都不受影响；
> 但**自动三方合并上游**需要先执行下面「接回上游历史」的一次性操作（无冲突、约 3 条命令）。

## 基线

| 项目 | 值 |
| --- | --- |
| 上游 | `https://github.com/osfans/trime.git`（remote 名 `upstream`） |
| 分支 | `develop` |
| 基线提交 | `57feda7007d2d2b7e71ecc99eaa38d8a4592a293`（2026-10-29） |
| 本分支根提交 | `dd6a98c`（tree 与上面的基线提交相同） |
| 本仓 | `https://github.com/mrjay4377/Trime-LocalVoice.git`（remote 名 `origin`） |
| 功能分支 | `localvoice` |

仓库的 `main` 分支是早期的**源码快照 + v21 APK 存档**，没有上游历史，不要用它来合并上游；请始终使用 `localvoice`。

## 本分支改动范围

- `app/src/main/java/com/osfans/trime/ime/voice/`：本地离线 ASR 全套（sherpa-onnx 反射封装、录音、模型目录 / 下载 / 安装 / ZIP 导入、ITN）
- `LocalAsrSettingsFragment.kt`、`VoiceIndicatorUi.kt`：设置页与候选栏语音标注 / 音量波形
- `KeyboardActionListener` / `KeyView` / `CommonKeyboardActionListener` / `InputBarDelegate` 链路：按住录音、松手结束
- `UserDataManager.kt`：**把 `voice/` 排除出 Rime 双向同步**（否则大模型会被同步拖慢或误删）
- `AndroidManifest.xml`（`RECORD_AUDIO` / `INTERNET`）、`proguard-rules.pro`（反射 keep）、三组 `strings.xml`、`ic_voice_mic.xml`
- `app/build.gradle.kts`、`gradle.properties`、`gradle-wrapper.properties`：debug 开启 R8 / 资源收缩、只打 arm64、应用名用正式名、排除多余 sherpa so

语音模型固定放在应用私有目录：`/sdcard/Android/data/com.osfans.trime.debug/files/rime/voice/`，不随「使用者资料夹」设置变化。

## 在另一台电脑上继续开发

```bash
git clone -b localvoice https://github.com/mrjay4377/Trime-LocalVoice.git trime
cd trime
git remote add upstream https://github.com/osfans/trime.git
git submodule update --init --recursive
```

还需手动补齐**未入库的第三方依赖**（体积过大或属第三方源码，构建时重新获取）：

- `app/libs/sherpa-onnx-2.0.1.aar` —— `app/build.gradle.kts` 直接引用，缺了无法编译
- `app/src/main/jni/boost/`、`app/src/main/jni/librime*/`、`app/src/main/jni/snappy/` —— 由 submodule / 官方构建脚本获取
- `boost-1.92.0.tar.xz`、Android SDK / NDK / JDK、Gradle 9.8.1

编译：

```bash
./gradlew :app:assembleDebug
```

默认 `buildABI=arm64-v8a`；需要多架构用 `-PbuildABI=arm64-v8a,armeabi-v7a,x86,x86_64` 覆盖。

## 一次性：接回真正的上游历史（建议尽快做）

在能正常访问 `github.com` git 协议的电脑上（本机开启可用代理后也可以）执行：

```bash
git fetch --unshallow upstream develop
git rebase --onto 57feda7007d2d2b7e71ecc99eaa38d8a4592a293 \
  $(git rev-list --max-parents=0 HEAD) localvoice
git push -f origin localvoice
```

根提交的 tree 与上游 `57feda7` 完全相同，所以 rebase 会干净地把功能提交挂到真正的
上游基线上，**全程无冲突**。完成后本分支就是标准 fork，之后所有上游更新都能自动三方合并。

> 若代理持续拦截 git 协议，把仓库 clone 到能直连 GitHub 的机器上执行上面三步即可，
> 不需要重做任何代码改动。

## 接回后：Trime 主体代码更新（内核/上游合并）

```bash
git fetch upstream
git merge upstream/develop          # 或 git rebase upstream/develop
# 解决冲突：只会出现在我们改过的 Kotlin / XML / gradle 文件上
git push origin localvoice
```

因为存在共同祖先，三方合并可正常工作；冲突面只限我们改过的约 27 个文件。

## 只更新 librime native 内核

```bash
cd app/src/main/jni/librime
git fetch --tags && git checkout <new-tag>
cd ../../../../..
git add app/src/main/jni/librime
git commit -m "chore: bump librime"
git push origin localvoice
```

这种情况 Java/Kotlin 层基本不冲突，只需重新编译 native。

## 不要提交的本地改动

本机（Windows）工作区里还有一批**构建环境适配改动，刻意没有纳入提交**：

- `app/data/rime/*`、`app/src/main/assets/shared/*`：clone 时 `core.symlinks=false` 导致 symlink 被实体化成普通文件（约 60 万行）
- `app/src/main/jni/librime-lua-deps`：submodule 内部 dirty
- `.gradle-home/`、`compile_log.txt`、`run_compile.sh`、`app/src/main/jni/boost-*.tar.xz`

它们只影响本机编译，提交会污染 fork 并在合并上游时造成大面积冲突。Windows 上建议开启开发者模式后 `git config core.symlinks true` 并重新 checkout，让 symlink 恢复正常。
