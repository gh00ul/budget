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

// Line chart of the balance after each pay period. The first point is today (drawn filled, since it's
// real); the rest are forecasts. Labels today's and the final value, and the low point when the balance
// actually dips (or goes negative).
class BalanceChart(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var labels: List<String> = emptyList()
    private var values: List<Double> = emptyList()
    private var format: (Double) -> String = { it.toString() }

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
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
        textSize = 11 * sp
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_secondary)
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * sp
        textAlign = Paint.Align.CENTER
        typeface = medium
    }

    // `description` is what a screen reader says for the chart.
    fun setData(labels: List<String>, values: List<Double>, description: String, format: (Double) -> String) {
        this.labels = labels
        this.values = values
        this.format = format
        contentDescription = description
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2 || width == 0) return
        val positive = context.getColor(R.color.positive)
        val negative = context.getColor(R.color.negative)
        val textColor = context.getColor(R.color.text)
        val secondary = context.getColor(R.color.text_secondary)
        val anyNegative = values.any { it < 0 }
        val color = if (anyNegative) negative else positive

        // Room for the value labels above and the axis labels below, which grow with the font size.
        val left = 16 * dp
        val right = width - 16 * dp
        val top = valueText.textSize + 12 * dp
        val bottom = height - axisText.textSize - 14 * dp
        val minV = minOf(0.0, values.min())
        val maxV = maxOf(values.max(), 0.0)
        val span = (maxV - minV).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = left + (right - left) * i / (values.size - 1)
        fun y(v: Double) = top + ((maxV - v) / span * (bottom - top)).toFloat()

        canvas.drawLine(left, bottom, right, bottom, basePaint)

        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
        val area = Path(line).apply {
            lineTo(x(values.size - 1), bottom)
            lineTo(x(0), bottom)
            close()
        }
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, withAlpha(color, 0x30), withAlpha(color, 0x00), Shader.TileMode.CLAMP)
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
            if (i != 0) canvas.drawCircle(x(i), y(v), radius - 2 * dp, holePaint) // today is solid
            canvas.drawText(labels.getOrElse(i) { "" }, x(i), height - 6 * dp, axisText)
        }

        valueText.color = secondary
        drawLabel(canvas, format(values[0]), x(0), y(values[0]))
        valueText.color = if (values[last] < 0) negative else textColor
        drawLabel(canvas, format(values[last]), x(last), y(values[last]))
        if (showLow && low != last && low != 0) {
            valueText.color = if (values[low] < 0) negative else textColor
            drawLabel(canvas, "Low ${format(values[low])}", x(low), y(values[low]))
        }
    }

    // Draws a value label above its point (below it if there's no room), kept inside the view.
    private fun drawLabel(canvas: Canvas, text: String, px: Float, py: Float) {
        val half = valueText.measureText(text) / 2
        val lx = if (2 * half >= width) width / 2f else px.coerceIn(half, width - half)
        val above = py - 12 * dp
        val ly = if (above < valueText.textSize) py + 22 * dp else above
        canvas.drawText(text, lx, ly, valueText)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
