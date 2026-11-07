/*
 * 可下载的离线语音模型目录。
 * 规格（包名 / 文件大小 / SHA-256）与下载源移植自 trime-mie / BiBi-Keyboard 的
 * LocalAsrModels（Apache-2.0）；模型包托管在 BiBi-Keyboard 的 models release。
 * 这里只描述模型，不做任何联网操作；识别仍然完全在设备本地进行。
 */
package com.osfans.trime.ime.voice

data class ModelFileSpec(val name: String, val sizeBytes: Long, val sha256: String)

data class ModelVariant(
    val familyId: String,
    val key: String,
    val label: String,
    val zipName: String,
    val downloadMiB: Int,
    val files: List<ModelFileSpec>,
) {
    /** 设置页列表使用的复合值：家族 / 变体。 */
    val id: String get() = "$familyId/$key"

    val familyLabel: String
        get() = VoiceModels.family(familyId)?.label?.substringBefore("（") ?: familyId

    val displayName: String get() = "$familyLabel · $label"
}

object ModelCatalog {
    private const val RELEASE_BASE =
        "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/"

    /**
     * 0 = 自动（镜像优先 → 官方兜底），1 = 官方，2..4 = 镜像。
     *
     * 国内网络下 GitHub 官方会 302 跳到 objects.githubusercontent.com，多数情况直接超时，
     * 因此自动模式把实测可用的镜像排在前，官方只作为最后兜底，避免每个模型都先白等 30 秒。
     * hub.gitmirror.com / github.moeyy.xyz / mirror.ghproxy.com 已实测失效，不再使用。
     */
    val SOURCES = listOf(
        "自动（镜像优先 → 官方兜底）",
        "GitHub 官方",
        "镜像 1 · ghproxy.net",
        "镜像 2 · ghfast.top",
        "镜像 3 · fastgit.cc",
    )

    private val PREFIXES = listOf(
        "",
        "",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://fastgit.cc/",
    )

    /** 自动模式额外尝试的备用镜像（实测可返回 206 分片）。 */
    private val EXTRA_MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.cn/",
    )

    private fun spec(name: String, size: Long, sha: String) = ModelFileSpec(name, size, sha)

    private val SENSE_VOICE_TOKENS =
        spec("tokens.txt", 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc")

    private val QWEN_TOKENIZER = listOf(
        spec("vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        spec("merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        spec("tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
    )

    val VARIANTS: List<ModelVariant> = listOf(
        ModelVariant(
            familyId = "sensevoice",
            key = "small-int8",
            label = "标准版 int8（中文推荐）",
            zipName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip",
            downloadMiB = 153,
            files = listOf(
                SENSE_VOICE_TOKENS,
                spec("model.int8.onnx", 239_233_841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            ),
        ),
        ModelVariant(
            familyId = "sensevoice",
            key = "small-full",
            label = "高精度版 fp32",
            zipName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.zip",
            downloadMiB = 828,
            files = listOf(
                SENSE_VOICE_TOKENS,
                spec("model.onnx", 937_617_178L, "977016bd9c79f9eb343430b5cc305e07ab64d5212dff41b0dcfa1694bee9a8cb"),
            ),
        ),
        ModelVariant(
            familyId = "qwen3_asr",
            key = "qwen3-0.6b-int8",
            label = "0.6B int8（中文准确）",
            zipName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.zip",
            downloadMiB = 806,
            files = listOf(
                spec("conv_frontend.onnx", 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
                spec("encoder.int8.onnx", 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
                spec("decoder.int8.onnx", 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
            ) + QWEN_TOKENIZER,
        ),
        ModelVariant(
            familyId = "qwen3_asr",
            key = "qwen3-1.7b-int8",
            label = "1.7B int8（大模型，需充足内存）",
            zipName = "sherpa-onnx-qwen3-asr-1.7B-int8-2026-08-04.zip",
            downloadMiB = 1970,
            files = listOf(
                spec("conv_frontend.onnx", 48_080_441L, "3cb27a9fe94d95c938e476f2012b21aba2ec0bfceef33b0e58acd208946bafdd"),
                spec("encoder.int8.onnx", 314_222_162L, "a5deedae034ece715de8ed204378d8c77f889af3a60c2566581135e84cced7cd"),
                spec("decoder.int8.onnx", 2_037_091_087L, "41eafdf582b124c5fb050a59ca3331a6ec62db0d5ce35d456e9f999f7e6ad042"),
            ) + QWEN_TOKENIZER,
        ),
        ModelVariant(
            familyId = "funasr_nano",
            key = "nano-int8",
            label = "Nano int8（中英）",
            zipName = "sherpa-onnx-funasr-nano-int8-2025-12-30.zip",
            downloadMiB = 691,
            files = listOf(
                spec("embedding.int8.onnx", 155_584_380L, "95e61cd0c9c3b9543339a4cf973c95c116815e745ccc1e0285cbd81f76d18644"),
                spec("encoder_adaptor.int8.onnx", 237_792_748L, "f36dea2e30fbc33b5db1d7a7265cc976c5e5586c77b042d5adb1ad27c72db422"),
                spec("llm.int8.onnx", 600_356_593L, "dfbf9aa3be41bccc257587f151e15c63fbe1b549f2b517f5ccd5bdce3bf4322a"),
                spec("tokenizer.json", 11_422_654L, "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"),
            ),
        ),
        ModelVariant(
            familyId = "funasr_nano",
            key = "mlt-int8",
            label = "MLT Nano int8（多语种）",
            zipName = "sherpa-onnx-funasr-mlt-nano-int8-2026-03-21.zip",
            downloadMiB = 722,
            files = listOf(
                spec("embedding.int8.onnx", 155_583_106L, "2eb6f47e9119b0e92cb38caea8d41505d446c7ee829a44f91619498a6ec7594b"),
                spec("encoder_adaptor.int8.onnx", 237_792_749L, "0ccd6c75ffe07d724921fd0787389479c3ae8d84e60f55508f43d87c0f02bc85"),
                spec("llm.int8.onnx", 600_356_593L, "2e54f5d0e7a7147df785a8318d8680a582678f95fb3dfbb5b9aa2d4e55e6ff6c"),
                spec("tokenizer.json", 11_422_654L, "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"),
            ),
        ),
        ModelVariant(
            familyId = "parakeet",
            key = "0.6b-v2-int8",
            label = "0.6B V2 int8（英语）",
            zipName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.zip",
            downloadMiB = 451,
            files = listOf(
                spec("tokens.txt", 9_384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
                spec("encoder.int8.onnx", 652_184_296L, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                spec("decoder.int8.onnx", 7_257_753L, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                spec("joiner.int8.onnx", 1_739_080L, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            ),
        ),
        ModelVariant(
            familyId = "parakeet",
            key = "0.6b-v3-int8",
            label = "0.6B V3 int8（欧洲语言）",
            zipName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.zip",
            downloadMiB = 456,
            files = listOf(
                spec("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
                spec("encoder.int8.onnx", 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                spec("decoder.int8.onnx", 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                spec("joiner.int8.onnx", 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            ),
        ),
        ModelVariant(
            familyId = "firered_asr",
            key = "ctc-int8",
            label = "FireRedASR V2 CTC int8（中英）",
            zipName = "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.zip",
            downloadMiB = 496,
            files = listOf(
                spec("tokens.txt", 79_172L, "1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b"),
                spec("model.int8.onnx", 775_861_420L, "ca3dbabd82170110cc0b343c2890866d449984bc9cd92b9a18371ff80a81bb99"),
            ),
        ),
        // TeleSpeech CTC：纯中文小模型，压缩包仅 180 MiB，是本机最省空间/内存的离线模型。
        // 文件名与解压后大小取自 release 包中央目录（实测）。上游未提供 SHA-256，
        // ModelInstaller 会按「同名 + 大小一致」降级校验，不影响安装与识别。
        ModelVariant(
            familyId = "telespeech",
            key = "ctc-int8",
            label = "TeleSpeech CTC int8（中文，体积最小）",
            zipName = "sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04.zip",
            downloadMiB = 180,
            files = listOf(
                spec("tokens.txt", 66_642L, ""),
                spec("model.int8.onnx", 340_990_949L, ""),
            ),
        ),
        // 原生流式 Transducer（X-ASR 480ms chunk）。包内文件名带 -480ms 后缀，
        // 大小取自 release 包中央目录实测；上游未提供 SHA-256，按「同名 + 大小一致」校验。
        // 该包 encoder 为 fp32，故体积偏大；代价是按住语音键就能逐块出字，无需等松手。
        ModelVariant(
            familyId = VoiceModels.STREAMING_FAMILY,
            key = "480ms-zh-en",
            label = "X-ASR 480ms（中英，边说边出字）",
            zipName = "sherpa-onnx-streaming-x-asr-480ms-zh-en.zip",
            downloadMiB = 529,
            files = listOf(
                spec("tokens.txt", 58_806L, ""),
                spec("encoder-480ms.onnx", 592_968_361L, ""),
                spec("decoder-480ms.onnx", 11_309_084L, ""),
                spec("joiner-480ms.onnx", 10_260_467L, ""),
            ),
        ),
    )

    /** 官方原始地址，供「手动下载」展示与复制。 */
    fun officialUrl(variant: ModelVariant): String = RELEASE_BASE + variant.zipName

    fun byId(id: String): ModelVariant? = VARIANTS.firstOrNull { it.id == id }

    fun variantsOf(familyId: String): List<ModelVariant> = VARIANTS.filter { it.familyId == familyId }

    /** 按下载源返回候选 URL；自动模式镜像优先、官方兜底，避免国内先卡在官方 302 上。 */
    fun candidateUrls(variant: ModelVariant, sourceIndex: Int): List<String> {
        val official = RELEASE_BASE + variant.zipName
        if (sourceIndex == 0) {
            val mirrors = (PREFIXES.drop(2) + EXTRA_MIRRORS).map { it + official }
            return mirrors + official
        }
        val prefix = PREFIXES.getOrElse(sourceIndex) { "" }
        return listOf(if (prefix.isEmpty()) official else prefix + official)
    }
}
