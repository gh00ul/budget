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

// Line chart of the balance after each pay period, with the lowest point labeled.
class BalanceChart(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var labels: List<String> = emptyList()
    private var values: List<Double> = emptyList()
    private var format: (Double) -> String = { it.toString() }

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.card) }
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
    private val lowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * sp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setData(labels: List<String>, values: List<Double>, format: (Double) -> String) {
        this.labels = labels
        this.values = values
        this.format = format
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return
        val positive = context.getColor(R.color.positive)
        val negative = context.getColor(R.color.negative)
        val anyNegative = values.any { it < 0 }
        val color = if (anyNegative) negative else positive

        val left = 16 * dp
        val right = width - 16 * dp
        val top = 26 * dp
        val bottom = height - 24 * dp
        val minV = minOf(0.0, values.min())
        val maxV = maxOf(values.max(), 0.0)
        val span = (maxV - minV).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = left + (right - left) * i / (values.size - 1)
        fun y(v: Double) = top + ((maxV - v) / span * (bottom - top)).toFloat()

        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
        val area = Path(line).apply {
            lineTo(x(values.size - 1), bottom)
            lineTo(x(0), bottom)
            close()
        }
        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, withAlpha(color, 0x50), withAlpha(color, 0x00), Shader.TileMode.CLAMP)
        canvas.drawPath(area, fillPaint)
        if (anyNegative) canvas.drawLine(left, y(0.0), right, y(0.0), zeroPaint)
        linePaint.color = color
        canvas.drawPath(line, linePaint)

        val low = values.indices.minBy { values[it] }
        dotPaint.color = color
        values.forEachIndexed { i, v ->
            val radius = if (i == low) 6 * dp else 4 * dp
            canvas.drawCircle(x(i), y(v), radius, dotPaint)
            canvas.drawCircle(x(i), y(v), radius - 2 * dp, holePaint)
            canvas.drawText(labels.getOrElse(i) { "" }, x(i), height - 6 * dp, axisText)
        }

        val label = "Low ${format(values[low])}"
        lowText.color = if (values[low] < 0) negative else context.getColor(R.color.text)
        val half = lowText.measureText(label) / 2
        val lx = x(low).coerceIn(half, width - half)
        val above = y(values[low]) - 12 * dp
        val ly = if (above < lowText.textSize) y(values[low]) + 22 * dp else above
        canvas.drawText(label, lx, ly, lowText)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
