// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.widget

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.weather.obviouslyweather.R
import kotlin.math.min
import kotlin.math.roundToInt

internal object WidgetLayout {
    fun measure(root: View, size: WidgetRenderSize, density: Float): Float {
        val summary = root.findViewById<ViewGroup>(R.id.widget_summary)
        val forecast = root.findViewById<View>(R.id.widget_forecast)
        val icon = root.findViewById<View>(R.id.widget_current_icon)
        val texts = mutableListOf<Pair<TextView, Float>>()
        fun collect(view: View) {
            if (view is TextView) texts += view to view.textSize
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(root)
        val margins = (0 until summary.childCount).map { index ->
            val child = summary.getChildAt(index)
            child to (child.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        }
        val screenWidth = root.resources.configuration.smallestScreenWidthDp.takeIf { it > 0 }
        var scale = min(1f, min(size.width / 320f, (screenWidth ?: 384) / 384f))
        var measuredUnit = density * scale
        repeat(8) {
            val unit = density * scale
            measuredUnit = unit
            val padding = (16 * unit).roundToInt()
            root.setPadding(padding, padding, padding, padding)
            summary.setPaddingRelative(0, 0, (12 * unit).roundToInt(), 0)
            icon.layoutParams = icon.layoutParams.apply {
                width = (80 * unit).roundToInt()
                height = (62 * unit).roundToInt()
            }
            margins.forEach { (child, top) ->
                (child.layoutParams as ViewGroup.MarginLayoutParams).topMargin = (top * scale).roundToInt()
            }
            texts.forEach { (text, original) -> text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, original * scale) }
            forecast.layoutParams = forecast.layoutParams.apply {
                height = min(size.pixelHeight - padding * 2, (220 * unit).roundToInt()).coerceAtLeast(1)
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(size.pixelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size.pixelHeight, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, size.pixelWidth, size.pixelHeight)
            val required = (0 until summary.childCount).sumOf { index ->
                val child = summary.getChildAt(index)
                if (child is TextView) child.measure(
                    View.MeasureSpec.makeMeasureSpec(child.measuredWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val params = child.layoutParams as ViewGroup.MarginLayoutParams
                child.measuredHeight + params.topMargin + params.bottomMargin
            }
            if (required <= summary.height) return unit
            // Fit the measured text, including imported fonts and accessibility font sizes.
            scale *= summary.height.toFloat() / required * 0.98f
        }
        return measuredUnit
    }
}
