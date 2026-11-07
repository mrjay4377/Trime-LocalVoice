/*
 * 候选栏的「正在录音」标注：两侧随音量起伏的波形线 + 中间麦克风 + 底部淡分隔线。
 * 按住语音键时由 InputBarDelegate 切到本页，松手后恢复候选/工具栏。
 * 颜色跟随当前配色方案的 candidate_text_color，切换主题配色时由 refreshColors() 重刷。
 */
package com.osfans.trime.ime.bar.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeScope
import splitties.views.dsl.core.Ui
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sin

/**
 * 一条随音量起伏的波形线。静音时只画一条细基线（保持原本「两条细线夹麦克风」的样式），
 * 有声音时按实时音量 + 缓慢变化的相位画出高低起伏的竖条，左右两侧镜像对称。
 */
private class VoiceWaveLine(
    context: Context,
    /** true = 靠近麦克风的一端在右侧（用于左半边）；false = 靠近麦克风的一端在左侧。 */
    private val nearMicAtEnd: Boolean,
) : View(context) {
    private fun dp(value: Float) = value * resources.displayMetrics.density

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var level = 0f
    private var target = 0f
    private var phase = 0f
    private var active = false

    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2f).toFloat()).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            // 音频块是 200ms 一个，做平滑跟随避免波形跳变
            level += (target - level) * 0.3f
            if (abs(target - level) < 0.002f) level = target
            invalidate()
        }
    }

    fun setColor(color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        wavePaint.color = Color.argb(0xB0, r, g, b)
        basePaint.color = Color.argb(0x55, r, g, b)
        invalidate()
    }

    fun setLevel(value: Float) {
        target = value.coerceIn(0f, 1f)
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
            level = 0f
            target = 0f
            phase = 0f
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cy = h / 2f

        // 基线：静音时就是原来那条细线
        basePaint.strokeWidth = dp(1f)
        canvas.drawLine(0f, cy, w, cy, basePaint)
        if (!active || level <= 0.002f) return

        val count = (w / dp(6f)).toInt().coerceIn(6, 40)
        val step = w / count
        wavePaint.strokeWidth = minOf(dp(2.4f), step * 0.6f)
        val minAmp = dp(0.75f)
        val maxAmp = minOf(h / 2f - dp(2f), dp(15f))
        val span = (maxAmp - minAmp).coerceAtLeast(dp(1f))
        for (i in 0 until count) {
            val t = if (count == 1) 0f else i / (count - 1f)
            // 越靠近麦克风振幅越大，两端自然收窄
            val proximity = if (nearMicAtEnd) t else 1f - t
            val envelope = 0.3f + 0.7f * proximity
            val wobble = 0.55f + 0.45f * sin(phase + i * 0.6f + (if (nearMicAtEnd) 0f else 1.8f))
            val amp = minAmp + span * level * envelope * wobble
            val x = step * (i + 0.5f)
            canvas.drawLine(x, cy - amp, x, cy + amp, wavePaint)
        }
    }
}

class VoiceIndicatorUi(
    override val ctx: Context,
    private val scope: ThemeScope,
) : Ui {
    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()

    private fun lineParams() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

    private val mic = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_voice_mic)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    /** 流式引擎的中间识别结果；整段解码的引擎不会给它内容，保持隐藏不影响原有样式。 */
    private val partialText = TextView(ctx).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        textSize = 13f
        setPadding(dp(16), 0, dp(16), 0)
        visibility = View.GONE
    }

    private val leftLine = VoiceWaveLine(ctx, nearMicAtEnd = true)
    private val rightLine = VoiceWaveLine(ctx, nearMicAtEnd = false)
    private val bottomDivider = View(ctx)

    /** 轻微的呼吸动画，用来区分「正在录音」与静态图标。 */
    private val breathing = ObjectAnimator.ofFloat(mic, "alpha", 1f, 0.4f, 1f).apply {
        duration = 1200L
        repeatCount = ObjectAnimator.INFINITE
    }

    override val root: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(16)
            setPadding(pad, 0, pad, 0)
            addView(leftLine, lineParams())
            addView(
                mic,
                LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(14)
                    rightMargin = dp(14)
                },
            )
            addView(rightLine, lineParams())
        }
        addView(
            partialText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22)),
        )
        addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(
            bottomDivider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)),
        )
        applyColors()
    }

    /** 主题/配色切换后重刷颜色。 */
    fun refreshColors() = applyColors()

    private fun applyColors() {
        runCatching {
            val fg = scope.colors.candidateTextColor
            val divider = Color.argb(0x22, Color.red(fg), Color.green(fg), Color.blue(fg))
            leftLine.setColor(fg)
            rightLine.setColor(fg)
            bottomDivider.setBackgroundColor(divider)
            mic.imageTintList = ColorStateList.valueOf(fg)
            partialText.setTextColor(fg)
        }.onFailure { Timber.w(it, "VoiceIndicatorUi: cannot resolve candidate text color") }
    }

    /** 实时音量（0..1），由录音 PCM 的 RMS 计算后传入。 */
    fun updateLevel(level: Float) {
        leftLine.setLevel(level)
        rightLine.setLevel(level * 0.94f)
    }

    /** 流式引擎的中间识别结果；空串收起文字行，恢复原来「两条细线夹麦克风」的样式。 */
    fun updatePartial(text: String) {
        val value = text.trim()
        partialText.text = value
        partialText.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setActive(active: Boolean) {
        if (active) {
            if (!breathing.isStarted) breathing.start()
        } else {
            breathing.cancel()
            mic.alpha = 1f
            updateLevel(0f)
            updatePartial("")
        }
        leftLine.setActive(active)
        rightLine.setActive(active)
    }
}
