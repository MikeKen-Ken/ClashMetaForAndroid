package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore

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

        paint.color = state.background
        paint.style = Paint.Style.FILL

        // draw background
        canvas.apply {
            if (state.config.proxyLine==1) {
                drawRect(0f, 0f, width, height, paint)
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

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint

        val width = width.toFloat()
        val height = height.toFloat()

        paint.textSize = state.config.textSize

        // measure delay text bounds
        val delayCount = safeBreakCount(
            state.delayText,
            paint.breakText(
                state.delayText,
                false,
                (width - state.config.layoutPadding * 2 - state.config.contentPadding * 2)
                    .coerceAtLeast(0f),
                null
            )
        )

        state.paint.getTextBounds(state.delayText, 0, delayCount, state.rect)

        val delayWidth = state.rect.width()

        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayWidth -
                state.config.textMargin * 2
                )
            .coerceAtLeast(0f)

        // Reserve space so title doesn't overlap manual-selection icon (top-right)
        val manualIconSize = if (state.isManualSelection) {
            (state.config.textSize * 2.2f).toInt().coerceIn(24, 40)
        } else 0
        val manualIconWidth = if (state.isManualSelection) {
            manualIconSize + state.config.textMargin
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

        // draw delay (red "T" when timeout, otherwise normal color)
        canvas.apply {
            paint.color = if (state.delayTimeout) state.config.delayTimeoutColor else state.controls
            val x = width - state.config.layoutPadding - state.config.contentPadding - delayWidth
            val y = height / 2f - textOffset

            drawText(state.delayText, 0, delayCount, x, y, paint)
        }
        paint.color = state.controls

        // draw title; manual-selection icon is drawn at top-right corner
        canvas.apply {
            val titleX = state.config.layoutPadding + state.config.contentPadding
            val titleY = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f - textOffset

            drawText(state.title, 0, titleCount, titleX, titleY, paint)
        }

        // draw subtitle
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f * 2 - textOffset

            drawText(state.subtitle, 0, subtitleCount, x, y, paint)
        }

        // draw manual-selection icon at top-right corner (pin icon), last so it stays on top
        if (state.isManualSelection && manualIconSize > 0) {
            context.getDrawableCompat(R.drawable.ic_baseline_place)?.let { icon ->
                DrawableCompat.setTint(icon.mutate(), state.controls)
                val iconLeft = (width - state.config.layoutPadding - state.config.contentPadding - manualIconSize).toInt()
                val iconTop = state.config.layoutPadding.toInt()
                icon.setBounds(iconLeft, iconTop, iconLeft + manualIconSize, iconTop + manualIconSize)
                icon.draw(canvas)
            }
        }
    }
}