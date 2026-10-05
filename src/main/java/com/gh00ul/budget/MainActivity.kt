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
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import kotlin.math.roundToLong

private const val REPO = "gh00ul/budget"

// Soft background + matching letter color for the round initial next to each bill.
private val AVATAR_COLORS = listOf(
    R.color.avatar_bg_1 to R.color.avatar_fg_1, R.color.avatar_bg_2 to R.color.avatar_fg_2,
    R.color.avatar_bg_3 to R.color.avatar_fg_3, R.color.avatar_bg_4 to R.color.avatar_fg_4,
    R.color.avatar_bg_5 to R.color.avatar_fg_5, R.color.avatar_bg_6 to R.color.avatar_fg_6,
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

        // Average days between due dates.
        val cycleDays: Double get() = if (days > 0) days.toDouble() else months * 365.25 / 12
    }

    // date = the first (or any) date the bill is due; it repeats from there on.
    private class Bill(val name: String, val amount: Double, val freq: Freq, val date: LocalDate) {
        // This bill's share of one week, e.g. $1,200 monthly rent ≈ $275.97 a week.
        val perWeek: Double get() = amount * 7 / freq.cycleDays

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

        // Next due date on or after today. A start date still ahead (even years ahead) is the next one.
        fun nextDue(today: LocalDate): LocalDate =
            if (!date.isBefore(today)) date
            else dueDates(today, today.plusYears(1).plusDays(1)).firstOrNull() ?: date
    }

    // Everything behind "Safe to spend" for the current pay week.
    private class Week(
        val payday: LocalDate, // the payday that started this pay period
        val nextPayday: LocalDate,
        val pay: Double,
        val billsShare: Double, // one week's share of all bills
        val daysTracked: Long, // 7, or fewer if spending tracking only started mid-week
        val spent: Double?, // null = no balance entered since payday, so spending is unknown
        val spendingMoney: Double, // (pay - share) for the tracked part of the week, minus spent
        val bank: Double, // the last balance entered, moved forward to today
        val bankIsEstimate: Boolean,
        val setAside: Double, // has to stay in the bank so every bill gets paid on time
        val tightest: Pair<Bill, LocalDate>?, // the bill that sets that amount
        val free: Double, // bank - setAside
        val safe: Double, // the smaller of spendingMoney and free
    )

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
    private var weeklyIncome = 0.0
    private var payday = DayOfWeek.FRIDAY
    // One-off paycheck amounts (overtime, short week) that replace the weekly income on that date.
    private val paycheckChanges = mutableMapOf<LocalDate, Double>()
    // Where "spent since payday" counts from: a balance (usually the last one, carried forward to payday)
    // and the day it applies to.
    private var weekStart: LocalDate? = null // the payday that began the period
    private var weekStartBalance = 0.0
    private var weekStartTaken: LocalDate? = null

    private var updateUrl: String? = null
    private var latestVersion: String? = null
    private var downloading = false

    private val handler = Handler(Looper.getMainLooper())
    private val pendingUndo = mutableListOf<Pair<Int, Bill>>() // (index it was at, bill)
    private val hideUndo = Runnable {
        pendingUndo.clear()
        undoBar.animate().alpha(0f).setDuration(150).withEndAction { undoBar.visibility = View.GONE }.start()
    }
    private var heroShown: Double? = null
    private var heroAnimator: ValueAnimator? = null
    private var sheetDialog: Dialog? = null

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
    private lateinit var heroCushion: TextView
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
        heroCushion = findViewById(R.id.hero_cushion)
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
        // The update check runs before anything reads saved data, so a data problem can never block an
        // update that fixes it.
        if (savedInstanceState == null) clearStaleInstallSessions()
        checkForUpdate()
        load()

        findViewById<TextView>(R.id.today_label).text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        findViewById<TextView>(R.id.version_footer).text = "Budget ${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.bills_card).clipToOutline = true // keeps swiped rows inside the rounded card

        hero.setOnClickListener { explainSafeToSpend() }
        findViewById<View>(R.id.balance_row).setOnClickListener { editBalance() }
        findViewById<View>(R.id.glance_payday_row).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.glance_bill_row).setOnClickListener { showTab(2) }
        findViewById<View>(R.id.glance_due_row).setOnClickListener { showTab(2) }
        findViewById<View>(R.id.income_row).setOnClickListener { editIncome() }
        findViewById<View>(R.id.payday_row).setOnClickListener { pickPayday() }
        findViewById<View>(R.id.check_row).setOnClickListener { checkForUpdate() }
        findViewById<View>(R.id.add_first_bill).setOnClickListener { openBillSheet(null) }
        fab.setOnClickListener { openBillSheet(null) }
        updateButton.setOnClickListener { installUpdate() }
        findViewById<View>(R.id.undo_button).setOnClickListener { undoDeletes() }

        // Coming back from a dark-mode / font-size change: same tab, and an Undo that was showing.
        savedInstanceState?.let { state ->
            showTab(state.getInt("tab", 0), animate = false)
            runCatching {
                val saved = JSONArray(state.getString("undo") ?: "[]")
                for (i in 0 until saved.length()) {
                    val entry = saved.getJSONObject(i)
                    billFromJson(entry.getJSONObject("bill"))?.let { pendingUndo += entry.getInt("index") to it }
                }
                if (pendingUndo.isNotEmpty()) showUndoBar()
            }
        }
    }

    // Redraw on every return to the app so "due in X days" and the paydays stay current.
    override fun onResume() {
        super.onResume()
        refresh()
        // Back from the install prompt without installing (or it failed): let them try again.
        if (updateUrl != null && !downloading) {
            updateText.text = "Version $latestVersion is ready"
            updateButton.isEnabled = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
        val undo = JSONArray()
        for ((index, bill) in pendingUndo) undo.put(JSONObject().put("index", index).put("bill", billToJson(bill)))
        outState.putString("undo", undo.toString())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        heroAnimator?.cancel()
        sheetDialog?.dismiss()
        super.onDestroy()
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
        currentTab = index.coerceIn(0, tabs.size - 1)
        tabs.forEachIndexed { i, tab ->
            val selected = i == currentTab
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
            item.findViewById<TextView>(R.id.nav_label).setTextColor(if (selected) textColor else secondary)
        }
        fab.visibility = if (currentTab == 2) View.VISIBLE else View.GONE
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
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (isNight()) 0 else lightBars, lightBars)

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

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ---------- The money math ----------

    private fun lastPayday(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(payday))

    private fun paycheckOn(date: LocalDate) = paycheckChanges[date] ?: weeklyIncome

    // Number of paydays after `from`, up to and including `through`.
    private fun paydaysAfter(from: LocalDate, through: LocalDate): Long {
        val first = from.with(TemporalAdjusters.next(payday))
        return if (through.isBefore(first)) 0 else ChronoUnit.DAYS.between(first, through) / 7 + 1
    }

    // A balance entered on `from`, moved forward to `to`: plus paychecks that landed after `from` (up to and
    // including `to`), minus bills that came due from `from` up to (not including) `to`.
    private fun project(amount: Double, from: LocalDate, to: LocalDate): Double {
        if (!from.isBefore(to)) return amount
        val pays = generateSequence(from.with(TemporalAdjusters.next(payday))) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(to) }
            .sumOf { paycheckOn(it) }
        return amount + pays - bills.sumOf { it.amount * it.dueDates(from, to).size }
    }

    private fun currentBalance(today: LocalDate) = balanceUpdated?.let { project(balance, it, today) } ?: balance

    // How much has to stay in the bank today so every bill over the next year gets paid on time, given that
    // each future paycheck puts one week's share of the bills toward them. Also returns the bill where that
    // is tightest.
    private fun setAside(today: LocalDate, share: Double): Pair<Double, Pair<Bill, LocalDate>?> {
        var total = 0.0
        var worst = 0.0
        var tightest: Pair<Bill, LocalDate>? = null
        bills.flatMap { bill -> bill.dueDates(today, today.plusDays(372)).map { it to bill } }
            .sortedBy { it.first }
            .forEach { (date, bill) ->
                total += bill.amount
                val need = total - share * paydaysAfter(today, date)
                if (need > worst + 0.004) {
                    worst = need
                    tightest = bill to date
                }
            }
        return cents(worst) to tightest
    }

    // Safe to spend = the smaller of
    //   spending money: this week's pay − the bills' weekly share − what's been spent since payday, and
    //   free money: what's in the bank − what has to stay set aside for upcoming bills.
    private fun computeWeek(today: LocalDate): Week {
        val period = lastPayday(today)
        val next = today.with(TemporalAdjusters.next(payday))
        val pay = paycheckOn(period)
        val share = cents(bills.sumOf { it.perWeek })
        val start = weekStartTaken
        val tracking = weekStart == period && start != null
        // Tracking that only started mid-week (no earlier balance to carry forward) covers the rest of the week.
        val daysTracked = if (tracking && start!!.isAfter(period)) ChronoUnit.DAYS.between(start, next) else 7L
        val spent = if (tracking) {
            val upTo = balanceUpdated ?: today
            val billsPaid = bills.sumOf { it.amount * it.dueDates(start!!, upTo).size }
            cents(maxOf(0.0, weekStartBalance - balance - billsPaid))
        } else {
            null
        }
        val spendingMoney = cents((pay - share) * daysTracked / 7 - (spent ?: 0.0))
        val bank = cents(currentBalance(today))
        val (aside, tightest) = setAside(today, share)
        val free = cents(bank - aside)
        return Week(
            payday = period, nextPayday = next, pay = pay, billsShare = share, daysTracked = daysTracked,
            spent = spent, spendingMoney = spendingMoney, bank = bank,
            bankIsEstimate = balanceUpdated?.isBefore(today) == true && abs(bank - balance) > 0.004,
            setAside = aside, tightest = tightest, free = free, safe = cents(minOf(spendingMoney, free)),
        )
    }

    // ---------- Drawing the screens ----------

    // Never let a bad number or date take the whole app down; show what happened instead.
    private fun refresh() {
        try {
            showBills()
            recalculate()
            showSettings()
        } catch (e: Exception) {
            Toast.makeText(this, "Something went wrong showing your budget: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
            !trackingThisWeek -> "Update to count this week's spending"
            days == 0L -> "Updated today"
            days == 1L -> "Updated yesterday"
            else -> "Updated $days days ago"
        }
        balanceUpdatedView.setTextColor(if (days == null || !trackingThisWeek || days >= 3) warning else secondary)
    }

    private fun showBills() {
        val today = LocalDate.now()
        val next = bills.associateWith { it.nextDue(today) }
        billList.removeAllViews()
        for (bill in bills.sortedBy { next.getValue(it) }) {
            val row = layoutInflater.inflate(R.layout.bill_row, billList, false)
            val due = next.getValue(bill)
            val daysLeft = ChronoUnit.DAYS.between(today, due)
            val (bg, fg) = AVATAR_COLORS[Math.floorMod(bill.name.lowercase().hashCode(), AVATAR_COLORS.size)]
            row.findViewById<TextView>(R.id.bill_row_avatar).apply {
                text = bill.name.take(1).uppercase()
                backgroundTintList = ColorStateList.valueOf(getColor(bg))
                setTextColor(getColor(fg))
            }
            row.findViewById<TextView>(R.id.bill_row_name).text = bill.name
            row.findViewById<TextView>(R.id.bill_row_amount).text = money.format(bill.amount)
            val dueText = buildString {
                append("${due.format(shortDate)} · ${dueIn(daysLeft)}")
                if (bill.freq != Freq.MONTHLY) append(" · ${bill.freq.label}")
            }
            row.findViewById<TextView>(R.id.bill_row_due).apply {
                text = dueText
                setTextColor(if (daysLeft <= 3) warning else secondary)
            }
            val content = row.findViewById<View>(R.id.bill_row_content)
            content.contentDescription = "${bill.name}, ${money.format(bill.amount)}, due $dueText"
            makeSwipeable(content, onTap = { openBillSheet(bill) }, onSwiped = { deleteBill(bill) })
            billList.addView(row)
        }
        billsEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
        billsHint.visibility = if (bills.isEmpty()) View.GONE else View.VISIBLE
        val perMonth = bills.sumOf { it.perWeek } * 365.25 / 7 / 12
        val allMonthly = bills.all { it.freq == Freq.MONTHLY }
        billsSubtitle.text = when {
            bills.isEmpty() -> "Add what you pay regularly"
            else -> "${bills.size} bill${if (bills.size == 1) "" else "s"} · ${if (allMonthly) "" else "about "}${money.format(perMonth)} a month"
        }
        val dueSoon = next.values.any { ChronoUnit.DAYS.between(today, it) <= 3 }
        navItems[2].findViewById<View>(R.id.nav_badge).visibility = if (dueSoon) View.VISIBLE else View.GONE
        navItems[2].contentDescription = if (dueSoon) "Bills, a bill is due soon" else "Bills"
    }

    // Walks from today to the end of the month. Each payday adds a paycheck, and each bill comes out of
    // the pay period it's due in. Today's paycheck (if today is payday) is assumed to already be in the
    // balance; bills due today are assumed not paid yet.
    private fun recalculate() {
        val today = LocalDate.now()
        val week = computeWeek(today)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val afterMonth = monthEnd.plusDays(1)
        val paydays = generateSequence(week.nextPayday) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(monthEnd) }
            .toList()
        val periodStarts = listOf(today) + paydays

        forecastList.removeAllViews()
        var running = week.bank
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
            running = cents(running + paycheck - billsDue)
            chartLabels += when (i) {
                0 -> "Now"
                1 -> start.format(shortDate)
                else -> start.dayOfMonth.toString()
            }
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
                    if (start in paycheckChanges) {
                        val from = length
                        append("  changed")
                        setSpan(ForegroundColorSpan(secondary), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(0.8f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    append("Before payday")
                }
            }
            row.findViewById<TextView>(R.id.forecast_title).typeface =
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            row.findViewById<TextView>(R.id.forecast_bills).text = if (due.isEmpty()) {
                "No bills"
            } else {
                val names = due.groupingBy { it }.eachCount().entries
                    .joinToString { (bill, times) -> if (times > 1) "${bill.name} ×$times" else bill.name }
                "−${money.format(billsDue)} · $names"
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

        // Summary
        val short = week.safe < 0
        hero.setBackgroundResource(if (short) R.drawable.hero_red else R.drawable.hero_green)
        animateHero(maxOf(0.0, week.safe))
        val until = week.nextPayday.format(dayFormat)
        heroNote.text = when {
            week.free < 0 -> "${money.format(-week.free)} short for upcoming bills" +
                (week.tightest?.let { " (${it.first.name}, ${it.second.format(shortDate)})" } ?: "")
            week.pay < week.billsShare -> "Your bills cost ${money.format(week.billsShare - week.pay)} more than your weekly pay"
            week.spendingMoney < 0 -> "You're ${money.format(-week.spendingMoney)} over this week's spending money"
            week.free < week.spendingMoney -> "Lowered so your upcoming bills stay covered"
            else -> "Your spending money until $until"
        }
        heroBreakdown.text = buildString {
            append("${shortMoney(week.pay)} pay − ${shortMoney(week.billsShare)} bills")
            if (week.daysTracked < 7) append(" (${week.daysTracked} of 7 days)")
            append(week.spent?.let { " − ${shortMoney(it)} spent" } ?: " · spending not counted yet")
            append("  ·  Tap for details")
        }
        hero.contentDescription = "Safe to spend ${money.format(maxOf(0.0, week.safe))}. ${heroNote.text}. Tap for details."
        heroEndLabel.text = "End of $monthName"
        heroEnd.text = money.format(running)
        heroEnd.setTextColor(if (running < 0) negative else textColor)
        val cushion = cents(week.free - maxOf(0.0, week.safe))
        heroCushion.text = money.format(cushion)
        heroCushion.setTextColor(if (cushion < 0) negative else textColor)

        glancePayday.text = "${week.nextPayday.format(shortDate)} · +${money.format(paycheckOn(week.nextPayday))}"
        val nextBill = bills.minByOrNull { it.nextDue(today) }
        if (nextBill == null) {
            glanceBill.text = "None"
            glanceBill.setTextColor(secondary)
        } else {
            val daysLeft = ChronoUnit.DAYS.between(today, nextBill.nextDue(today))
            glanceBill.text = "${nextBill.name} · ${dueIn(daysLeft)}"
            glanceBill.setTextColor(if (daysLeft <= 3) warning else textColor)
        }
        val stillDue = cents(bills.sumOf { it.amount * it.dueDates(today, afterMonth).size })
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

    // The "explain like I'm 5" breakdown behind Safe to spend, with the real numbers.
    private fun explainSafeToSpend() {
        val week = computeWeek(LocalDate.now())
        val pad = dp(24)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(4), pad, dp(8))
        }
        fun heading(text: String) = box.addView(TextView(this).apply {
            this.text = text
            setTextColor(secondary)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(12), 0, dp(4))
        })
        fun row(label: String, value: String, total: Boolean = false) = box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(context).apply {
                text = label
                setTextColor(textColor)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = value
                setTextColor(textColor)
                textSize = 15f
                gravity = Gravity.END
                fontFeatureSettings = "tnum"
                if (total) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        })
        fun line() = box.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.divider))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        })

        heading("1. Spending money from this week's pay")
        row("Pay on ${week.payday.format(dayFormat)}", money.format(week.pay))
        row("Weekly share of your bills", "−" + money.format(week.billsShare))
        if (week.daysTracked < 7) row("Days left you're tracking", "${week.daysTracked} of 7")
        row("Spent since payday", week.spent?.let { "−" + money.format(it) } ?: "not counted yet")
        line()
        row("Spending money", money.format(week.spendingMoney), total = true)

        heading("2. What the bank can spare")
        row(if (week.bankIsEstimate) "Bank balance (estimated)" else "Bank balance", money.format(week.bank))
        row("Kept for upcoming bills", "−" + money.format(week.setAside))
        line()
        row("Free money", money.format(week.free), total = true)

        box.addView(TextView(this).apply {
            setTextColor(secondary)
            textSize = 13f
            setPadding(0, dp(14), 0, 0)
            text = buildString {
                append("Safe to spend is the smaller of the two: ${money.format(maxOf(0.0, week.safe))}.\n\n")
                append("Each paycheck puts ${money.format(week.billsShare)} toward bills (rent and other big bills are spread evenly over the weeks). ")
                val tightest = week.tightest
                if (tightest != null && week.setAside > 0) {
                    append("On top of that, ${money.format(week.setAside)} has to stay in the bank now so ${tightest.first.name} on ${tightest.second.format(shortDate)} and everything before it gets paid on time.")
                } else {
                    append("That covers every bill on time, so nothing extra has to stay in the bank.")
                }
                if (week.spent == null) append("\n\nUpdate your bank balance to count what you've spent since payday.")
                if (week.bankIsEstimate) append("\n\nYour balance is an estimate: the last one you entered, plus paychecks and minus bills since then.")
            }
        })

        AlertDialog.Builder(this)
            .setTitle("How Safe to spend works")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("OK", null)
            .setNeutralButton("Change this week's pay") { _, _ -> editPaycheck(week.payday) }
            .show()
    }

    // ---------- Editing ----------

    private fun editBalance() = askAmount("Bank balance", "What's in your account right now?", balance, signed = true) { value ->
        if (value == null) return@askAmount // empty or invalid: change nothing
        val today = LocalDate.now()
        val period = lastPayday(today)
        val oldDate = balanceUpdated
        if (weekStart != period) {
            // First balance this pay period. Carry the last one forward to payday so spending since payday
            // counts; with no earlier balance, start counting from today.
            weekStart = period
            if (oldDate != null && oldDate.isBefore(period)) {
                weekStartBalance = project(balance, oldDate, period)
                weekStartTaken = period
            } else {
                weekStartBalance = value
                weekStartTaken = today
            }
        } else if (weekStartTaken == today) {
            weekStartBalance = value // a same-day correction replaces the starting point
        }
        balance = value
        balanceUpdated = today
        // More money than expected (a late paycheck, a refund): count spending from here instead.
        val start = weekStartTaken ?: today
        val billsPaid = bills.sumOf { it.amount * it.dueDates(start, today).size }
        if (balance > weekStartBalance - billsPaid + 0.004) {
            weekStartBalance = balance
            weekStartTaken = today
        }
        save()
        refresh()
    }

    private fun editIncome() = askAmount("Weekly income", "Your usual paycheck each week.", weeklyIncome) {
        if (it == null) return@askAmount
        weeklyIncome = it
        save()
        refresh()
    }

    private fun editPaycheck(date: LocalDate) = askAmount(
        "Paycheck on ${date.format(dayFormat)}",
        "Usually ${money.format(weeklyIncome)}. Enter this week's amount.",
        paycheckChanges[date],
        showZero = true,
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
                // Keep counting this week's spending if the starting balance still falls in the new pay week.
                val period = lastPayday(LocalDate.now())
                if (weekStartTaken?.isBefore(period) == false) weekStart = period
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
        showZero: Boolean = false,
        neutral: Pair<String, () -> Unit>? = null,
        onSave: (Double?) -> Unit,
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            filters = arrayOf(InputFilter.LengthFilter(12))
            hint = "0.00"
            current?.takeIf { showZero || it != 0.0 }?.let { setText(plain(it)) }
            setSelectAllOnFocus(true)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), 0, dp(24), 0)
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
        if (sheetDialog?.isShowing == true) return // a quick double-tap shouldn't stack two sheets
        if (existing != null && existing !in bills) return // tapped while it was being deleted
        val today = LocalDate.now()
        var closeSheet: () -> Unit = {}
        val dialog = object : Dialog(this, R.style.SheetDialog) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() = closeSheet()
        }
        sheetDialog = dialog
        dialog.setContentView(R.layout.sheet_bill)
        val window = dialog.window!!
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                (if (existing == null) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0)
        )

        val root = dialog.findViewById<View>(R.id.sheet_root)
        val scrim = dialog.findViewById<View>(R.id.sheet_scrim)
        val sheet = dialog.findViewById<View>(R.id.sheet)
        val nameBox = dialog.findViewById<EditText>(R.id.sheet_name)
        val amountBox = dialog.findViewById<EditText>(R.id.sheet_amount)
        val freqSpinner = dialog.findViewById<Spinner>(R.id.sheet_freq)
        val dateField = dialog.findViewById<TextView>(R.id.sheet_date)
        val saveButton = dialog.findViewById<Button>(R.id.sheet_save)
        val deleteButton = dialog.findViewById<View>(R.id.sheet_delete)

        // Run the sheet down behind the navigation buttons (Android 11+). Older phones keep the window above
        // them and leave the button colors alone.
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(R.color.card)
            window.setDecorFitsSystemWindows(false)
            window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
            window.isNavigationBarContrastEnforced = false
            val lightNav = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (isNight()) 0 else lightNav, lightNav)
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
        // When editing, keep the bill's original start date unless a new one is picked (so a bill due on
        // the 31st stays on the 31st).
        var pickedDate: LocalDate? = null
        if (existing != null) {
            nameBox.setText(existing.name)
            amountBox.setText(plain(existing.amount))
            freqSpinner.setSelection(existing.freq.ordinal)
            dateField.text = existing.nextDue(today).format(dayFormat)
        }
        fun pickDate() {
            val start = pickedDate ?: existing?.nextDue(today) ?: today
            DatePickerDialog(this, { _, year, month, day ->
                pickedDate = LocalDate.of(year, month + 1, day)
                dateField.text = pickedDate!!.format(dayFormat)
            }, start.year, start.monthValue - 1, start.dayOfMonth).show()
        }
        dateField.setOnClickListener { pickDate() }

        var closing = false
        fun close(then: () -> Unit = {}) {
            if (closing) return
            closing = true
            hideKeyboardIn(dialog)
            scrim.animate().alpha(0f).setDuration(180).start()
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (dialog.isShowing && !isDestroyed) dialog.dismiss()
                    if (!isDestroyed) then()
                }.start()
        }
        closeSheet = { close() }

        saveButton.setOnClickListener {
            if (closing) return@setOnClickListener // a second tap while the sheet is closing
            val name = nameBox.text.toString().trim()
            val amount = parseMoney(amountBox.text.toString())
            val date = pickedDate ?: existing?.date
            when {
                name.isEmpty() -> nameBox.requestFocus()
                amount == null || amount < 0 -> amountBox.requestFocus()
                date == null -> pickDate()
                else -> {
                    val bill = Bill(name, amount, Freq.values()[freqSpinner.selectedItemPosition], date)
                    if (existing == null) {
                        bills.add(bill)
                    } else {
                        val index = bills.indexOf(existing)
                        if (index >= 0) bills[index] = bill // gone already (deleted meanwhile): don't bring it back
                    }
                    save()
                    haptic(saveButton)
                    close { refresh() }
                }
            }
        }
        deleteButton.setOnClickListener {
            if (!closing) close { if (existing != null) deleteBill(existing) }
        }

        // Slide up from the bottom; the dimmed area only closes the sheet once it's fully open.
        scrim.alpha = 0f
        sheet.visibility = View.INVISIBLE
        if (existing == null) nameBox.requestFocus()
        dialog.setOnDismissListener { if (sheetDialog === dialog) sheetDialog = null }
        dialog.show()
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(200).start()
            sheet.animate().translationY(0f).setDuration(250).setInterpolator(DecelerateInterpolator())
                .withEndAction { scrim.setOnClickListener { close() } }
                .start()
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
        pendingUndo += index to bill
        showUndoBar()
    }

    // One Undo bar for everything deleted in the last few seconds.
    private fun showUndoBar() {
        undoText.text = if (pendingUndo.size == 1) "Deleted ${pendingUndo[0].second.name}" else "Deleted ${pendingUndo.size} bills"
        handler.removeCallbacks(hideUndo)
        undoBar.animate().cancel()
        if (undoBar.visibility != View.VISIBLE) {
            undoBar.alpha = 0f
            undoBar.visibility = View.VISIBLE
        }
        undoBar.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed(hideUndo, 5000)
    }

    private fun undoDeletes() {
        for ((index, bill) in pendingUndo.reversed()) bills.add(minOf(index, bills.size), bill)
        pendingUndo.clear()
        save()
        refresh()
        handler.removeCallbacks(hideUndo)
        hideUndo.run()
    }

    // Swipe a bill row left to delete it; a tap opens it for editing. Screen readers get a Delete action.
    @SuppressLint("ClickableViewAccessibility")
    private fun makeSwipeable(content: View, onTap: () -> Unit, onSwiped: () -> Unit) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var swiping = false
        var moved = false
        var removing = false
        content.setOnClickListener { if (!removing) onTap() }
        content.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_delete_bill, "Delete"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == R.id.action_delete_bill) {
                    onSwiped()
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
        content.setOnTouchListener { view, event ->
            if (removing) return@setOnTouchListener true
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
                        swiping && -view.translationX > view.width * 0.35f -> {
                            removing = true
                            view.animate().translationX(-view.width.toFloat()).setDuration(160)
                                .withEndAction { onSwiped() }.start()
                        }
                        swiping -> view.animate().translationX(0f).setDuration(160).start()
                        !moved -> view.performClick()
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

    // Plain numbers stay neutral; only a shortfall is red.
    private fun showMoney(view: TextView, amount: Double) {
        view.text = money.format(amount)
        view.setTextColor(if (amount < 0) negative else textColor)
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

    // Rounds to whole cents (and turns -0.0 into 0.0) so tiny floating-point leftovers never show as
    // "-$0.00" or flip a number red.
    private fun cents(amount: Double): Double = (amount * 100).roundToLong() / 100.0 + 0.0

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun haptic(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun plain(amount: Double) = amount.toBigDecimal().stripTrailingZeros().toPlainString()

    // Accepts "1,234.56", "$50", "-20". Anything empty, unreadable or absurdly large counts as no answer.
    private fun parseMoney(text: String): Double? =
        text.replace(Regex("[^0-9.-]"), "").toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) < 1e9 }

    // ---------- Saving ----------

    private fun billToJson(bill: Bill) = JSONObject()
        .put("name", bill.name).put("amount", bill.amount)
        .put("freq", bill.freq.name).put("date", bill.date.toString())

    // A saved bill, or null if it can't be read (it's skipped instead of crashing the app).
    private fun billFromJson(json: JSONObject): Bill? = runCatching {
        val (freq, date) = if (json.has("freq")) {
            Freq.valueOf(json.getString("freq")) to LocalDate.parse(json.getString("date"))
        } else {
            // Bills saved before v2.6 were monthly with just a day of the month. January has 31 days,
            // so any day fits.
            Freq.MONTHLY to LocalDate.of(LocalDate.now().year, 1, json.optInt("day", 1).coerceIn(1, 31))
        }
        val amount = json.getDouble("amount")
        if (!amount.isFinite()) null else Bill(json.getString("name"), amount, freq, date)
    }.getOrNull()

    private fun load() {
        fun date(key: String) = prefs.getString(key, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        fun number(key: String) = prefs.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        balance = number("balance") ?: 0.0
        balanceUpdated = date("balance_updated")
        weeklyIncome = number("weekly_income") ?: 0.0
        payday = runCatching { DayOfWeek.of(prefs.getInt("payday", DayOfWeek.FRIDAY.value)) }.getOrDefault(DayOfWeek.FRIDAY)
        runCatching {
            val saved = JSONArray(prefs.getString("bills", null) ?: "[]")
            for (i in 0 until saved.length()) saved.optJSONObject(i)?.let { json -> billFromJson(json)?.let { bills.add(it) } }
        }

        // Keep this pay period's paycheck change (it sets this week's spending money); drop older ones.
        val periodStart = lastPayday(LocalDate.now())
        runCatching {
            val changes = JSONObject(prefs.getString("paycheck_changes", null) ?: "{}")
            for (key in changes.keys()) {
                val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                val amount = changes.optDouble(key)
                if (!day.isBefore(periodStart) && amount.isFinite()) paycheckChanges[day] = amount
            }
        }

        weekStart = date("week_start")
        weekStartBalance = number("week_start_balance") ?: 0.0
        weekStartTaken = date("week_start_taken")
    }

    private fun save() {
        val saved = JSONArray()
        for (bill in bills) saved.put(billToJson(bill))
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

    // A download that died (no signal, app closed) can leave a half-written install behind; clear those.
    private fun clearStaleInstallSessions() {
        thread {
            runCatching {
                val installer = packageManager.packageInstaller
                for (session in installer.mySessions) runCatching { installer.abandonSession(session.sessionId) }
            }
        }
    }

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
                if (isDestroyed) return@runOnUiThread
                val update = result.getOrNull()
                when {
                    result.isFailure -> updateStatus.text = "Couldn't check · version $current"
                    update == null -> updateStatus.text = "Up to date · version $current"
                    else -> {
                        latestVersion = update.first
                        updateUrl = update.second
                        updateStatus.text = "Version ${update.first} is available"
                        if (!downloading) updateText.text = "Version ${update.first} is ready"
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
        if (downloading) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Budget to install updates, then tap Update again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        downloading = true
        updateButton.isEnabled = false
        updateText.text = "Downloading…"
        thread {
            val installer = packageManager.packageInstaller
            var sessionId = -1
            val result = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                if (conn.responseCode != HttpURLConnection.HTTP_OK) error("download failed (${conn.responseCode})")
                val length = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                installer.openSession(sessionId).use { session ->
                    conn.inputStream.use { input ->
                        session.openWrite("budget.apk", 0, length).use { output ->
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
            if (result.isFailure && sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            runOnUiThread {
                downloading = false
                if (isDestroyed) return@runOnUiThread
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
