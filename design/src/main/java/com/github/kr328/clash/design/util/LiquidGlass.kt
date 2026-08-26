package com.github.kr328.clash.design.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * iOS 液态玻璃：壁纸霜化裁切 + 低密度渐变着色 + 高光描边。
 * 对齐看板 [KanbanGlassSurface] 与桌面 glass.scss。
 */
object LiquidGlass {
    private const val BLUR_FILL_LIGHT_TOP = 0.62f
    private const val BLUR_FILL_LIGHT_MID = 0.52f
    private const val BLUR_FILL_LIGHT_BOT = 0.46f
    private const val BLUR_FILL_DARK_TOP = 0.56f
    private const val BLUR_FILL_DARK_MID = 0.46f
    private const val BLUR_FILL_DARK_BOT = 0.40f
    private const val EDGE_LIGHT = 0.62f
    private const val EDGE_DARK = 0.22f

    fun attach(view: View) {
        view.background = ChromeDrawable(view)
        view.alpha = 1f
        UiBackground.watchChrome(view)
    }

    fun draw(view: View, canvas: Canvas) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return

        val frost = UiBackground.frostedCover(view.context)
        if (frost != null) {
            drawFrostedWallpaper(view, canvas, frost, width, height)
        }

        val dark = isDark(view)
        val top = if (dark) BLUR_FILL_DARK_TOP else BLUR_FILL_LIGHT_TOP
        val mid = if (dark) BLUR_FILL_DARK_MID else BLUR_FILL_LIGHT_MID
        val bot = if (dark) BLUR_FILL_DARK_BOT else BLUR_FILL_LIGHT_BOT
        val base = if (dark) Color.rgb(46, 48, 61) else Color.rgb(245, 245, 245)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    Color.argb((top * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                    Color.argb((mid * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                    Color.argb((bot * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val edgeAlpha = if (dark) EDGE_DARK else EDGE_LIGHT
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = view.resources.displayMetrics.density
            color = Color.argb((edgeAlpha * 255).toInt(), 255, 255, 255)
        }
        canvas.drawRect(
            edgePaint.strokeWidth / 2f,
            edgePaint.strokeWidth / 2f,
            width - edgePaint.strokeWidth / 2f,
            height - edgePaint.strokeWidth / 2f,
            edgePaint,
        )
    }

    private fun drawFrostedWallpaper(
        view: View,
        canvas: Canvas,
        frost: android.graphics.Bitmap,
        width: Int,
        height: Int,
    ) {
        val screen = view.resources.displayMetrics
        val screenW = screen.widthPixels.coerceAtLeast(1)
        val screenH = screen.heightPixels.coerceAtLeast(1)
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)

        val bw = frost.width.toFloat()
        val bh = frost.height.toFloat()
        val scale = maxOf(screenW / bw, screenH / bh)
        val displayW = bw * scale
        val displayH = bh * scale
        val offsetX = (screenW - displayW) / 2f
        val offsetY = (screenH - displayH) / 2f

        val srcLeft = ((loc[0] - offsetX) / scale).coerceIn(0f, bw)
        val srcTop = ((loc[1] - offsetY) / scale).coerceIn(0f, bh)
        val srcRight = ((loc[0] + width - offsetX) / scale).coerceIn(srcLeft + 1f, bw)
        val srcBottom = ((loc[1] + height - offsetY) / scale).coerceIn(srcTop + 1f, bh)

        val src = Rect(srcLeft.toInt(), srcTop.toInt(), srcRight.toInt(), srcBottom.toInt())
        val dst = Rect(0, 0, width, height)
        canvas.drawBitmap(frost, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun isDark(view: View): Boolean {
        val night = view.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private class ChromeDrawable(private val view: View) : Drawable() {
        override fun draw(canvas: Canvas) {
            if (UiBackground.exists(view.context)) {
                LiquidGlass.draw(view, canvas)
            } else {
                canvas.drawColor(view.context.resolveThemedColor(android.R.attr.windowBackground))
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
