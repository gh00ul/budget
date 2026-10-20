package com.gh00ul.budget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// The 4×2 home-screen widget: Safe to spend this week, with the bank balance and the next bill. It works the numbers
// out itself (Budget.kt), so they move on with the days while the app stays closed: it's redrawn whenever the app
// saves, just after midnight, and every few hours. It never syncs the bank; opening the app does that.
class BudgetWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, manager, ids, null)
    }

    // Resized: show more or less of it.
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        draw(context, manager, intArrayOf(id), null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MIDNIGHT, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> update(context, null)
            else -> super.onReceive(context, intent)
        }
    }

    // The last one was removed from the home screen.
    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(midnightIntent(context))
    }

    companion object {
        private const val ACTION_MIDNIGHT = "com.gh00ul.budget.WIDGET_MIDNIGHT"

        // Redraws every Budget widget. `budget` = the app's own copy, already loaded; null reads the saved one.
        // Never throws: a widget problem mustn't stop the app from saving.
        internal fun update(context: Context, budget: Budget?) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return // no widgets on this device
                val ids = manager.getAppWidgetIds(ComponentName(context, BudgetWidget::class.java))
                if (ids.isNotEmpty()) draw(context, manager, ids, budget)
            } catch (e: RuntimeException) {
                Log.w("Budget", "Widget update failed: ${e.javaClass.name}")
            }
        }

        fun isOnHomeScreen(context: Context) =
            AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, BudgetWidget::class.java))
                ?.isNotEmpty() == true

        // Android 8+ launchers can add it straight from the app (after asking).
        fun canPin(context: Context) = AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true

        fun pin(context: Context) =
            AppWidgetManager.getInstance(context)?.requestPinAppWidget(ComponentName(context, BudgetWidget::class.java), null, null) == true

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray, given: Budget?) {
            val shown = try {
                val budget = given ?: Budget(context.applicationContext).apply { load() }
                if (budget.isSetUp()) Shown.from(budget, LocalDate.now()) else Shown.notSetUp()
            } catch (e: Exception) {
                // The type and where (adb logcat -s Budget), not the message, which could hold a number.
                Log.e("Budget", "Widget couldn't work out the numbers: ${e.javaClass.name}\n" +
                    e.stackTrace.take(8).joinToString("\n") { "    at $it" })
                Shown.problem()
            }
            for (id in ids) manager.updateAppWidget(id, views(context, shown, manager.getAppWidgetOptions(id)))
            scheduleMidnight(context)
        }

        private fun views(context: Context, shown: Shown, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_budget)
            // The size on the home screen, in dp (portrait: the narrowest width and the tallest height). 0 = unknown.
            // Everything needs about 205dp of height at normal text size (less with smaller text); smaller, the
            // progress line, then the note go, then the bank and next-bill row instead (the note comes back), so
            // what's shown is never cut off.
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            val fontScale = context.resources.configuration.fontScale
            fun fits(dp: Int) = height == 0 || height >= dp * fontScale

            views.setTextViewText(R.id.widget_label, smallCaps("Safe to spend this week"))
            views.setTextViewText(R.id.widget_bank_label, smallCaps("Bank"))
            views.setTextViewText(R.id.widget_bill_label, smallCaps("Next bill"))
            views.setTextViewText(R.id.widget_amount, shown.amount)
            views.setTextViewTextSize(R.id.widget_amount, TypedValue.COMPLEX_UNIT_SP, if (width in 1..229) 30f else 38f)
            // Bills not covered: the amount and why in a brick red, on the same card.
            val color = context.getColor(if (shown.short) R.color.widget_short else R.color.widget_text)
            views.setTextColor(R.id.widget_amount, color)
            views.setTextColor(R.id.widget_note,
                if (shown.short) color else context.getColor(R.color.widget_text_secondary))
            views.setTextViewText(R.id.widget_note, shown.note)
            val progress = shown.progress
            val details = shown.details && fits(145)
            val line = if (progress != null && fits(205)) View.VISIBLE else View.GONE
            views.setViewVisibility(R.id.widget_progress, line)
            views.setViewVisibility(R.id.widget_spent_row, line)
            if (progress != null) views.setProgressBar(R.id.widget_progress, 1000, progress, false)
            views.setTextViewText(R.id.widget_spent, shown.spent)
            views.setTextViewText(R.id.widget_week, shown.weekMoney)
            views.setViewVisibility(R.id.widget_note, if (!details || fits(161)) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_details, if (details) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_bank, shown.bank)
            views.setTextViewText(R.id.widget_bill, shown.bill)
            views.setContentDescription(android.R.id.background, shown.description)

            // Tapping it opens the app; the small button updates the balance (or shows the bank, when linked).
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(android.R.id.background,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            views.setOnClickPendingIntent(R.id.widget_action,
                PendingIntent.getActivity(context, 1, Intent(open).setAction(ACTION_UPDATE_BALANCE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            views.setImageViewResource(R.id.widget_action, if (shown.linked) R.drawable.ic_sync else R.drawable.ic_edit)
            views.setContentDescription(R.id.widget_action, if (shown.linked) "Bank balance and sync" else "Update bank balance")
            return views
        }

        // "Due in 3 days" moves on at midnight, and so does the pay week on payday. The alarm doesn't wake the phone:
        // it goes off the next time the phone is in use. Android clears alarms on restart; the regular update
        // (updatePeriodMillis) and the redraw Android does after an app update pick it up again.
        private fun scheduleMidnight(context: Context) {
            val now = ZonedDateTime.now()
            val at = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1).toInstant().toEpochMilli()
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, midnightIntent(context))
        }

        private fun midnightIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, BudgetWidget::class.java).setAction(ACTION_MIDNIGHT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // What the widget says.
    private class Shown(
        val amount: CharSequence,
        val short: Boolean, // bills aren't covered: the amount and note turn brick red
        val note: String,
        val progress: Int?, // how much of this week's money is spent, per mille; null = not known
        val spent: String, // under the progress line: "Spent $96" ... "of $226"
        val weekMoney: String,
        val details: Boolean, // the bank / next bill row
        val bank: String,
        val bill: String,
        val linked: Boolean,
        val description: String,
    ) {
        companion object {
            private val money: NumberFormat get() = NumberFormat.getCurrencyInstance()
            private val dayFormat get() = DateTimeFormatter.ofPattern("EEE, MMM d")
            private val shortDate get() = DateTimeFormatter.ofPattern("MMM d")
            private val weekday get() = DateTimeFormatter.ofPattern("EEE")

            fun from(budget: Budget, today: LocalDate): Shown {
                val money = money
                val week = budget.computeWeek(today)
                val shown = maxOf(0.0, week.safe)
                val until = week.nextPayday.format(if (week.daysLeft < 7) weekday else dayFormat)
                // The Summary's note under Safe to spend, shortened to fit one line.
                val note = when {
                    budget.bills.isEmpty() && !budget.billsNone -> "No bills added yet, so this may be too high"
                    week.free < 0 -> "${money.format(-week.free)} short for bills"
                    week.pay < week.billsShare -> "Your bills cost more than your pay"
                    week.spendingMoney < 0 -> "${money.format(-week.spendingMoney)} over this week's money"
                    week.free < week.spendingMoney -> "Lowered so your bills stay covered"
                    week.daysLeft == 1L -> "Payday tomorrow"
                    week.daysLeft > 1 && shown > 0 -> "Until payday $until · ${wholeOrCents(money, cents(shown / week.daysLeft))} a day"
                    else -> "Until payday $until"
                }
                val spent = week.spent
                val progress = if (spent != null && week.weekMoney > 0) (spent / week.weekMoney * 1000).toInt().coerceIn(0, 1000) else null
                val spentText = spent?.let { "Spent ${wholeOrCents(money, it)}" }.orEmpty()
                val bank = (if (week.bankIsEstimate) "≈ " else "") + money.format(week.bank)
                val next = budget.bills.minByOrNull { it.nextDue(today) }
                val bill = next?.let {
                    val due = it.nextDue(today)
                    val days = ChronoUnit.DAYS.between(today, due)
                    val whenDue = when {
                        days == 0L -> "today"
                        days == 1L -> "tomorrow"
                        days < 7 -> due.format(weekday)
                        else -> due.format(shortDate)
                    }
                    "${it.name} ${wholeOrCents(money, it.amount)} · $whenDue"
                } ?: "None"
                return Shown(
                    amount = money.format(shown), short = week.safe < 0, note = note, progress = progress,
                    spent = spentText, weekMoney = "of ${wholeOrCents(money, week.weekMoney)}", details = true,
                    bank = bank, bill = bill, linked = budget.bank.isLinked,
                    description = "Budget. Safe to spend this week ${money.format(shown)}. $note. Bank $bank. Next bill $bill.",
                )
            }

            fun notSetUp() = message("Open Budget to finish setting up")

            fun problem() = message("Open Budget to see your numbers")

            private fun message(note: String) = Shown(
                amount = "–", short = false, note = note, progress = null, spent = "", weekMoney = "", details = false,
                bank = "", bill = "", linked = false, description = "Budget. $note.",
            )

            // "$65" for whole dollars, "$15.49" otherwise.
            private fun wholeOrCents(money: NumberFormat, amount: Double): String =
                if (amount == Math.rint(amount)) (money.clone() as NumberFormat).apply { maximumFractionDigits = 0 }.format(amount)
                else money.format(amount)
        }
    }
}

// Small capitals, like the home screen clock's "Sunrise 6:32 AM": capitals stay full size and lowercase letters become
// smaller capitals. Done by hand because the phone's font may not have small capitals. Used by the widget and the
// app's Safe to spend card.
internal fun smallCaps(text: String): CharSequence {
    val caps = SpannableString(text.uppercase())
    var i = 0
    while (i < text.length) {
        if (!text[i].isLowerCase()) { i++; continue }
        val start = i
        while (i < text.length && text[i].isLowerCase()) i++
        caps.setSpan(RelativeSizeSpan(0.8f), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return caps
}
