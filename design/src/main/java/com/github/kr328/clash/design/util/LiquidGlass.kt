package com.github.kr328.clash.design.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * iOS 液态玻璃：壁纸霜化裁切 + 低密度渐变着色 + 高光描边。
 * 对齐看板 [KanbanGlassSurface] 与桌面 glass.scss。
 * 玻璃保持浅色，正文用深色墨水。
 */
object LiquidGlass {
    val CONTENT_TEXT = Color.rgb(28, 28, 28)

    private const val BLUR_FILL_TOP = 0.62f
    private const val BLUR_FILL_MID = 0.52f
    private const val BLUR_FILL_BOT = 0.46f
    private const val EDGE = 0.62f

    fun attach(view: View, cornerRadiusPx: Float = 0f) {
        view.background = ChromeDrawable(view, cornerRadiusPx)
        view.alpha = 1f
        UiBackground.watchChrome(view)
    }

    fun draw(view: View, canvas: Canvas, cornerRadiusPx: Float = 0f) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return

        val clipped = cornerRadiusPx > 0f
        if (clipped) {
            canvas.save()
            val path = Path()
            path.addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                cornerRadiusPx,
                cornerRadiusPx,
                Path.Direction.CW,
            )
            canvas.clipPath(path)
        }

        val frost = UiBackground.frostedCover(view.context)
        if (frost != null) {
            drawFrostedWallpaper(view, canvas, frost, width, height)
        }

        val base = Color.rgb(245, 245, 245)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    Color.argb((BLUR_FILL_TOP * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                    Color.argb((BLUR_FILL_MID * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                    Color.argb((BLUR_FILL_BOT * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base)),
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = view.resources.displayMetrics.density
            color = Color.argb((EDGE * 255).toInt(), 255, 255, 255)
        }
        val inset = edgePaint.strokeWidth / 2f
        if (clipped) {
            canvas.drawRoundRect(
                inset,
                inset,
                width - inset,
                height - inset,
                cornerRadiusPx,
                cornerRadiusPx,
                edgePaint,
            )
        } else {
            canvas.drawRect(
                inset,
                inset,
                width - inset,
                height - inset,
                edgePaint,
            )
        }

        if (clipped) {
            canvas.restore()
        }
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

    private class ChromeDrawable(
        private val view: View,
        private val cornerRadiusPx: Float,
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            if (UiBackground.exists(view.context)) {
                LiquidGlass.draw(view, canvas, cornerRadiusPx)
            } else if (cornerRadiusPx > 0f) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = view.context.resolveThemedColor(
                        com.google.android.material.R.attr.colorSurface,
                    )
                }
                canvas.drawRoundRect(
                    0f,
                    0f,
                    bounds.width().toFloat(),
                    bounds.height().toFloat(),
                    cornerRadiusPx,
                    cornerRadiusPx,
                    paint,
                )
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
