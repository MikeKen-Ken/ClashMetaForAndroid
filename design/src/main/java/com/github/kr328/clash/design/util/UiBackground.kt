package com.github.kr328.clash.design.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/** 应用自定义背景图：本地文件 + 遮罩，行为对齐看板的 Board background。 */
object UiBackground {
    const val FILE_NAME = "ui_background"
    const val DEFAULT_OVERLAY_PERCENT = 40
    const val MAX_OVERLAY_PERCENT = 70
    const val DEFAULT_CARD_OPACITY_PERCENT = 100
    const val MIN_CARD_OPACITY_PERCENT = 35

    val overlayPercents = arrayOf(0, 20, DEFAULT_OVERLAY_PERCENT, 60)
    val cardOpacityPercents = arrayOf(DEFAULT_CARD_OPACITY_PERCENT, 85, 70, 50)

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean {
        val target = file(context)
        return target.isFile && target.length() > 0L
    }

    fun overlayColor(percent: Int): Int {
        val clamped = percent.coerceIn(0, MAX_OVERLAY_PERCENT)
        return Color.argb((clamped * 255 / 100f).toInt().coerceIn(0, 255), 0, 0, 0)
    }

    fun cardSurfaceAlpha(context: Context, opacityPercent: Int): Int {
        if (!exists(context)) return 255
        val clamped = opacityPercent.coerceIn(MIN_CARD_OPACITY_PERCENT, DEFAULT_CARD_OPACITY_PERCENT)
        return (clamped * 255 / 100f).toInt().coerceIn(0, 255)
    }

    fun wrap(context: Context, content: View, overlayPercent: Int): View {
        val image = decodeCover(context) ?: return content
        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        frame.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageBitmap(image)
        })
        if (overlayPercent > 0) {
            frame.addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBackgroundColor(overlayColor(overlayPercent))
            })
        }
        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        (content.parent as? ViewGroup)?.removeView(content)
        frame.addView(content)
        return frame
    }

    fun import(context: Context, uri: Uri): Boolean {
        val dest = file(context)
        val tmp = File(context.cacheDir, "$FILE_NAME.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                tmp.delete()
                return false
            }
            tmp.copyTo(dest, overwrite = true)
            true
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun decodeCover(context: Context): Bitmap? {
        val target = file(context)
        if (!target.isFile || target.length() <= 0L) return null
        val metrics = context.resources.displayMetrics
        val reqW = metrics.widthPixels.coerceAtLeast(1)
        val reqH = metrics.heightPixels.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            target.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
