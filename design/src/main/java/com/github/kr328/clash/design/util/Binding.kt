package com.github.kr328.clash.design.util

import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.databinding.BindingAdapter
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.R

@BindingAdapter("android:minHeight")
fun bindMinHeight(view: View, value: Float) {
    view.minimumHeight = value.toInt()
}

private const val LOG_SEPARATOR = " --> "

@BindingAdapter("logMessage")
fun setLogMessage(textView: TextView, logMessage: LogMessage?) {
    if (logMessage == null) {
        textView.text = ""
        return
    }
    val message = logMessage.message
    val level = logMessage.level
    val context = textView.context

    val colorError = ContextCompat.getColor(context, R.color.color_error)
    val colorWarn = ContextCompat.getColor(context, R.color.color_warn)
    val colorHighlight = ContextCompat.getColor(context, R.color.color_log_highlight)

    val spannable = SpannableString(message)

    when (level) {
        LogMessage.Level.Error -> spannable.setSpan(
            ForegroundColorSpan(colorError), 0, message.length, 0
        )
        LogMessage.Level.Warning -> spannable.setSpan(
            ForegroundColorSpan(colorWarn), 0, message.length, 0
        )
        else -> {}
    }

    val segments = message.split(LOG_SEPARATOR)
    if (segments.size >= 6) {
        var pos = 0
        for (i in segments.indices) {
            val start = pos
            val end = pos + segments[i].length
            when (i) {
                1 -> {
                    // Highlight process name (segment 1)
                    spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
                    spannable.setSpan(ForegroundColorSpan(colorHighlight), start, end, 0)
                }
                4 -> {
                    // Highlight only the value part after comma in rule detail (segment 4)
                    // e.g., "DOMAIN-SUFFIX,+.bugly.qq.com" -> only highlight "+.bugly.qq.com"
                    val segment = segments[i]
                    val commaIndex = segment.indexOf(',')
                    if (commaIndex != -1 && commaIndex < segment.length - 1) {
                        val valueStart = start + commaIndex + 1
                        spannable.setSpan(StyleSpan(Typeface.BOLD), valueStart, end, 0)
                        spannable.setSpan(ForegroundColorSpan(colorHighlight), valueStart, end, 0)
                    } else {
                        // No comma found, highlight the whole segment
                        spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
                        spannable.setSpan(ForegroundColorSpan(colorHighlight), start, end, 0)
                    }
                }
            }
            pos = end + LOG_SEPARATOR.length
        }
    }

    textView.text = spannable
}
