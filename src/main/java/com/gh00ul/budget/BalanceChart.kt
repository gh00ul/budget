package com.gh00ul.budget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs

// Line chart of the balance after each pay period. The first point ("Now") is today's bank balance minus the
// bills still due before the next payday, so it's a forecast like the rest; it's drawn solid only to mark where
// the line starts. Each later point is the balance after that payday's paycheck and bills. Labels the first and
// the final value, and the low point when the balance actually dips (or goes negative).
class BalanceChart(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var labels: List<String> = emptyList()
    private var values: List<Double> = emptyList()
    private var format: (Double) -> String = { it.toString() }

    private val dp = resources.displayMetrics.density
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.card) }
    private val basePaint = Paint().apply {
        strokeWidth = 1 * dp
        color = context.getColor(R.color.divider)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * dp
        pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f)
        color = context.getColor(R.color.hint)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_secondary)
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
        typeface = medium
    }

    // Space kept between two labels moved apart so they don't touch.
    private val labelGap = 4 * dp

    // Reused on every draw (onDraw runs each animation frame, so it shouldn't allocate).
    private val line = Path()
    private val area = Path()

    // Each text size is converted on its own (rather than multiplying 1sp) so Android 14+'s non-linear font
    // scaling applies, as it does to the app's other text: big sizes grow less than small ones. For the 11sp and
    // 12sp used here the result is the same either way.
    private fun sp(size: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)

    // `description` is what a screen reader says for the chart.
    fun setData(labels: List<String>, values: List<Double>, description: String, format: (Double) -> String) {
        this.labels = labels
        this.values = values
        this.format = format
        contentDescription = description
        updateFill()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFill()
    }

    // Room for the value labels above and the axis labels below, which grow with the font size.
    private fun plotTop() = valueText.textSize + 12 * dp
    private fun plotBottom() = height - axisText.textSize - 14 * dp
    private fun lineColor() = context.getColor(if (values.any { it < 0 }) R.color.negative else R.color.positive)

    // The fade under the line depends only on the color and the plot's height, so it's made when those change.
    private fun updateFill() {
        val color = lineColor()
        fillPaint.shader = LinearGradient(
            0f, plotTop(), 0f, plotBottom(), withAlpha(color, 0x30), withAlpha(color, 0x00), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2 || width == 0) return
        val negative = context.getColor(R.color.negative)
        val textColor = context.getColor(R.color.text)
        val secondary = context.getColor(R.color.text_secondary)
        val anyNegative = values.any { it < 0 }
        val color = lineColor()

        val left = 16 * dp
        val right = width - 16 * dp
        val top = plotTop()
        val bottom = plotBottom()
        val minV = minOf(0.0, values.min())
        val maxV = maxOf(values.max(), 0.0)
        val span = (maxV - minV).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = left + (right - left) * i / (values.size - 1)
        fun y(v: Double) = top + ((maxV - v) / span * (bottom - top)).toFloat()

        canvas.drawLine(left, bottom, right, bottom, basePaint)

        line.reset()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
        area.set(line)
        area.lineTo(x(values.size - 1), bottom)
        area.lineTo(x(0), bottom)
        area.close()
        canvas.drawPath(area, fillPaint)
        if (anyNegative) canvas.drawLine(left, y(0.0), right, y(0.0), zeroPaint)
        linePaint.color = color
        canvas.drawPath(line, linePaint)

        val last = values.size - 1
        val low = values.indices.minBy { values[it] }
        // Only call out the low point if the balance really dips somewhere in the middle, or goes negative.
        val showLow = low in 1 until last || values[low] < 0
        dotPaint.color = color
        values.forEachIndexed { i, v ->
            val radius = if (showLow && i == low) 6 * dp else 4 * dp
            canvas.drawCircle(x(i), y(v), radius, dotPaint)
            if (i != 0) canvas.drawCircle(x(i), y(v), radius - 2 * dp, holePaint) // the first point is solid
        }

        // Dates under the points, kept inside the view (with large text the first and last ran off the edges).
        // If one would run into the date before it or into the last date (very large text on a narrow screen),
        // it's left out: its point still shows, and the screen-reader description has every value. The first
        // and last dates are always drawn.
        val axisY = height - 6 * dp
        val endLabel = labels.getOrElse(last) { "" }
        val endHalf = axisText.measureText(endLabel) / 2
        val endX = labelX(x(last), endHalf)
        var drawnTo = Float.NEGATIVE_INFINITY
        for (i in 0 until last) {
            val label = labels.getOrElse(i) { "" }
            val half = axisText.measureText(label) / 2
            val lx = labelX(x(i), half)
            if (i > 0 && (lx - half < drawnTo + labelGap || lx + half > endX - endHalf - labelGap)) continue
            canvas.drawText(label, lx, axisY, axisText)
            drawnTo = lx + half
        }
        canvas.drawText(endLabel, endX, axisY, axisText)

        val firstText = format(values[0])
        val firstHalf = valueText.measureText(firstText) / 2
        val firstX = labelX(x(0), firstHalf)
        val firstY = labelY(y(values[0]))
        valueText.color = secondary
        canvas.drawText(firstText, firstX, firstY, valueText)
        val lastText = format(values[last])
        val lastHalf = valueText.measureText(lastText) / 2
        val lastX = labelX(x(last), lastHalf)
        val lastY = labelY(y(values[last]))
        valueText.color = if (values[last] < 0) negative else textColor
        canvas.drawText(lastText, lastX, lastY, valueText)
        if (showLow && low != last && low != 0) {
            val text = "Low ${format(values[low])}"
            val half = valueText.measureText(text) / 2
            fun hitsFirst(lx: Float, ly: Float) = overlaps(lx, half, ly, firstX, firstHalf, firstY)
            fun hitsEither(lx: Float, ly: Float) = hitsFirst(lx, ly) || overlaps(lx, half, ly, lastX, lastHalf, lastY)
            val px = x(low)
            val py = y(values[low])
            var lx = labelX(px, half)
            var ly = labelY(py)
            // A small dip right next to the first or last point puts this label on top of theirs (mostly with
            // large text). Then it goes under its point if that stays clear of the dates, or else slides sideways
            // off the other label, as long as it still spans its own point. Otherwise it stays where it was.
            if (hitsEither(lx, ly)) {
                val under = below(py)
                val slid = if (hitsFirst(lx, ly)) firstX + firstHalf + labelGap + half else lastX - lastHalf - labelGap - half
                if (under + valueText.descent() <= axisY + axisText.ascent() && !hitsEither(lx, under)) {
                    ly = under
                } else if (abs(slid - px) <= half && slid >= half && slid <= width - half && !hitsEither(slid, ly)) {
                    lx = slid
                }
            }
            valueText.color = if (values[low] < 0) negative else textColor
            canvas.drawText(text, lx, ly, valueText)
        }
    }

    // A label's centre: on its point, but kept inside the view (centred if it's wider than the view).
    private fun labelX(px: Float, half: Float) = if (2 * half >= width) width / 2f else px.coerceIn(half, width - half)

    // A value label's baseline: above its point, or below it if there's no room above.
    private fun labelY(py: Float): Float {
        val above = py - 12 * dp
        return if (above < valueText.textSize) below(py) else above
    }

    // Below the point with the same gap at any text size (22dp at the default size, as before).
    private fun below(py: Float) = py + 10 * dp + valueText.textSize

    // Whether two value labels' text boxes overlap (both are one line of valueText, so they're the same height).
    private fun overlaps(ax: Float, aHalf: Float, ay: Float, bx: Float, bHalf: Float, by: Float) =
        abs(ax - bx) < aHalf + bHalf && abs(ay - by) < valueText.descent() - valueText.ascent()

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
