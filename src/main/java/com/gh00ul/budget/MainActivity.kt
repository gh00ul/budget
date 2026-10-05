package com.gh00ul.budget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

private const val REPO = "gh00ul/budget"

// Background colors for the round initial next to each bill, handed out in alphabetical order so
// neighboring bills don't share a color.
private val AVATAR_COLORS = intArrayOf(
    0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFFE53935.toInt(),
    0xFF00897B.toInt(), 0xFF3949AB.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt(), 0xFF00ACC1.toInt(),
)

class MainActivity : Activity() {
    // How often a bill repeats: every `days` days, or every `months` months on the same day of the month.
    private enum class Freq(val label: String, val days: Int, val months: Int) {
        MONTHLY("Monthly", 0, 1),
        WEEKLY("Weekly", 7, 0),
        BIWEEKLY("Every 2 weeks", 14, 0),
        QUARTERLY("Every 3 months", 0, 3),
        SEMIANNUAL("Every 6 months", 0, 6),
        YEARLY("Yearly", 0, 12);

        val perMonth: Double get() = if (days > 0) 365.25 / 12 / days else 1.0 / months
    }

    // date = the first (or any) date the bill is due; it repeats from there on.
    private class Bill(val name: String, val amount: Double, val freq: Freq, val date: LocalDate) {
        // Every due date in [from, until).
        fun dueDates(from: LocalDate, until: LocalDate): List<LocalDate> {
            val start = if (from.isAfter(date)) from else date
            val dates = mutableListOf<LocalDate>()
            if (freq.days > 0) {
                val offset = Math.floorMod(ChronoUnit.DAYS.between(date, start), freq.days.toLong())
                var due = if (offset == 0L) start else start.plusDays(freq.days - offset)
                while (due.isBefore(until)) {
                    dates += due
                    due = due.plusDays(freq.days.toLong())
                }
            } else {
                val first = YearMonth.from(date)
                var month = YearMonth.from(start)
                while (month.atDay(1).isBefore(until)) {
                    if (Math.floorMod(ChronoUnit.MONTHS.between(first, month), freq.months.toLong()) == 0L) {
                        val due = month.atDay(minOf(date.dayOfMonth, month.lengthOfMonth()))
                        if (!due.isBefore(start) && due.isBefore(until)) dates += due
                    }
                    month = month.plusMonths(1)
                }
            }
            return dates
        }

        fun nextDue(today: LocalDate) = dueDates(today, today.plusYears(1).plusDays(1)).first()
    }

    private class Tab(val label: String, val icon: Int, val page: Int)

    private val tabs = listOf(
        Tab("Summary", R.drawable.ic_tab_summary, R.id.page_summary),
        Tab("Paydays", R.drawable.ic_tab_paydays, R.id.page_paydays),
        Tab("Bills", R.drawable.ic_tab_bills, R.id.page_bills),
        Tab("Settings", R.drawable.ic_tab_settings, R.id.page_settings),
    )
    private val navItems = mutableListOf<View>()
    private var currentTab = 0

    private val prefs by lazy { getSharedPreferences("budget", MODE_PRIVATE) }
    private val money = NumberFormat.getCurrencyInstance()
    private val decimalSeparator = (money as? DecimalFormat)?.decimalFormatSymbols?.monetaryDecimalSeparator ?: '.'
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val shortDate = DateTimeFormatter.ofPattern("MMM d")
    private val bills = mutableListOf<Bill>()
    private var balance = 0.0
    private var balanceUpdated: LocalDate? = null
    // The first balance entered in the current pay period. Later balances are compared to it to work out
    // how much has been spent since payday.
    private var weekStart: LocalDate? = null // the payday that began the period
    private var weekStartBalance = 0.0
    private var weekStartTaken: LocalDate? = null // the day that balance was entered
    private var weeklyIncome = 0.0
    private var payday = DayOfWeek.FRIDAY
    // One-off paycheck amounts (overtime, short week) that replace the weekly income on that date.
    private val paycheckChanges = mutableMapOf<LocalDate, Double>()
    private var updateUrl: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var undoAction: (() -> Unit)? = null
    private val hideUndo = Runnable {
        undoAction = null
        undoBar.animate().alpha(0f).setDuration(150).withEndAction { undoBar.visibility = View.GONE }.start()
    }
    private var heroShown: Double? = null
    private var heroAnimator: ValueAnimator? = null

    // Theme colors (they change in dark mode).
    private val positive by lazy { getColor(R.color.positive) }
    private val negative by lazy { getColor(R.color.negative) }
    private val warning by lazy { getColor(R.color.warning) }
    private val secondary by lazy { getColor(R.color.text_secondary) }
    private val textColor by lazy { getColor(R.color.text) }
    private val accent by lazy { getColor(R.color.chip_text) }

    private lateinit var billList: LinearLayout
    private lateinit var billsEmpty: View
    private lateinit var billsHint: View
    private lateinit var billsSubtitle: TextView
    private lateinit var forecastList: LinearLayout
    private lateinit var chartCard: View
    private lateinit var chart: BalanceChart
    private lateinit var paydaysSubtitle: TextView
    private lateinit var endLabel: TextView
    private lateinit var endBalance: TextView
    private lateinit var hero: View
    private lateinit var heroAmount: TextView
    private lateinit var heroNote: TextView
    private lateinit var heroBreakdown: TextView
    private lateinit var heroEndLabel: TextView
    private lateinit var heroEnd: TextView
    private lateinit var heroNextCol: View
    private lateinit var heroNextLabel: TextView
    private lateinit var heroNext: TextView
    private lateinit var balanceValue: TextView
    private lateinit var balanceUpdatedView: TextView
    private lateinit var glancePayday: TextView
    private lateinit var glanceBill: TextView
    private lateinit var glanceDue: TextView
    private lateinit var incomeValue: TextView
    private lateinit var paydayValue: TextView
    private lateinit var updateBanner: View
    private lateinit var updateText: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var fab: View
    private lateinit var undoBar: View
    private lateinit var undoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        billList = findViewById(R.id.bill_list)
        billsEmpty = findViewById(R.id.bills_empty)
        billsHint = findViewById(R.id.bills_hint)
        billsSubtitle = findViewById(R.id.bills_subtitle)
        forecastList = findViewById(R.id.forecast_list)
        chartCard = findViewById(R.id.chart_card)
        chart = findViewById(R.id.chart)
        paydaysSubtitle = findViewById(R.id.paydays_subtitle)
        endLabel = findViewById(R.id.end_label)
        endBalance = findViewById(R.id.end_balance)
        hero = findViewById(R.id.hero)
        heroAmount = findViewById(R.id.hero_amount)
        heroNote = findViewById(R.id.hero_note)
        heroBreakdown = findViewById(R.id.hero_breakdown)
        heroEndLabel = findViewById(R.id.hero_end_label)
        heroEnd = findViewById(R.id.hero_end)
        heroNextCol = findViewById(R.id.hero_next_col)
        heroNextLabel = findViewById(R.id.hero_next_label)
        heroNext = findViewById(R.id.hero_next)
        balanceValue = findViewById(R.id.balance_value)
        balanceUpdatedView = findViewById(R.id.balance_updated)
        glancePayday = findViewById(R.id.glance_payday)
        glanceBill = findViewById(R.id.glance_bill)
        glanceDue = findViewById(R.id.glance_due)
        incomeValue = findViewById(R.id.income_value)
        paydayValue = findViewById(R.id.payday_value)
        updateBanner = findViewById(R.id.update_banner)
        updateText = findViewById(R.id.update_text)
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update_button)
        fab = findViewById(R.id.fab)
        undoBar = findViewById(R.id.undo_bar)
        undoText = findViewById(R.id.undo_text)

        fitToSystemBars()
        setUpTabs()
        load()

        findViewById<TextView>(R.id.today_label).text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        findViewById<View>(R.id.bills_card).clipToOutline = true // keeps swiped rows inside the rounded card

        findViewById<View>(R.id.balance_row).setOnClickListener { editBalance() }
        findViewById<View>(R.id.glance_payday_row).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.glance_bill_row).setOnClickListener { showTab(2) }
        findViewById<View>(R.id.glance_due_row).setOnClickListener { showTab(2) }
        findViewById<View>(R.id.income_row).setOnClickListener { editIncome() }
        findViewById<View>(R.id.payday_row).setOnClickListener { pickPayday() }
        findViewById<View>(R.id.check_row).setOnClickListener { checkForUpdate() }
        fab.setOnClickListener { openBillSheet(null) }
        updateButton.setOnClickListener { installUpdate() }
        findViewById<View>(R.id.undo_button).setOnClickListener {
            undoAction?.invoke()
            handler.removeCallbacks(hideUndo)
            hideUndo.run()
        }

        checkForUpdate()
    }

    // Redraw on every return to the app so "due in X days" and the paydays stay current.
    override fun onResume() {
        super.onResume()
        refresh()
    }

    // Back from another tab goes to Summary before leaving the app.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentTab != 0) showTab(0) else super.onBackPressed()
    }

    // ---------- Tabs and window ----------

    private fun setUpTabs() {
        val nav = findViewById<LinearLayout>(R.id.nav)
        tabs.forEachIndexed { i, tab ->
            val item = layoutInflater.inflate(R.layout.nav_item, nav, false)
            item.findViewById<ImageView>(R.id.nav_icon).setImageResource(tab.icon)
            item.findViewById<TextView>(R.id.nav_label).text = tab.label
            item.contentDescription = tab.label
            item.setOnClickListener { showTab(i) }
            nav.addView(item)
            navItems.add(item)
        }
        showTab(0, animate = false)
    }

    private fun showTab(index: Int, animate: Boolean = true) {
        val changed = index != currentTab
        currentTab = index
        tabs.forEachIndexed { i, tab ->
            val selected = i == index
            val page = findViewById<View>(tab.page)
            page.visibility = if (selected) View.VISIBLE else View.GONE
            if (selected && animate && changed) {
                page.alpha = 0f
                page.translationY = 12 * resources.displayMetrics.density
                page.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
            val item = navItems[i]
            item.isSelected = selected
            item.findViewById<View>(R.id.nav_pill).setBackgroundResource(if (selected) R.drawable.nav_pill else 0)
            item.findViewById<ImageView>(R.id.nav_icon).imageTintList =
                ColorStateList.valueOf(if (selected) accent else secondary)
            item.findViewById<TextView>(R.id.nav_label).apply {
                setTextColor(if (selected) textColor else secondary)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        fab.visibility = if (index == 2) View.VISIBLE else View.GONE
        hideKeyboard()
    }

    private fun hideKeyboard() {
        currentFocus?.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    // Android 15+ draws the app behind the status/navigation bars, so pad the pages and tab bar clear of
    // them. The tab bar's color runs down behind the phone's navigation buttons.
    @Suppress("DEPRECATION")
    private fun fitToSystemBars() {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else lightBars, lightBars)

        val pages = findViewById<View>(R.id.pages)
        val navBar = findViewById<View>(R.id.nav_bar)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboardOpen = insets.isVisible(WindowInsets.Type.ime())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            pages.setPadding(bars.left, bars.top, bars.right, if (keyboardOpen) keyboard.bottom else 0)
            navBar.setPadding(bars.left, 0, bars.right, bars.bottom)
            navBar.visibility = if (keyboardOpen) View.GONE else View.VISIBLE
            insets
        }
    }

    // ---------- Drawing the screens ----------

    private fun refresh() {
        showBills()
        recalculate()
        showSettings()
    }

    private fun showSettings() {
        incomeValue.text = money.format(weeklyIncome)
        paydayValue.text = payday.getDisplayName(TextStyle.FULL, Locale.getDefault())
        balanceValue.text = money.format(balance)
        val today = LocalDate.now()
        val days = balanceUpdated?.let { ChronoUnit.DAYS.between(it, today) }
        val trackingThisWeek = weekStart == lastPayday(today)
        balanceUpdatedView.text = when {
            days == null -> "Tap to update"
            !trackingThisWeek -> "Update to track this week's spending"
            days == 0L -> "Updated today"
            days == 1L -> "Updated yesterday"
            else -> "Updated $days days ago"
        }
        balanceUpdatedView.setTextColor(if (days == null || !trackingThisWeek || days >= 3) warning else secondary)
    }

    private fun showBills() {
        val today = LocalDate.now()
        val colorOrder = bills.map { it.name.lowercase() }.distinct().sorted()
        billList.removeAllViews()
        for (bill in bills.sortedBy { it.nextDue(today) }) {
            val row = layoutInflater.inflate(R.layout.bill_row, billList, false)
            val next = bill.nextDue(today)
            val daysLeft = ChronoUnit.DAYS.between(today, next)
            row.findViewById<TextView>(R.id.bill_row_avatar).apply {
                text = bill.name.take(1).uppercase()
                backgroundTintList = ColorStateList.valueOf(AVATAR_COLORS[colorOrder.indexOf(bill.name.lowercase()) % AVATAR_COLORS.size])
            }
            row.findViewById<TextView>(R.id.bill_row_name).text = bill.name
            row.findViewById<TextView>(R.id.bill_row_amount).text = money.format(bill.amount)
            row.findViewById<TextView>(R.id.bill_row_due).apply {
                text = buildString {
                    append("${next.format(shortDate)} · ${dueIn(daysLeft)}")
                    if (bill.freq != Freq.MONTHLY) append(" · ${bill.freq.label}")
                }
                setTextColor(if (daysLeft <= 3) warning else secondary)
            }
            makeSwipeable(row.findViewById(R.id.bill_row_content), onTap = { openBillSheet(bill) }, onSwiped = { deleteBill(bill) })
            billList.addView(row)
        }
        billsEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
        billsHint.visibility = if (bills.isEmpty()) View.GONE else View.VISIBLE
        val perMonth = bills.sumOf { it.amount * it.freq.perMonth }
        val allMonthly = bills.all { it.freq == Freq.MONTHLY }
        billsSubtitle.text = when {
            bills.isEmpty() -> "Add what you pay each month"
            else -> "${bills.size} bill${if (bills.size == 1) "" else "s"} · ${if (allMonthly) "" else "about "}${money.format(perMonth)} a month"
        }
        val dueSoon = bills.any { ChronoUnit.DAYS.between(today, it.nextDue(today)) <= 3 }
        navItems[2].findViewById<View>(R.id.nav_badge).visibility = if (dueSoon) View.VISIBLE else View.GONE
    }

    // Walks from today to the end of the month. Each payday adds a paycheck, and each bill comes out of
    // the pay period it's due in. Today's paycheck (if today is payday) is assumed to already be in the
    // balance; bills due today are assumed not paid yet.
    private fun recalculate() {
        val today = LocalDate.now()
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val afterMonth = monthEnd.plusDays(1)
        val nextPayday = today.with(TemporalAdjusters.next(payday))
        val paydays = generateSequence(nextPayday) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(monthEnd) }
            .toList()
        val periodStarts = listOf(today) + paydays
        fun paycheckOn(date: LocalDate) = paycheckChanges[date] ?: weeklyIncome

        forecastList.removeAllViews()
        var running = balance
        val chartLabels = mutableListOf<String>()
        val chartValues = mutableListOf<Double>()
        periodStarts.forEachIndexed { i, start ->
            val end = periodStarts.getOrNull(i + 1) ?: afterMonth
            // One entry per time a bill comes due in this pay period, in date order.
            val due = bills.flatMap { bill -> bill.dueDates(start, end).map { it to bill } }
                .sortedBy { it.first }
                .map { it.second }
            val billsDue = due.sumOf { it.amount }
            val isPayday = i > 0
            val paycheck = if (isPayday) paycheckOn(start) else 0.0
            running += paycheck - billsDue
            chartLabels += if (isPayday) start.dayOfMonth.toString() else "Now"
            chartValues += running

            val row = layoutInflater.inflate(R.layout.forecast_row, forecastList, false)
            row.findViewById<TextView>(R.id.chip_top).text =
                if (isPayday) start.format(DateTimeFormatter.ofPattern("EEE")) else "Today"
            row.findViewById<TextView>(R.id.chip_day).text = start.dayOfMonth.toString()
            if (!isPayday) {
                row.findViewById<View>(R.id.chip).setBackgroundResource(R.drawable.chip_today)
                row.findViewById<TextView>(R.id.chip_top).setTextColor(secondary)
                row.findViewById<TextView>(R.id.chip_day).setTextColor(textColor)
            }
            row.findViewById<TextView>(R.id.forecast_title).text = SpannableStringBuilder().apply {
                if (isPayday) {
                    append("+" + money.format(paycheck), ForegroundColorSpan(positive), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (start in paycheckChanges) {
                        val from = length
                        append("  changed")
                        setSpan(ForegroundColorSpan(secondary), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(0.8f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    append("Before payday", StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            row.findViewById<TextView>(R.id.forecast_bills).text = if (due.isEmpty()) {
                "No bills"
            } else {
                val names = due.groupingBy { it }.eachCount().entries
                    .joinToString { (bill, times) -> if (times > 1) "${bill.name} ×$times" else bill.name }
                SpannableStringBuilder()
                    .append("-" + money.format(billsDue), ForegroundColorSpan(negative), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    .append(" · $names")
            }
            showMoney(row.findViewById(R.id.forecast_left), running)
            if (isPayday) row.setOnClickListener { editPaycheck(start) }
            forecastList.addView(row)
        }

        val monthName = monthEnd.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        paydaysSubtitle.text = "Rest of $monthName · tap a payday to change its paycheck"
        endLabel.text = "End of $monthName"
        showMoney(endBalance, running)
        chartCard.visibility = if (chartValues.size >= 2) View.VISIBLE else View.GONE
        chart.setData(chartLabels, chartValues) { compactMoney(it) }

        // Summary. Safe to spend is this week's spending money: the paycheck minus a fair weekly share of
        // every bill (rent, yearly bills, ... spread evenly), minus what's been spent since payday. It's
        // never more than what's in the bank after the bills due before the next paycheck. Anything else
        // in the account is cushion.
        val periodPayday = lastPayday(today)
        val pay = paycheckOn(periodPayday)
        val billsPerWeek = bills.sumOf { it.amount * it.freq.perMonth } * 12 / 52
        val spendingMoney = pay - billsPerWeek
        val taken = weekStartTaken
        val spent = if (weekStart == periodPayday && taken != null) {
            // A drop in the balance that isn't explained by bills coming due counts as spending.
            val billsPaid = bills.sumOf { it.amount * it.dueDates(taken, today).size }
            maxOf(0.0, weekStartBalance - balance - billsPaid)
        } else {
            null // no balance entered since payday yet, so spending can't be worked out
        }
        val inBank = chartValues.first() // balance minus bills due before the next paycheck
        val fromPay = spendingMoney - (spent ?: 0.0)
        val safe = minOf(fromPay, inBank)
        val until = nextPayday.format(dayFormat)
        hero.setBackgroundResource(if (safe < 0) R.drawable.hero_red else R.drawable.hero_green)
        animateHero(maxOf(0.0, safe))
        heroNote.text = when {
            inBank < 0 -> "Bills due before $until are ${money.format(-inBank)} more than your balance"
            spendingMoney < 0 -> "Your bills cost ${money.format(-spendingMoney)} more than your weekly pay"
            fromPay < 0 -> "You're ${money.format(-fromPay)} over this week's spending money"
            inBank < fromPay -> "Limited by your bank balance until $until"
            else -> "Your spending money until $until"
        }
        heroBreakdown.text = buildString {
            append("${shortMoney(pay)} pay − ${shortMoney(billsPerWeek)} for bills")
            if (spent != null) append(" − ${shortMoney(spent)} spent")
        }
        heroEndLabel.text = "End of $monthName"
        heroEnd.text = bigMoney(running)
        heroNextLabel.text = "Cushion"
        heroNext.text = bigMoney(inBank - maxOf(0.0, safe))

        glancePayday.text = "${nextPayday.format(shortDate)} · +${money.format(paycheckOn(nextPayday))}"
        val nextBill = bills.minByOrNull { it.nextDue(today) }
        if (nextBill == null) {
            glanceBill.text = "None"
            glanceBill.setTextColor(secondary)
        } else {
            val daysLeft = ChronoUnit.DAYS.between(today, nextBill.nextDue(today))
            glanceBill.text = "${nextBill.name} · ${dueIn(daysLeft)}"
            glanceBill.setTextColor(if (daysLeft <= 3) warning else textColor)
        }
        val stillDue = bills.sumOf { it.amount * it.dueDates(today, afterMonth).size }
        glanceDue.text = if (stillDue == 0.0) "All paid" else money.format(stillDue)
    }

    // Counts the headline number up or down to its new value.
    private fun animateHero(target: Double) {
        val from = heroShown ?: target
        heroShown = target
        heroAnimator?.cancel()
        if (from == target) {
            heroAmount.text = bigMoney(target)
            return
        }
        heroAnimator = ValueAnimator.ofFloat(from.toFloat(), target.toFloat()).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener { heroAmount.text = bigMoney((it.animatedValue as Float).toDouble()) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    heroAmount.text = bigMoney(target)
                }
            })
            start()
        }
    }

    // ---------- Editing ----------

    private fun editBalance() = askAmount("Bank balance", "What's in your account right now?", balance, signed = true) {
        val today = LocalDate.now()
        balance = it ?: 0.0
        balanceUpdated = today
        if (weekStart != lastPayday(today)) {
            weekStart = lastPayday(today)
            weekStartBalance = balance
            weekStartTaken = today
        }
        save()
        refresh()
    }

    private fun lastPayday(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(payday))

    private fun editIncome() = askAmount("Weekly income", "Your usual paycheck each week.", weeklyIncome) {
        weeklyIncome = it ?: 0.0
        save()
        refresh()
    }

    private fun editPaycheck(date: LocalDate) = askAmount(
        "Paycheck on ${date.format(dayFormat)}",
        "Usually ${money.format(weeklyIncome)}. Enter this week's amount.",
        paycheckChanges[date],
        neutral = "Use usual" to {
            paycheckChanges.remove(date)
            save()
            refresh()
        },
    ) { amount ->
        if (amount == null || amount == weeklyIncome) paycheckChanges.remove(date) else paycheckChanges[date] = amount
        save()
        refresh()
    }

    private fun pickPayday() {
        val days = DayOfWeek.values()
        AlertDialog.Builder(this)
            .setTitle("Payday")
            .setSingleChoiceItems(days.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }.toTypedArray(), payday.ordinal) { dialog, which ->
                payday = days[which]
                save()
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Small dialog with one money field and the keyboard already up.
    private fun askAmount(
        title: String,
        message: String,
        current: Double?,
        signed: Boolean = false,
        neutral: Pair<String, () -> Unit>? = null,
        onSave: (Double?) -> Unit,
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            hint = "0.00"
            current?.takeIf { it != 0.0 }?.let { setText(plain(it)) }
            setSelectAllOnFocus(true)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setPositiveButton("Save") { _, _ -> onSave(parseMoney(input.text.toString())) }
            .setNegativeButton("Cancel", null)
        neutral?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    // Slide-up sheet for adding a bill (existing == null) or editing one.
    private fun openBillSheet(existing: Bill?) {
        val today = LocalDate.now()
        val dialog = Dialog(this, R.style.SheetDialog)
        dialog.setContentView(R.layout.sheet_bill)
        val window = dialog.window!!
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = dialog.findViewById<View>(R.id.sheet_root)
        val scrim = dialog.findViewById<View>(R.id.sheet_scrim)
        val sheet = dialog.findViewById<View>(R.id.sheet)
        val nameBox = dialog.findViewById<EditText>(R.id.sheet_name)
        val amountBox = dialog.findViewById<EditText>(R.id.sheet_amount)
        val freqSpinner = dialog.findViewById<Spinner>(R.id.sheet_freq)
        val dateField = dialog.findViewById<TextView>(R.id.sheet_date)
        val saveButton = dialog.findViewById<Button>(R.id.sheet_save)
        val deleteButton = dialog.findViewById<View>(R.id.sheet_delete)

        // Run the sheet down behind the navigation buttons; if the phone keeps the window above them
        // instead, color that strip to match the sheet.
        @Suppress("DEPRECATION")
        window.navigationBarColor = getColor(R.color.card)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
            window.isNavigationBarContrastEnforced = false
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val lightNav = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else lightNav, lightNav)
            val basePadding = sheet.paddingBottom
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                sheet.setPadding(sheet.paddingLeft, sheet.paddingTop, sheet.paddingRight, basePadding + maxOf(bars.bottom, keyboard.bottom))
                insets
            }
        }

        dialog.findViewById<TextView>(R.id.sheet_title).text = if (existing == null) "New bill" else "Edit bill"
        saveButton.text = if (existing == null) "Add bill" else "Save"
        deleteButton.visibility = if (existing == null) View.GONE else View.VISIBLE
        freqSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, Freq.values().map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        // When editing, keep the bill's original start date unless a new one is picked (so a bill due
        // on the 31st stays on the 31st).
        var pickedDate: LocalDate? = null
        if (existing != null) {
            nameBox.setText(existing.name)
            amountBox.setText(plain(existing.amount))
            freqSpinner.setSelection(existing.freq.ordinal)
            dateField.text = "Due ${existing.nextDue(today).format(dayFormat)}"
        }
        fun pickDate() {
            val start = pickedDate ?: existing?.nextDue(today) ?: today
            DatePickerDialog(this, { _, year, month, day ->
                pickedDate = LocalDate.of(year, month + 1, day)
                dateField.text = "Due ${pickedDate!!.format(dayFormat)}"
            }, start.year, start.monthValue - 1, start.dayOfMonth).show()
        }
        dateField.setOnClickListener { pickDate() }

        fun close(then: () -> Unit = {}) {
            hideKeyboardIn(dialog)
            scrim.animate().alpha(0f).setDuration(180).start()
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    dialog.dismiss()
                    then()
                }.start()
        }
        scrim.setOnClickListener { close() }

        saveButton.setOnClickListener {
            val name = nameBox.text.toString().trim()
            val amount = parseMoney(amountBox.text.toString())
            val date = pickedDate ?: existing?.date
            when {
                name.isEmpty() -> nameBox.requestFocus()
                amount == null -> amountBox.requestFocus()
                date == null -> pickDate()
                else -> {
                    val bill = Bill(name, amount, Freq.values()[freqSpinner.selectedItemPosition], date)
                    val index = bills.indexOf(existing)
                    if (index >= 0) bills[index] = bill else bills.add(bill)
                    save()
                    haptic(saveButton)
                    close { refresh() }
                }
            }
        }
        deleteButton.setOnClickListener { close { if (existing != null) deleteBill(existing) } }

        // Slide up from the bottom.
        scrim.alpha = 0f
        sheet.visibility = View.INVISIBLE
        dialog.show()
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(200).start()
            sheet.animate().translationY(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
            if (existing == null) {
                nameBox.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(nameBox, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideKeyboardIn(dialog: Dialog) {
        val token = dialog.window?.decorView?.windowToken ?: return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(token, 0)
    }

    private fun deleteBill(bill: Bill) {
        val index = bills.indexOf(bill)
        if (index < 0) return
        bills.removeAt(index)
        save()
        refresh()
        haptic(billList)
        showUndo("Deleted ${bill.name}") {
            bills.add(minOf(index, bills.size), bill)
            save()
            refresh()
        }
    }

    private fun showUndo(message: String, action: () -> Unit) {
        undoText.text = message
        undoAction = action
        handler.removeCallbacks(hideUndo)
        undoBar.animate().cancel()
        undoBar.alpha = 0f
        undoBar.visibility = View.VISIBLE
        undoBar.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed(hideUndo, 5000)
    }

    // Swipe a bill row left to delete it; a plain tap opens it for editing.
    @SuppressLint("ClickableViewAccessibility")
    private fun makeSwipeable(content: View, onTap: () -> Unit, onSwiped: () -> Unit) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var swiping = false
        var moved = false
        content.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    swiping = false
                    moved = false
                    view.drawableHotspotChanged(event.x, event.y)
                    view.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        view.isPressed = false
                        if (abs(dx) > abs(dy) && dx < 0) {
                            swiping = true
                            view.parent.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    if (swiping) view.translationX = minOf(0f, dx)
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    when {
                        swiping && -view.translationX > view.width * 0.35f ->
                            view.animate().translationX(-view.width.toFloat()).setDuration(160).withEndAction { onSwiped() }.start()
                        swiping -> view.animate().translationX(0f).setDuration(160).start()
                        !moved -> onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.animate().translationX(0f).setDuration(160).start()
                }
            }
            true
        }
    }

    // ---------- Formatting ----------

    private fun dueIn(days: Long) = when (days) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $days days"
    }

    private fun showMoney(view: TextView, amount: Double) {
        view.text = money.format(amount)
        view.setTextColor(if (amount < 0) negative else positive)
    }

    // "$3,064.50" with the cents drawn smaller.
    private fun bigMoney(amount: Double): CharSequence {
        val text = money.format(amount)
        val dot = text.lastIndexOf(decimalSeparator)
        if (dot < 0) return text
        return SpannableString(text).apply { setSpan(RelativeSizeSpan(0.6f), dot, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    // "$750" for whole dollars, "$424.48" otherwise.
    private fun shortMoney(amount: Double): String =
        if (amount == Math.rint(amount)) compactMoney(amount) else money.format(amount)

    // "$1,045" for chart labels.
    private fun compactMoney(amount: Double): String =
        (money.clone() as NumberFormat).apply { maximumFractionDigits = 0 }.format(amount)

    private fun haptic(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun plain(amount: Double) = amount.toBigDecimal().stripTrailingZeros().toPlainString()

    private fun parseMoney(text: String): Double? = text.replace(Regex("[^0-9.-]"), "").toDoubleOrNull()

    // ---------- Saving ----------

    private fun load() {
        balance = prefs.getString("balance", null)?.toDoubleOrNull() ?: 0.0
        balanceUpdated = prefs.getString("balance_updated", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        weeklyIncome = prefs.getString("weekly_income", null)?.toDoubleOrNull() ?: 0.0
        payday = DayOfWeek.of(prefs.getInt("payday", DayOfWeek.FRIDAY.value))
        val saved = JSONArray(prefs.getString("bills", "[]"))
        for (i in 0 until saved.length()) {
            val bill = saved.getJSONObject(i)
            val (freq, date) = if (bill.has("freq")) {
                Freq.valueOf(bill.getString("freq")) to LocalDate.parse(bill.getString("date"))
            } else {
                // Bills saved before v2.6 were monthly with just a day of the month. January has 31 days,
                // so any day fits.
                Freq.MONTHLY to LocalDate.of(LocalDate.now().year, 1, bill.optInt("day", 1))
            }
            bills.add(Bill(bill.getString("name"), bill.getDouble("amount"), freq, date))
        }

        // Keep this pay period's paycheck change (it sets this week's spending money); drop older ones.
        val periodStart = lastPayday(LocalDate.now())
        val changes = JSONObject(prefs.getString("paycheck_changes", "{}"))
        for (key in changes.keys()) {
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
            if (!date.isBefore(periodStart)) paycheckChanges[date] = changes.getDouble(key)
        }

        weekStart = prefs.getString("week_start", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        weekStartBalance = prefs.getString("week_start_balance", null)?.toDoubleOrNull() ?: 0.0
        weekStartTaken = prefs.getString("week_start_taken", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    private fun save() {
        val saved = JSONArray()
        for (bill in bills) {
            saved.put(
                JSONObject().put("name", bill.name).put("amount", bill.amount)
                    .put("freq", bill.freq.name).put("date", bill.date.toString())
            )
        }
        val changes = JSONObject()
        for ((date, amount) in paycheckChanges) changes.put(date.toString(), amount)
        prefs.edit()
            .putString("balance", balance.toString())
            .putString("balance_updated", balanceUpdated?.toString())
            .putString("weekly_income", weeklyIncome.toString())
            .putInt("payday", payday.value)
            .putString("bills", saved.toString())
            .putString("paycheck_changes", changes.toString())
            .putString("week_start", weekStart?.toString())
            .putString("week_start_balance", weekStartBalance.toString())
            .putString("week_start_taken", weekStartTaken?.toString())
            .apply()
    }

    // ---------- Updates ----------

    // Looks at the latest GitHub Release; if it's newer than this build, shows the update banner.
    private fun checkForUpdate() {
        val current = BuildConfig.VERSION_NAME
        updateStatus.text = "Checking…"
        thread {
            val result = runCatching {
                val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val latest = release.getString("tag_name").removePrefix("v")
                val assets = release.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                if (apk != null && isNewer(latest, current)) latest to apk.getString("browser_download_url") else null
            }
            runOnUiThread {
                val update = result.getOrNull()
                when {
                    result.isFailure -> updateStatus.text = "Couldn't check · version $current"
                    update == null -> updateStatus.text = "Up to date · version $current"
                    else -> {
                        updateUrl = update.second
                        updateStatus.text = "Version ${update.first} is available"
                        updateText.text = "Version ${update.first} is ready"
                        updateBanner.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // Streams the new APK into Android's installer; Android then asks you to confirm the update.
    private fun installUpdate() {
        val url = updateUrl ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Budget to install updates, then tap Update again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        updateButton.isEnabled = false
        updateText.text = "Downloading…"
        thread {
            val result = runCatching {
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    URL(url).openStream().use { input ->
                        session.openWrite("budget.apk", 0, -1).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val callback = PendingIntent.getBroadcast(
                        this, sessionId, Intent(this, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(callback.intentSender)
                }
            }
            runOnUiThread {
                updateButton.isEnabled = true
                updateText.text = result.exceptionOrNull()?.let { "Update failed: ${it.message}" } ?: "Installing…"
            }
        }
    }
}

// Receives the installer's result and shows Android's "Do you want to update this app?" prompt.
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val prompt = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> Toast.makeText(
                context, "Update failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG
            ).show()
        }
    }
}
