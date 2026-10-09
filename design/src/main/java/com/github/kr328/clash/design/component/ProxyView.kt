package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.LiquidGlass
import com.github.kr328.clash.design.util.UiBackground
import com.github.kr328.clash.design.util.WallpaperReadability
import kotlin.math.max

/**
 * Ensures [breakCount] does not cut in the middle of a UTF-16 surrogate pair (e.g. emoji).
 * Otherwise drawText would show replacement character for the second half.
 */
private fun safeBreakCount(text: CharSequence, breakCount: Int): Int {
    var count = breakCount.coerceIn(0, text.length)
    while (count > 0 && count < text.length && Character.isHighSurrogate(text[count - 1])) {
        count--
    }
    return count
}

class ProxyView(
    context: Context,
    config: ProxyViewConfig,
) : View(context) {

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    var state: ProxyViewState? = null
    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = state ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                resources.displayMetrics.widthPixels
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec)
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        state.paint.apply {
            reset()

            textSize = state.config.textSize

            getTextBounds("Stub!", 0, 1, state.rect)
        }

        val textHeight = state.rect.height()
        val exceptHeight = (state.config.layoutPadding * 2 +
                state.config.contentPadding * 2 +
                textHeight * 2 +
                state.config.textMargin).toInt()

        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                exceptHeight
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                exceptHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        setMeasuredDimension(width, height)
    }

    override fun draw(canvas: Canvas) {
        val state = state ?: return super.draw(canvas)

        if (state.update(false))
            postInvalidate()

        val width = width.toFloat()
        val height = height.toFloat()

        val paint = state.paint

        paint.reset()

        val glass = UiBackground.exists(context) && !state.isSelected
        paint.color = state.background
        paint.style = Paint.Style.FILL

        // draw background
        canvas.apply {
            if (state.config.proxyLine==1) {
                if (glass) {
                    LiquidGlass.draw(this@ProxyView, this)
                } else {
                    drawRect(0f, 0f, width, height, paint)
                }
            } else {
                val path = state.path

                path.reset()

                path.addRoundRect(
                    state.config.layoutPadding,
                    state.config.layoutPadding,
                    width - state.config.layoutPadding,
                    height - state.config.layoutPadding,
                    state.config.cardRadius,
                    state.config.cardRadius,
                    Path.Direction.CW,
                )

                if (glass) {
                    clipPath(path)
                    LiquidGlass.draw(this@ProxyView, this)
                } else {
                    paint.setShadowLayer(
                        state.config.cardRadius,
                        state.config.cardOffset,
                        state.config.cardOffset,
                        state.config.shadow
                    )

                    drawPath(path, paint)

                    clipPath(path)
                }
            }
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint

        val width = width.toFloat()
        val height = height.toFloat()

        paint.textSize = state.config.textSize

        val delayMaxWidth = (width - state.config.layoutPadding * 2 - state.config.contentPadding * 2)
            .coerceAtLeast(0f)

        // measure delay text bounds
        val delayCount = safeBreakCount(
            state.delayText,
            paint.breakText(
                state.delayText,
                false,
                delayMaxWidth,
                null
            )
        )

        state.paint.getTextBounds(state.delayText, 0, delayCount, state.rect)

        val delayWidth = state.rect.width().toFloat()
        val effectiveText = state.effectiveDelayText
        val effectiveSize = state.config.textSize * 0.72f
        var effectiveCount = 0
        var effectiveWidth = 0f
        if (effectiveText.isNotEmpty()) {
            paint.textSize = effectiveSize
            effectiveCount = safeBreakCount(
                effectiveText,
                paint.breakText(effectiveText, false, delayMaxWidth, null),
            )
            state.paint.getTextBounds(effectiveText, 0, effectiveCount, state.rect)
            effectiveWidth = state.rect.width().toFloat()
            paint.textSize = state.config.textSize
        }
        val delayColumnWidth = max(delayWidth, effectiveWidth)

        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayColumnWidth -
                state.config.textMargin * 2
                )
            .coerceAtLeast(0f)

        val manualIconWidth = if (state.isManualSelection) {
            (state.config.textSize * 1.5f).toInt().coerceIn(18, 28) + state.config.textMargin
        } else 0f

        // measure title text bounds (safe break so emoji/surrogate pairs are not cut)
        val titleCount = safeBreakCount(
            state.title,
            paint.breakText(
                state.title,
                false,
                (mainTextWidth - manualIconWidth).coerceAtLeast(0f),
                null,
            )
        )

        // measure subtitle text bounds (safe break for emoji in group/selector names)
        val subtitleCount = safeBreakCount(
            state.subtitle,
            paint.breakText(
                state.subtitle,
                false,
                mainTextWidth,
                null,
            )
        )

        // text draw measure
        val textOffset = (paint.descent() + paint.ascent()) / 2

        paint.reset()

        paint.textSize = state.config.textSize
        paint.isAntiAlias = true
        WallpaperReadability.applyCanvasTextContrast(context, paint, state.controls)

        val upperRowY = state.config.layoutPadding +
                (height - state.config.layoutPadding * 2) / 3f - textOffset
        val lowerRowY = state.config.layoutPadding +
                (height - state.config.layoutPadding * 2) / 3f * 2 - textOffset
        val middleY = height / 2f - textOffset
        val titleY = if (state.config.showDetail) upperRowY else middleY
        val hasEffective = effectiveText.isNotEmpty() && effectiveCount > 0

        // draw delay (red "T" when timeout, otherwise normal color)
        canvas.apply {
            paint.color = if (state.delayTimeout) state.config.delayTimeoutColor else state.controls
            val x = width - state.config.layoutPadding - state.config.contentPadding - delayWidth
            drawText(state.delayText, 0, delayCount, x, if (hasEffective) upperRowY else middleY, paint)
        }
        if (hasEffective) {
            paint.textSize = effectiveSize
            paint.color = Color.argb(
                (Color.alpha(state.controls) * 0.72f).toInt(),
                Color.red(state.controls),
                Color.green(state.controls),
                Color.blue(state.controls),
            )
            val x = width - state.config.layoutPadding - state.config.contentPadding - effectiveWidth
            canvas.drawText(effectiveText, 0, effectiveCount, x, lowerRowY, paint)
            paint.textSize = state.config.textSize
        }
        paint.color = state.controls

        // draw title (with optional manual-selection indicator)
        canvas.apply {
            var titleX = state.config.layoutPadding + state.config.contentPadding

            if (state.isManualSelection) {
                val iconSize = (state.config.textSize * 1.5f).toInt().coerceIn(18, 28)
                context.getDrawableCompat(R.drawable.ic_outline_label)?.let { icon ->
                    DrawableCompat.setTint(icon.mutate(), state.controls)
                    val iconLeft = titleX.toInt()
                    val iconTop = (titleY - iconSize / 2f).toInt()
                    icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                    icon.draw(canvas)
                }
                titleX += iconSize + state.config.textMargin
            }

            drawText(state.title, 0, titleCount, titleX, titleY, paint)
        }

        if (state.config.showDetail) {
            canvas.apply {
                val x = state.config.layoutPadding + state.config.contentPadding
                drawText(state.subtitle, 0, subtitleCount, x, lowerRowY, paint)
            }
        }
    }
}