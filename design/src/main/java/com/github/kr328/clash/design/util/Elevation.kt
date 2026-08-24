package com.github.kr328.clash.design.util

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.view.ActivityBarLayout
import com.github.kr328.clash.design.view.ObservableScrollView

private class AppBarElevationController(
    private val activityBar: ActivityBarLayout
) {
    private var animator: ValueAnimator? = null

    var elevated: Boolean = false
        set(value) {
            if (field == value)
                return

            field = value

            val target = if (value) {
                activityBar.context.getPixels(R.dimen.toolbar_elevation).toFloat()
            } else {
                0f
            }

            animator?.cancel()
            animator = ValueAnimator.ofFloat(activityBar.elevation, target).apply {
                addUpdateListener {
                    activityBar.elevation = it.animatedValue as Float
                }
                duration = ELEVATION_ANIMATION_DURATION_MS
                interpolator = ELEVATION_INTERPOLATOR
                start()
            }
        }

    private companion object {
        const val ELEVATION_ANIMATION_DURATION_MS = 160L
        val ELEVATION_INTERPOLATOR = AccelerateDecelerateInterpolator()
    }
}

fun RecyclerView.bindAppBarElevation(activityBar: ActivityBarLayout) {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        private val controller = AppBarElevationController(activityBar)

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            controller.elevated = !recyclerView.isTop
        }
    })
}

fun ObservableScrollView.bindAppBarElevation(activityBar: ActivityBarLayout) {
    val controller = AppBarElevationController(activityBar)

    addOnScrollChangedListener { view, _, _, _, _ ->
        controller.elevated = !view.isTop
    }
}
