/*
 * 语音输入后的离线翻译：Foxlet Translate 0.5.0（Bergamot / Marian + Mozilla Firefox
 * Translations 模型，MIT / MPL-2.0）。思路与说点啥 v4.6.0 的离线翻译一致，但只保留
 * 纯离线链路：模型首次使用前联网下载一次，之后翻译全程在设备本地完成。
 *
 * AAR 声明 minSdk 28 且只有 arm64 native 库，宿主 minSdk 21 仍保持兼容：
 * 只引 classes.jar 与 arm64 .so，运行时通过 isAvailable() 做门槛判断。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Build
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.InstalledModel
import io.github.yinvoker.foxlet.LanguagePair
import io.github.yinvoker.foxlet.Threading
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

object OfflineTranslator {
    private const val SRC_ZH = "zh-Hans"
    private const val PIVOT = "en"

    val DIRECTION_VALUES = arrayOf("auto", "zh_en", "en_zh", "zh_ja", "zh_ko", "zh_fr", "zh_de", "zh_es", "zh_ru")
    val DIRECTION_LABELS = arrayOf(
        "自动（中文 ⇄ 英文）",
        "中文 → 英文",
        "英文 → 中文",
        "中文 → 日语（经英文中转）",
        "中文 → 韩语（经英文中转）",
        "中文 → 法语（经英文中转）",
        "中文 → 德语（经英文中转）",
        "中文 → 西班牙语（经英文中转）",
        "中文 → 俄语（经英文中转）",
    )

    private val TARGET_LABELS = mapOf(
        "ja" to "日语",
        "ko" to "韩语",
        "fr" to "法语",
        "de" to "德语",
        "es" to "西班牙语",
        "ru" to "俄语",
    )

    /** Foxlet 每个进程只允许一个活动客户端；输入法服务内统一复用。 */
    @Volatile
    private var client: Foxlet? = null
    private val clientMutex = Mutex()

    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            runCatching { Class.forName("io.github.yinvoker.foxlet.Foxlet") }.isSuccess

    data class Chain(val pairs: List<LanguagePair>, val label: String)

    /** 中文到非英语语种走 pivot：zh-Hans → en → 目标语（Foxlet 原生支持）。 */
    fun chain(direction: String, sourceIsChinese: Boolean): Chain {
        val d = if (direction in DIRECTION_VALUES) direction else "auto"
        return when (d) {
            "zh_en" -> Chain(listOf(LanguagePair(SRC_ZH, PIVOT)), "中文 → 英文")
            "en_zh" -> Chain(listOf(LanguagePair(PIVOT, SRC_ZH)), "英文 → 中文")
            "auto" ->
                if (sourceIsChinese) {
                    Chain(listOf(LanguagePair(SRC_ZH, PIVOT)), "中文 → 英文")
                } else {
                    Chain(listOf(LanguagePair(PIVOT, SRC_ZH)), "英文 → 中文")
                }
            else -> {
                val target = d.removePrefix("zh_")
                Chain(
                    listOf(LanguagePair(SRC_ZH, PIVOT), LanguagePair(PIVOT, target)),
                    "中文 → ${TARGET_LABELS[target] ?: target}（经英文中转）",
                )
            }
        }
    }

    /** 设置页显示当前方向需要的语向包（按中文输入估算）。 */
    fun pairsFor(direction: String): List<LanguagePair> = chain(direction, true).pairs

    fun isChinese(text: String): Boolean {
        if (text.isEmpty()) return true
        var cjk = 0
        for (c in text) {
            if (c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf') cjk++
        }
        return cjk * 3 >= text.length
    }

    private suspend fun obtain(context: Context): Foxlet {
        client?.let { return it }
        return clientMutex.withLock {
            client ?: Foxlet.create(context.applicationContext) {
                translation { threading = Threading.Fixed(2) }
            }.also { client = it }
        }
    }

    /** 已就绪的本地语言包；未安装或校验失败返回 null。 */
    suspend fun installed(context: Context, pair: LanguagePair): InstalledModel? =
        runCatching { obtain(context).models.findUsable(pair) }
            .onFailure { Timber.w(it, "foxlet findUsable failed") }
            .getOrNull()

    /** 下载 / 校验语言包；失败返回 null（调用方提示用户检查网络）。 */
    suspend fun prepare(context: Context, pair: LanguagePair): InstalledModel? =
        runCatching { obtain(context).models.prepare(pair) }
            .onFailure { Timber.w(it, "foxlet prepare failed") }
            .getOrNull()

    suspend fun delete(context: Context, pair: LanguagePair): Boolean =
        runCatching {
            obtain(context).models.deleteAll(pair)
            true
        }.onFailure { Timber.w(it, "foxlet deleteAll failed") }.getOrDefault(false)

    /**
     * 翻译整句。任一语言包缺失时返回 null，由调用方按原文上屏并提示先下载，
     * 绝不让翻译失败吃掉用户的识别结果。
     */
    suspend fun translate(context: Context, text: String, direction: String): String? =
        runCatching {
            val c = chain(direction, isChinese(text))
            val f = obtain(context)
            val models = c.pairs.map { f.models.findUsable(it) ?: return@runCatching null }
            val result =
                if (models.size == 1) {
                    f.translator.translate(text, models[0])
                } else {
                    f.translator.translatePivot(text, models[0], models[1])
                }
            result.takeIf { it.isNotBlank() }
        }.onFailure { Timber.w(it, "foxlet translate failed") }.getOrNull()

    suspend fun shutdown() {
        clientMutex.withLock {
            client?.let { runCatching { it.shutdown() } }
            client = null
        }
    }
}
