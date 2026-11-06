# Trime LocalVoice

> **开发、编译、合并 Trime 上游更新，请一律使用 `localvoice` 分支。**
> `main` 只是 2026-11-05 的源码快照存档（含 v21 APK），没有上游 git 历史，不能用来做内核合并。

## 分支

| 分支 | 用途 |
| --- | --- |
| `localvoice` | **标准 fork 分支**：基于 `osfans/trime` `develop` 的 `57feda7`，带完整上游历史（约 3100 个提交），可直接 `git merge` / `git rebase` 上游 `develop` |
| `main` | 早期源码快照 + `apk/Trime-LocalVoice-v21-arm64-v8a.apk` 存档 |

`localvoice` 与上游存在共同祖先（`git merge-base` = `57feda7`），Trime 更新后执行
`git fetch upstream && git merge upstream/develop` 即可，冲突只出现在我们改过的约 27 个文件上。

## 快速开始

```bash
git clone -b localvoice https://github.com/mrjay4377/Trime-LocalVoice.git trime
cd trime
git remote add upstream https://github.com/osfans/trime.git
git submodule update --init --recursive
```

完整的基线信息、未入库的第三方依赖清单（sherpa-onnx AAR、Boost、librime submodules）、
编译命令、上游合并流程、librime 内核更新方法，见 `localvoice` 分支内的 **`LOCALVOICE.md`**。

## 功能

- 本地离线语音输入（sherpa-onnx），按住语音键录音、松手结束并上屏
- 候选栏语音标注与实时音量波形
- 模型列表、下载并自动安装、本地 ZIP 导入、ITN、手动下载地址复制
- 模型固定放在应用私有 `rime/voice/`，且不参与 Rime 使用者资料夹双向同步
- debug 包开启 R8 / 资源收缩、只打 arm64，应用名为正式名

## 注意

`main` 的 `.gitignore` 排除了体积较大的第三方 native 依赖与构建产物，因此快照**不能直接编译**；
`localvoice` 分支是完整源码，但同样需要在新机器上补齐 `app/libs/sherpa-onnx-2.0.1.aar`
与 `jni/` 下的 submodules 后才能构建。
