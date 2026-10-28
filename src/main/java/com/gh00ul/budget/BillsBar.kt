package com.gh00ul.budget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.ceil
import kotlin.math.max

// Where the bills' money goes, on the Bills tab's card: one bar split by each bill's share of a month, biggest first,
// with a legend under it (a dot, the name and the monthly amount), in two columns, or one with large text or a narrow
// screen. MainActivity groups the smallest bills as "Other" and sets what screen readers say for it.
class BillsBar(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    class Part(val name: String, val amount: Double, val label: String)

    private var parts: List<Part> = emptyList()
    private var shown = 1f // how much of the bar is drawn, while it grows in

    private val dp = resources.displayMetrics.density
    private val barHeight = 12 * dp
    private val segmentGap = 3 * dp
    private val legendTop = 16 * dp // between the bar and the legend
    private val rowGap = 8 * dp
    private val columnGap = 16 * dp
    private val dotRadius = 4 * dp
    private val dotGap = 8 * dp // dot to name
    private val nameGap = 8 * dp // name to amount

    private val colors = intArrayOf(
        context.getColor(R.color.bill_1), context.getColor(R.color.bill_2), context.getColor(R.color.bill_3),
        context.getColor(R.color.bill_4), context.getColor(R.color.bill_5),
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        color = context.getColor(R.color.hero_text)
    }
    private val amountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        color = context.getColor(R.color.hero_text_secondary)
    }

    // Reused on every draw (it runs each frame while the bar grows).
    private val bar = RectF()
    private val segment = RectF()
    private val clip = Path()

    private fun sp(size: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)

    // The parts in order, biggest first; a part's color follows its place (the fifth is for "Other").
    fun setParts(parts: List<Part>) {
        val resize = parts.size != this.parts.size
        this.parts = parts
        if (resize) requestLayout()
        invalidate()
    }

    // Grows the bar in from the start (skipped when Android's animations are off).
    fun playIntro() {
        if (parts.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun columns(width: Int) =
        if (resources.configuration.fontScale >= 1.3f || width < 280 * dp) 1 else 2

    private fun rowHeight() = namePaint.fontSpacing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = if (parts.isEmpty()) 0 else (parts.size + columns(width) - 1) / columns(width)
        val legend = if (rows == 0) 0f else legendTop + rows * rowHeight() + (rows - 1) * rowGap
        val height = ceil(barHeight + legend).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (parts.isEmpty() || width == 0) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()

        // The bar: rounded ends, a small gap between parts, each part at least a sliver wide.
        bar.set(left, top, right, top + barHeight)
        clip.reset()
        clip.addRoundRect(bar, barHeight / 2, barHeight / 2, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val total = parts.sumOf { it.amount }.takeIf { it > 0 } ?: 1.0
        val usable = (right - left) - segmentGap * (parts.size - 1)
        val drawTo = left + (right - left) * shown
        var x = left
        parts.forEachIndexed { i, part ->
            val w = max(2 * dp, (usable * part.amount / total).toFloat())
            val start = x
            val end = if (i == parts.lastIndex) right else minOf(right, x + w)
            if (start < drawTo) {
                if (rtl) segment.set(right - (minOf(end, drawTo) - left), top, right - (start - left), top + barHeight)
                else segment.set(start, top, minOf(end, drawTo), top + barHeight)
                fill.color = colors[minOf(i, colors.lastIndex)]
                canvas.drawRect(segment, fill)
            }
            x = end + segmentGap
        }
        canvas.restore()

        // The legend.
        val columns = columns(width)
        val cellWidth = ((right - left) - columnGap * (columns - 1)) / columns
        val rowHeight = rowHeight()
        parts.forEachIndexed { i, part ->
            val row = i / columns
            val column = i % columns
            val cellStart = left + column * (cellWidth + columnGap)
            val cellTop = top + barHeight + legendTop + row * (rowHeight + rowGap)
            val baseline = cellTop - namePaint.ascent()
            val amountWidth = amountPaint.measureText(part.label)
            val nameRoom = cellWidth - 2 * dotRadius - dotGap - nameGap - amountWidth
            val name = TextUtils.ellipsize(part.name, namePaint, max(0f, nameRoom), TextUtils.TruncateAt.END).toString()
            fill.color = colors[minOf(i, colors.lastIndex)]
            val centerY = cellTop + rowHeight / 2
            if (rtl) {
                val cellEnd = right - column * (cellWidth + columnGap)
                canvas.drawCircle(cellEnd - dotRadius, centerY, dotRadius, fill)
                namePaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(name, cellEnd - 2 * dotRadius - dotGap, baseline, namePaint)
                amountPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(part.label, cellEnd - cellWidth, baseline, amountPaint)
            } else {
                canvas.drawCircle(cellStart + dotRadius, centerY, dotRadius, fill)
                namePaint.textAlign = Paint.Align.LEFT
                canvas.drawText(name, cellStart + 2 * dotRadius + dotGap, baseline, namePaint)
                amountPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(part.label, cellStart + cellWidth, baseline, amountPaint)
            }
        }
    }
}
