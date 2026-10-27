package com.tyust.course.schedule

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.tyust.course.R
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class ScheduleWidgetText(
    val text: String,
    val size: Float,
    val bold: Boolean = false,
    val margin: Float = 0f,
    val singleLine: Boolean = false,
    /** Text laid out after this one in the same baseline-aligned row, [besideGap] px apart, using the remaining width. */
    val beside: ScheduleWidgetText? = null,
    val besideGap: Int = 0
)

/** Measure the same wrapping, font metrics and nonlinear SP scaling as the host TextViews. */
internal class ScheduleWidgetTextFitter(private val context: Context, width: Float) {
    private val metrics = context.resources.displayMetrics
    private val width = (width - 2).toInt().coerceAtLeast(1)

    fun dp(value: Float): Int = (value * metrics.density).roundToInt()

    private fun measure(field: ScheduleWidgetText, scale: Float, width: Int = this.width): TextView {
        // Match RemoteViews' actual TextView metrics, including CJK fallback line spacing.
        // Measure all lines even for a one-line field so fits() can reject truncation.
        return TextView(context, null, 0, R.style.ScheduleWidgetFullText).apply {
            text = field.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, field.size * scale)
            typeface = if (field.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        }
    }

    private class Line(val height: Int, val complete: Boolean)

    /** Height of one field's row (both views when [ScheduleWidgetText.beside] is set) and whether nothing is cut off. */
    private fun line(field: ScheduleWidgetText, scale: Float): Line {
        val label = measure(field, scale)
        val layout = label.layout ?: return Line(label.measuredHeight, false)
        fun complete(layout: Layout, single: Boolean, width: Int) = !(single && layout.lineCount > 1) &&
            (0 until layout.lineCount).all { ceil(layout.getLineWidth(it)) <= width }
        val own = complete(layout, field.singleLine, width)
        val side = field.beside?.takeIf { it.text.isNotEmpty() } ?: return Line(label.measuredHeight, own)
        // The side view gets what the wrap_content label leaves; the row aligns both on their baselines,
        // so its height is the larger ascent plus the larger descent (CJK fallback fonts are taller than digits).
        val used = (0 until layout.lineCount).maxOfOrNull { ceil(layout.getLineWidth(it)).toInt() } ?: 0
        val sideWidth = width - used - field.besideGap - 1
        if (sideWidth < 1) return Line(label.measuredHeight, false)
        val sideLabel = measure(side, scale, sideWidth)
        val sideLayout = sideLabel.layout ?: return Line(label.measuredHeight, false)
        val ascent = maxOf(label.baseline, sideLabel.baseline)
        val descent = maxOf(label.measuredHeight - label.baseline, sideLabel.measuredHeight - sideLabel.baseline)
        return Line(ascent + descent, own && complete(sideLayout, side.singleLine, sideWidth))
    }

    fun height(fields: List<ScheduleWidgetText>, scale: Float = 1f): Int = fields.filter { it.text.isNotEmpty() }
        .sumOf { line(it, scale).height + dp(it.margin) + 1 }

    fun fits(fields: List<ScheduleWidgetText>, available: Int, scale: Float = 1f): Boolean {
        var height = 0
        for (field in fields.filter { it.text.isNotEmpty() }) {
            val line = line(field, scale)
            height += line.height + dp(field.margin) + 1
            if (height > available || !line.complete) return false
        }
        return true
    }

    fun scale(fields: List<ScheduleWidgetText>, available: Int): Float {
        if (fits(fields, available)) return 1f
        var low = 0.01f
        var high = 1f
        repeat(14) {
            val mid = (low + high) / 2
            if (fits(fields, available, mid)) low = mid else high = mid
        }
        return low
    }
}
