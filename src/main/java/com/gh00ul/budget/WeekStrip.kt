package com.gh00ul.budget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil

// The pay week on the Safe to spend card: a short bar per day, payday first, with the day's initial under it. Days
// gone and today are filled, the days to come are faint, and today's initial stands out. Only a picture (the card's
// note already says when payday is), so the layout hides it from screen readers.
class WeekStrip(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var days: List<String> = emptyList()
    private var today = -1

    private val dp = resources.displayMetrics.density
    private val barHeight = 6 * dp
    private val barGap = 6 * dp // between two bars
    private val textGap = 6 * dp // between the bars and the initials
    private val bar = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    private var doneColor = context.getColor(R.color.hero_day_done)
    private var restColor = context.getColor(R.color.hero_day)
    private var textColor = context.getColor(R.color.hero_text_secondary)
    private var todayColor = context.getColor(R.color.hero_text)

    // `days` are the initials from payday on; `today` is today's place in them.
    fun setWeek(days: List<String>, today: Int) {
        val resize = days.isEmpty() != this.days.isEmpty()
        this.days = days
        this.today = today
        if (resize) requestLayout()
        invalidate()
    }

    fun setColors(done: Int, rest: Int, text: Int, today: Int) {
        doneColor = done
        restColor = rest
        textColor = text
        todayColor = today
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val content = if (days.isEmpty()) 0f else barHeight + textGap + textPaint.fontSpacing
        val height = ceil(content).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty() || width == 0) return
        val count = days.size
        val step = (width - paddingLeft - paddingRight + barGap) / count
        val slot = step - barGap
        val top = paddingTop.toFloat()
        val baseline = top + barHeight + textGap - textPaint.ascent()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        days.forEachIndexed { i, label ->
            val left = paddingLeft + (if (rtl) count - 1 - i else i) * step
            bar.set(left, top, left + slot, top + barHeight)
            barPaint.color = if (i <= today) doneColor else restColor
            canvas.drawRoundRect(bar, barHeight / 2, barHeight / 2, barPaint)
            textPaint.color = if (i == today) todayColor else textColor
            textPaint.typeface = if (i == today) medium else regular
            canvas.drawText(label, left + slot / 2, baseline, textPaint)
        }
    }
}
