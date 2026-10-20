package com.gh00ul.budget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

// The Paydays list as a timeline: a line runs down through the rows' date badges (forecast_row's chip), behind them,
// and the end-of-month row (forecast_end_row) gets a ring on the line. The line stays straight, at the first badge's
// center, even when large text makes a badge wider.
class Timeline(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * dp
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.card_rim)
    }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.card) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        color = context.getColor(R.color.accent)
    }
    private val rect = Rect() // reused on every draw

    init {
        setWillNotDraw(false) // a LinearLayout without a background skips onDraw otherwise
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        invalidate() // a row opened or closed: the line and the rings move with it
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var lineX = Float.NaN
        var firstY = Float.NaN
        var lastY = Float.NaN
        for (i in 0 until childCount) {
            val row = getChildAt(i)
            if (row.visibility != View.VISIBLE) continue
            val y = row.findViewById<View>(R.id.chip)?.let { chip ->
                rect.set(0, 0, chip.width, chip.height)
                offsetDescendantRectToMyCoords(chip, rect)
                if (lineX.isNaN()) lineX = rect.exactCenterX()
                rect.exactCenterY()
            } ?: (row.top + row.height / 2f)
            if (firstY.isNaN()) firstY = y
            lastY = y
        }
        if (lineX.isNaN() || firstY == lastY) return
        canvas.drawLine(lineX, firstY, lineX, lastY, linePaint)
        val radius = 6 * dp
        for (i in 0 until childCount) {
            val row = getChildAt(i)
            if (row.visibility != View.VISIBLE || row.id != R.id.end_row) continue
            val y = row.top + row.height / 2f
            canvas.drawCircle(lineX, y, radius, ringFill)
            canvas.drawCircle(lineX, y, radius, ringPaint)
        }
    }
}
