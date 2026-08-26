package com.github.kr328.clash.design.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R

/**
 * 壁纸上的文字对比：主题色遮罩之外，给贴在图上的 TextView / Canvas 字加反色光晕，
 * 避免浅色主题黑字叠在黑色遮罩（或深色主题白字叠在浅色图）上看不见。
 */
internal object WallpaperReadability {
    private const val SHADOW_ALPHA = 0xCC

    fun install(root: View) {
        if (!UiBackground.exists(root.context)) return
        decorate(root)
    }

    fun applyTo(text: TextView) {
        if (!UiBackground.exists(text.context)) return
        applyInk(text)
    }

    fun applyCanvasTextContrast(context: Context, paint: Paint, textColor: Int) {
        if (!UiBackground.exists(context)) {
            paint.clearShadowLayer()
            return
        }
        val density = context.resources.displayMetrics.density
        paint.setShadowLayer(
            3.5f * density,
            0f,
            density,
            contrastShadowColor(textColor),
        )
    }

    private fun decorate(view: View) {
        when (view) {
            is TextView -> applyInk(view)
            is RecyclerView -> {
                watchRecycler(view)
                for (index in 0 until view.childCount) {
                    decorate(view.getChildAt(index))
                }
            }
            is ViewGroup -> {
                for (index in 0 until view.childCount) {
                    decorate(view.getChildAt(index))
                }
            }
        }
    }

    private fun watchRecycler(list: RecyclerView) {
        if (list.getTag(R.id.tag_wallpaper_child_watch) == true) return
        list.setTag(R.id.tag_wallpaper_child_watch, true)
        list.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(child: View) {
                    decorate(child)
                }

                override fun onChildViewDetachedFromWindow(child: View) = Unit
            },
        )
    }

    private fun applyInk(text: TextView) {
        if (isLightColor(text.currentTextColor)) {
            text.setTextColor(LiquidGlass.CONTENT_TEXT)
        }
        val density = text.resources.displayMetrics.density
        text.setShadowLayer(
            3.5f * density,
            0f,
            density,
            contrastShadowColor(text.currentTextColor),
        )
        text.setTag(R.id.tag_wallpaper_text_shadow, true)
    }

    private fun isLightColor(color: Int): Boolean {
        val luminance = (
            0.299f * Color.red(color) +
                0.587f * Color.green(color) +
                0.114f * Color.blue(color)
            ) / 255f
        return luminance > 0.62f
    }

    private fun contrastShadowColor(textColor: Int): Int {
        val luminance = (
            0.299f * Color.red(textColor) +
                0.587f * Color.green(textColor) +
                0.114f * Color.blue(textColor)
            ) / 255f
        return if (luminance > 0.5f) {
            Color.argb(SHADOW_ALPHA, 0, 0, 0)
        } else {
            Color.argb(SHADOW_ALPHA, 255, 255, 255)
        }
    }
}
