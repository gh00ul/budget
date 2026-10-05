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
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.math.RoundingMode
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

// Home-screen shortcuts (res/xml/shortcuts.xml).
private const val ACTION_UPDATE_BALANCE = "com.gh00ul.budget.UPDATE_BALANCE"
private const val ACTION_ADD_BILL = "com.gh00ul.budget.ADD_BILL"

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
    // paid = due dates marked paid (early, or already taken by autopay) → the day they were marked.
    private class Bill(
        val name: String,
        val amount: Double,
        val freq: Freq,
        val date: LocalDate,
        val paid: Map<LocalDate, LocalDate> = emptyMap(),
    ) {
        // This bill's share of one week, e.g. $1,200 monthly rent ≈ $275.97 a week.
        val perWeek: Double get() = amount * 7 / freq.cycleDays

        // Every scheduled due date in [from, until), paid or not.
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

        // Due dates in [from, until) that haven't been marked paid.
        fun unpaid(from: LocalDate, until: LocalDate) = dueDates(from, until).filter { it !in paid }

        // How many times it was marked paid on a day after `after`, up to and including `through`.
        fun paidBetween(after: LocalDate, through: LocalDate) = paid.values.count { it.isAfter(after) && !it.isAfter(through) }

        // Next unpaid due date on or after today (even years ahead).
        fun nextDue(today: LocalDate): LocalDate {
            var from = today
            repeat(5) {
                val until = from.plusYears(2)
                dueDates(from, until).firstOrNull { it !in paid }?.let { return it }
                from = until
            }
            return date
        }

        // The due date most recently marked paid, if that was done in the past week.
        fun recentPaidMark(today: LocalDate): LocalDate? =
            paid.entries.filter { !it.value.isBefore(today.minusDays(6)) && !it.value.isAfter(today) }
                .maxByOrNull { it.value.toEpochDay() }?.key

        fun withPaid(due: LocalDate, on: LocalDate) = Bill(name, amount, freq, date, paid + (due to on))
        fun withoutPaid(due: LocalDate) = Bill(name, amount, freq, date, paid - due)
    }

    // The "bill buffer": what has to stay in the bank for upcoming bills.
    private class Buffer(
        val amount: Double,
        val tightest: Pair<Bill, LocalDate>?, // where the buffer is needed most
        val firstShort: Pair<Bill, LocalDate>?, // the first bill the bank can't cover, if any
    )

    // Everything behind "Safe to spend" for the current pay week.
    private class Week(
        val payday: LocalDate, // the payday that started this pay period
        val nextPayday: LocalDate,
        val daysLeft: Long, // days until the next payday
        val pay: Double,
        val billsShare: Double, // one week's share of all bills
        val afterBills: Double, // pay - billsShare
        val daysTracked: Long, // 7, or fewer if spending tracking only started mid-week
        val weekMoney: Double, // afterBills for the tracked days
        val trackedFrom: LocalDate?, // the day "spent" counts from
        val startBalance: Double?, // the balance on that day
        val spent: Double?, // null = no balance entered since payday, so spending is unknown
        val spendingMoney: Double, // weekMoney - spent
        val bank: Double, // the last balance entered, moved forward to today
        val bankIsEstimate: Boolean,
        val setAside: Double, // "bill buffer"
        val tightest: Pair<Bill, LocalDate>?,
        val firstShort: Pair<Bill, LocalDate>?,
        val free: Double, // "spare money": bank - setAside
        val safe: Double, // the smaller of spendingMoney and free
        val fromBank: Boolean, // spent = purchases synced from the bank, not balance changes
    )

    private class BalanceState(
        val balance: Double, val updated: LocalDate?, val pendingPay: LocalDate?,
        val weekStart: LocalDate?, val startBalance: Double, val startTaken: LocalDate?, val startProjected: Boolean,
    )

    private class Tab(val label: String, val icon: Int, val page: Int)

    private val tabs = listOf(
        Tab("Summary", R.drawable.ic_tab_summary, R.id.page_summary),
        Tab("Paydays", R.drawable.ic_tab_paydays, R.id.page_paydays),
        Tab("Bills", R.drawable.ic_tab_bills, R.id.page_bills),
    )
    private val navItems = mutableListOf<View>()
    private var currentTab = 0

    private val prefs by lazy { getSharedPreferences("budget", MODE_PRIVATE) }
    private val money = NumberFormat.getCurrencyInstance()
    private val decimalSeparator = (money as? DecimalFormat)?.decimalFormatSymbols?.monetaryDecimalSeparator ?: '.'
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val shortDate = DateTimeFormatter.ofPattern("MMM d")
    private val weekday = DateTimeFormatter.ofPattern("EEE")
    private val monthShort = DateTimeFormatter.ofPattern("MMM")
    private val bills = mutableListOf<Bill>()
    private var balance = 0.0
    private var balanceUpdated: LocalDate? = null
    private var pendingPay: LocalDate? = null // payday whose paycheck hadn't landed when the balance was entered
    private var weeklyIncome = 0.0
    private var payday = DayOfWeek.FRIDAY
    private var billsNone = false // said "I have no regular bills" during setup
    // One-off paycheck amounts (overtime, short week) that replace the weekly income on that date.
    private val paycheckChanges = mutableMapOf<LocalDate, Double>()
    // Where "spent since payday" counts from: a balance and the day it applies to. Usually the last balance
    // carried forward to payday ("projected"), otherwise the first balance entered this week.
    private var weekStart: LocalDate? = null // the payday that began the period
    private var weekStartBalance = 0.0
    private var weekStartTaken: LocalDate? = null
    private var weekStartProjected = false

    // Bank sync (BankSync.kt). The balance comes from checking; purchases on checking and cards count as spent.
    private val bank by lazy { BankStore(this) }
    private var bankSyncing = false
    private var earlyPay: LocalDate? = null // an upcoming payday whose paycheck is already in the synced balance

    private var updateUrl: String? = null
    private var latestVersion: String? = null
    private var downloading = false
    private var updateStatusText = "checking…"

    private val handler = Handler(Looper.getMainLooper())
    private val pendingDeletes = mutableListOf<Pair<Int, Bill>>() // (index it was at, bill)
    private var snackUndo: (() -> Unit)? = null
    private val hideSnack = Runnable {
        pendingDeletes.clear()
        snackUndo = null
        undoBar.animate().alpha(0f).translationY(dp(16).toFloat()).setDuration(150)
            .withEndAction { undoBar.visibility = View.GONE }.start()
    }
    private var heroShown: Double? = null
    private var heroAnimator: ValueAnimator? = null
    private var sheetDialog: Dialog? = null
    private var fabShown = false
    private val openForecastRows = mutableSetOf<LocalDate>() // payday rows showing their details

    // Theme colors (they change in dark mode).
    private val positive by lazy { getColor(R.color.positive) }
    private val negative by lazy { getColor(R.color.negative) }
    private val warning by lazy { getColor(R.color.warning) }
    private val secondary by lazy { getColor(R.color.text_secondary) }
    private val textColor by lazy { getColor(R.color.text) }
    private val accent by lazy { getColor(R.color.chip_text) }
    private val a11y by lazy { getSystemService(AccessibilityManager::class.java) }
    private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private fun <T : View> view(id: Int) = lazy { findViewById<T>(id) }
    private val billList by view<LinearLayout>(R.id.bill_list)
    private val billsEmpty by view<View>(R.id.bills_empty)
    private val billsHint by view<View>(R.id.bills_hint)
    private val billsSubtitle by view<TextView>(R.id.bills_subtitle)
    private val billsNoneLink by view<View>(R.id.bills_none_link)
    private val forecastList by view<LinearLayout>(R.id.forecast_list)
    private val chartCard by view<View>(R.id.chart_card)
    private val chart by view<BalanceChart>(R.id.chart)
    private val paydaysSubtitle by view<TextView>(R.id.paydays_subtitle)
    private val paydaysEmpty by view<View>(R.id.paydays_empty)
    private val paydaysEmptyBody by view<TextView>(R.id.paydays_empty_body)
    private val paydaysEmptyButton by view<Button>(R.id.paydays_empty_button)
    private val paydaysContent by view<View>(R.id.paydays_content)
    private val payValue by view<TextView>(R.id.pay_value)
    private val setupCard by view<View>(R.id.setup_card)
    private val summaryMain by view<View>(R.id.summary_main)
    private val hero by view<View>(R.id.hero)
    private val heroAmount by view<TextView>(R.id.hero_amount)
    private val heroNote by view<TextView>(R.id.hero_note)
    private val heroProgress by view<ProgressBar>(R.id.hero_progress)
    private val heroBreakdown by view<TextView>(R.id.hero_breakdown)
    private val heroEndLabel by view<TextView>(R.id.hero_end_label)
    private val heroEnd by view<TextView>(R.id.hero_end)
    private val heroCushion by view<TextView>(R.id.hero_cushion)
    private val heroCushionCaption by view<TextView>(R.id.hero_cushion_caption)
    private val balanceValue by view<TextView>(R.id.balance_value)
    private val balanceUpdatedView by view<TextView>(R.id.balance_updated)
    private val glancePayday by view<TextView>(R.id.glance_payday)
    private val glanceBill by view<TextView>(R.id.glance_bill)
    private val glanceDue by view<TextView>(R.id.glance_due)
    private val updateBanner by view<View>(R.id.update_banner)
    private val updateText by view<TextView>(R.id.update_text)
    private val updateButton by view<Button>(R.id.update_button)
    private val fab by view<View>(R.id.fab)
    private val undoBar by view<View>(R.id.undo_bar)
    private val undoText by view<TextView>(R.id.undo_text)
    private val undoButton by view<View>(R.id.undo_button)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fitToSystemBars()
        setUpTabs()
        // The update check runs before anything reads saved data, so a data problem can never block an
        // update that fixes it.
        if (savedInstanceState == null) clearStaleInstallSessions()
        checkForUpdate()
        load()

        findViewById<TextView>(R.id.today_label).text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        findViewById<View>(R.id.bills_card).clipToOutline = true // keeps swiped rows inside the rounded card

        hero.setOnClickListener { explainSafeToSpend() }
        findViewById<View>(R.id.balance_row).setOnClickListener { editBalance() }
        findViewById<View>(R.id.log_purchase).setOnClickListener { if (bank.isLinked) showPurchases() else logPurchase() }
        findViewById<View>(R.id.glance_payday_row).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.glance_bill_row).setOnClickListener {
            val today = LocalDate.now()
            bills.minByOrNull { it.nextDue(today) }?.let { openBillSheet(it) } ?: showTab(2)
        }
        findViewById<View>(R.id.glance_due_row).setOnClickListener { showTab(2) }
        findViewById<View>(R.id.settings_button).setOnClickListener { showSettings() }
        findViewById<View>(R.id.pay_row).setOnClickListener { editPay() }
        findViewById<View>(R.id.add_first_bill).setOnClickListener { openBillSheet(null) }
        billsNoneLink.setOnClickListener { setNoBills() }
        findViewById<View>(R.id.setup_pay_row).setOnClickListener { editPay() }
        findViewById<View>(R.id.setup_bills_row).setOnClickListener { openBillSheet(null) }
        findViewById<View>(R.id.setup_balance_row).setOnClickListener { editBalance() }
        findViewById<View>(R.id.setup_no_bills).setOnClickListener { setNoBills() }
        fab.setOnClickListener { openBillSheet(null) }
        updateButton.setOnClickListener { installUpdate() }
        undoButton.setOnClickListener { undo() }

        // Coming back from a dark-mode / font-size change: same tab, and an Undo that was showing.
        if (savedInstanceState != null) {
            showTab(savedInstanceState.getInt("tab", 0), animate = false)
            runCatching {
                val saved = JSONArray(savedInstanceState.getString("undo") ?: "[]")
                for (i in 0 until saved.length()) {
                    val entry = saved.getJSONObject(i)
                    billFromJson(entry.getJSONObject("bill"))?.let { pendingDeletes += entry.getInt("index") to it }
                }
                if (pendingDeletes.isNotEmpty()) showSnackBar(deletedText(), canUndo = true)
            }
        } else {
            handleShortcut(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    // Redraw on every return to the app so "due in X days" and the paydays stay current.
    override fun onResume() {
        super.onResume()
        refresh()
        syncBank()
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
        for ((index, bill) in pendingDeletes) undo.put(JSONObject().put("index", index).put("bill", billToJson(bill)))
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

    private fun handleShortcut(intent: Intent?) {
        when (intent?.action) {
            ACTION_UPDATE_BALANCE -> {
                showTab(0, animate = false)
                hero.post { editBalance() }
            }
            ACTION_ADD_BILL -> {
                showTab(2, animate = false)
                hero.post { openBillSheet(null) }
            }
        }
    }

    // ---------- Tabs and window ----------

    private fun setUpTabs() {
        val nav = findViewById<LinearLayout>(R.id.nav)
        nav.accessibilityDelegate = object : View.AccessibilityDelegate() {
            @Suppress("DEPRECATION")
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(1, tabs.size, false)
            }
        }
        tabs.forEachIndexed { i, tab ->
            val item = layoutInflater.inflate(R.layout.nav_item, nav, false)
            item.findViewById<ImageView>(R.id.nav_icon).setImageResource(tab.icon)
            item.findViewById<TextView>(R.id.nav_label).text = tab.label
            item.contentDescription = tab.label
            item.setOnClickListener { showTab(i) }
            // Screen readers announce "Tab, 2 of 3".
            item.accessibilityDelegate = object : View.AccessibilityDelegate() {
                @Suppress("DEPRECATION")
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.extras.putCharSequence("AccessibilityNodeInfo.roleDescription", "Tab")
                    info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, i, 1, false, host.isSelected)
                }
            }
            if (Build.VERSION.SDK_INT >= 28) findViewById<View>(tab.page).accessibilityPaneTitle = tab.label
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
                page.translationY = dp(12).toFloat()
                page.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
            val item = navItems[i]
            item.isSelected = selected
            val pill = item.findViewById<View>(R.id.nav_pill)
            pill.setBackgroundResource(if (selected) R.drawable.nav_pill else 0)
            if (selected && animate && changed) {
                pill.scaleX = 0.6f
                pill.alpha = 0f
                pill.animate().scaleX(1f).alpha(1f).setDuration(200).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            }
            item.findViewById<ImageView>(R.id.nav_icon).imageTintList =
                ColorStateList.valueOf(if (selected) accent else secondary)
            item.findViewById<TextView>(R.id.nav_label).setTextColor(if (selected) textColor else secondary)
        }
        updateFab(animate)
        hideKeyboard()
    }

    // The + button lives on the Bills tab once there's a bill (the empty list has its own button).
    private fun updateFab(animate: Boolean) {
        val show = currentTab == 2 && bills.isNotEmpty()
        if (show == fabShown) return
        fabShown = show
        fab.animate().cancel()
        if (show) {
            fab.visibility = View.VISIBLE
            if (animate) {
                fab.scaleX = 0.6f
                fab.scaleY = 0.6f
                fab.alpha = 0f
            }
            fab.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(if (animate) 180 else 0).start()
        } else if (animate) {
            fab.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(120)
                .withEndAction { if (!fabShown) fab.visibility = View.GONE }.start()
        } else {
            fab.visibility = View.GONE
        }
        positionSnackBar()
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

    // The balance entered, plus a paycheck that hadn't landed yet, minus one that landed before its payday
    // (moving the balance forward adds it back on that payday).
    private fun effectiveBalance(): Double {
        val updated = balanceUpdated ?: return balance
        return balance +
            (pendingPay?.takeIf { !it.isAfter(updated) }?.let { paycheckOn(it) } ?: 0.0) -
            (earlyPay?.takeIf { it.isAfter(updated) }?.let { paycheckOn(it) } ?: 0.0)
    }

    // Unpaid bills due in [from, until).
    private fun billsDueIn(from: LocalDate, until: LocalDate) = cents(bills.sumOf { it.amount * it.unpaid(from, until).size })

    // Money that left the bank for bills between a balance on `from` and one on `to`: scheduled bills due in
    // [from, to) that weren't marked paid, plus bills marked paid on a day in (from, to].
    private fun billsOut(from: LocalDate, to: LocalDate) =
        bills.sumOf { it.amount * (it.unpaid(from, to).size + it.paidBetween(from, to)) }

    // A balance entered on `from`, moved forward to `to`: plus paychecks after `from` (up to and including
    // `to`), minus bills that went out in between.
    private fun project(amount: Double, from: LocalDate, to: LocalDate): Double {
        if (!from.isBefore(to)) return amount
        val pays = generateSequence(from.with(TemporalAdjusters.next(payday))) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(to) }
            .sumOf { paycheckOn(it) }
        return amount + pays - billsOut(from, to)
    }

    private fun currentBalance(today: LocalDate) = balanceUpdated?.let { project(effectiveBalance(), it, today) } ?: balance

    // The bill buffer: how much has to stay in the bank today so every unpaid bill over the next year gets
    // paid on time, given that each paycheck puts one week's share of the bills toward them (or all of it, if
    // the paycheck is smaller than that).
    private fun setAside(today: LocalDate, share: Double, bank: Double): Buffer {
        val horizon = today.plusDays(372)
        val paydays = generateSequence(today.with(TemporalAdjusters.next(payday))) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(horizon) }
            .toList()
        var next = 0
        var contributed = 0.0
        var total = 0.0
        var worst = 0.0
        var tightest: Pair<Bill, LocalDate>? = null
        var firstShort: Pair<Bill, LocalDate>? = null
        bills.flatMap { bill -> bill.unpaid(today, horizon).map { it to bill } }
            .sortedBy { it.first }
            .forEach { (date, bill) ->
                while (next < paydays.size && !paydays[next].isAfter(date)) {
                    contributed += minOf(share, paycheckOn(paydays[next]))
                    next++
                }
                total += bill.amount
                val need = total - contributed
                if (need > worst + 0.004) {
                    worst = need
                    tightest = bill to date
                }
                if (firstShort == null && need > bank + 0.004) firstShort = bill to date
            }
        return Buffer(cents(worst), tightest, firstShort)
    }

    // Safe to spend = the smaller of
    //   spending money: this week's pay − the bills' weekly share − what's been spent since payday, and
    //   spare money: what's in the bank − the bill buffer.
    private fun computeWeek(today: LocalDate): Week {
        val period = lastPayday(today)
        val next = today.with(TemporalAdjusters.next(payday))
        val pay = paycheckOn(period)
        val share = cents(bills.sumOf { it.perWeek })
        // With the bank linked, spent = this pay week's purchases, so the whole week always counts.
        val bankSpent = if (bank.isLinked && bank.checkedAt > 0) bankSpending(period, today).first else null
        val start = weekStartTaken
        val tracking = bankSpent == null && weekStart == period && start != null
        // Tracking that only started mid-week (no earlier balance to carry forward) covers the rest of the week.
        val daysTracked =
            if (tracking && start!!.isAfter(period)) ChronoUnit.DAYS.between(start, next).coerceIn(1, 7) else 7L
        val spent = when {
            bankSpent != null -> bankSpent
            tracking -> cents(maxOf(0.0, weekStartBalance - effectiveBalance() - billsOut(start!!, balanceUpdated ?: today)))
            else -> null
        }
        val afterBills = cents(pay - share)
        val weekMoney = cents(afterBills * daysTracked / 7)
        val spendingMoney = cents(weekMoney - (spent ?: 0.0))
        val bankNow = cents(currentBalance(today))
        val buffer = setAside(today, share, bankNow)
        val free = cents(bankNow - buffer.amount)
        return Week(
            payday = period, nextPayday = next, daysLeft = ChronoUnit.DAYS.between(today, next),
            pay = pay, billsShare = share, afterBills = afterBills, daysTracked = daysTracked, weekMoney = weekMoney,
            trackedFrom = if (bankSpent != null) period else if (tracking) start else null,
            startBalance = if (tracking) weekStartBalance else null,
            spent = spent, spendingMoney = spendingMoney, bank = bankNow,
            bankIsEstimate = balanceUpdated?.isBefore(today) == true && abs(bankNow - balance) > 0.004,
            setAside = buffer.amount, tightest = buffer.tightest, firstShort = buffer.firstShort,
            free = free, safe = cents(minOf(spendingMoney, free)), fromBank = bankSpent != null,
        )
    }

    // The month the end-of-month numbers are for: this month, or next month once this month has no paydays left.
    private fun targetMonthEnd(today: LocalDate, nextPayday: LocalDate): LocalDate {
        val monthEnd = YearMonth.from(today).atEndOfMonth()
        return if (!nextPayday.isAfter(monthEnd)) monthEnd else YearMonth.from(today).plusMonths(1).atEndOfMonth()
    }

    private fun isSetUp() = weeklyIncome > 0 && balanceUpdated != null

    // ---------- Bank sync ----------

    // How a synced transaction counts.
    private enum class Kind { SPEND, BILL, TRANSFER, INCOME, SKIPPED }

    private class Sorted(val txn: BankTxn, val kind: Kind, val bill: Pair<Int, LocalDate>?) // bill = (index in bills, due date)

    // Purchases count from the checking account the balance comes from, and from credit cards.
    private fun spendAccounts(): Set<String> =
        bank.accounts.filter { it.id == bank.accountId || it.isCredit }.map { it.id }.toSet()

    // Moving money between your own accounts, or paying a card or loan. Cash from an ATM is spending.
    private fun isTransfer(t: BankTxn): Boolean {
        val category = "${t.category} ${t.detail}".lowercase()
        if ("withdrawal" in category) return false
        val name = t.name.uppercase()
        return "transfer in" in category || "transfer out" in category || "loan payment" in category ||
            "credit card payment" in category || name.contains("PAYMENT TO CREDIT CARD") ||
            name.contains("CREDIT CARD PAYMENT") || name.contains("CREDIT CARD PMT") ||
            name.contains("PAYMENT THANK YOU") || name.contains("PAYMENT - THANK YOU") ||
            name.startsWith("TRANSFER TO ") || name.startsWith("TRANSFER FROM ")
    }

    private fun isPayroll(t: BankTxn): Boolean {
        val name = t.name.uppercase()
        return "wages" in t.detail.lowercase() || name.contains("PAYROLL") || name.contains("DIR DEP") ||
            name.contains("DIRECT DEP") || name.contains("SALARY")
    }

    // The deposit that is that payday's paycheck: into checking, at least half the expected pay, within two
    // days before (paid early) to a day after.
    private fun payDeposit(payday: LocalDate, upTo: LocalDate = LocalDate.now()): BankTxn? {
        val expected = paycheckOn(payday)
        if (expected <= 0) return null
        val last = minOf(payday.plusDays(1).toEpochDay(), upTo.toEpochDay())
        return bank.txns.firstOrNull {
            it.account == bank.accountId && it.amount >= expected * 0.5 && (isPayroll(it) || !isTransfer(it)) &&
                it.date.toEpochDay() in payday.minusDays(2).toEpochDay()..last
        }
    }

    // "Phone bill" and "VERIZON WIRELESS" don't match; "Verizon" does.
    private fun nameMatches(billName: String, txnName: String): Boolean {
        val name = txnName.lowercase()
        return billName.lowercase().split(Regex("[^a-z0-9]+"))
            .any { it.length >= 3 && it !in genericBillWords && name.contains(it) }
    }

    private val genericBillWords = setOf("bill", "the", "and", "pay", "payment", "monthly", "auto", "autopay", "fee")

    // Which transactions paid which bill due dates: the amount matches (to the cent, near enough), or the name
    // matches and the amount is close, within 4 days of the due date. Choices made by hand come first.
    private fun billMatches(): Map<String, Pair<Int, LocalDate>> {
        val txns = bank.txns
        val overrides = bank.overrides
        val accounts = spendAccounts()
        val result = mutableMapOf<String, Pair<Int, LocalDate>>()
        val taken = mutableSetOf<Pair<Int, LocalDate>>()
        for (t in txns) {
            val name = overrides[t.id]?.takeIf { it.startsWith("bill:") }?.removePrefix("bill:") ?: continue
            val i = bills.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: continue
            val due = bills[i].dueDates(t.date.minusDays(45), t.date.plusDays(46))
                .minByOrNull { abs(ChronoUnit.DAYS.between(it, t.date)) } ?: continue
            result[t.id] = i to due
            taken += i to due
        }
        class Candidate(val txn: BankTxn, val bill: Int, val due: LocalDate, val score: Double)
        val candidates = mutableListOf<Candidate>()
        for (t in txns) {
            if (t.amount >= 0 || t.account !in accounts || t.id in result || overrides[t.id] != null) continue
            val paid = -t.amount
            bills.forEachIndexed { i, bill ->
                if (bill.amount <= 0) return@forEachIndexed
                val nameHit = nameMatches(bill.name, t.name)
                val diff = abs(paid - bill.amount)
                if (diff > maxOf(0.5, bill.amount * 0.01) && !(nameHit && diff <= bill.amount * 0.35)) return@forEachIndexed
                for (due in bill.dueDates(t.date.minusDays(4), t.date.plusDays(5))) {
                    val days = abs(ChronoUnit.DAYS.between(due, t.date))
                    candidates += Candidate(t, i, due, (if (nameHit) 0.0 else 100.0) + diff / bill.amount * 50 + days)
                }
            }
        }
        for (c in candidates.sortedBy { it.score }) {
            if (c.txn.id in result || (c.bill to c.due) in taken) continue
            result[c.txn.id] = c.bill to c.due
            taken += c.bill to c.due
        }
        return result
    }

    private fun sortTxns(): List<Sorted> {
        val accounts = spendAccounts()
        val matches = billMatches()
        val overrides = bank.overrides
        return bank.txns.filter { it.account in accounts }.map { t ->
            val kind = when {
                overrides[t.id] == "skip" -> Kind.SKIPPED
                t.id in matches -> Kind.BILL
                overrides[t.id] == "spend" -> Kind.SPEND
                isTransfer(t) -> Kind.TRANSFER
                t.amount > 0 && (t.category.startsWith("Income", true) || isPayroll(t)) -> Kind.INCOME
                else -> Kind.SPEND // a refund (money back) counts against spending
            }
            Sorted(t, kind, matches[t.id])
        }
    }

    // Spent from `from` through `to`: purchases minus refunds, and every transaction in that time.
    private fun bankSpending(from: LocalDate, to: LocalDate): Pair<Double, List<Sorted>> {
        val week = sortTxns().filter { !it.txn.date.isBefore(from) && !it.txn.date.isAfter(to) }
        return cents(maxOf(0.0, -week.filter { it.kind == Kind.SPEND }.sumOf { it.txn.amount })) to week
    }

    // Bills the bank shows as paid get their "paid" mark, dated the day the money left.
    private fun markBillsFromBank(): List<String> {
        val txnDates = bank.txns.associate { it.id to it.date }
        val names = mutableListOf<String>()
        for ((txnId, match) in billMatches()) {
            val (i, due) = match
            val bill = bills.getOrNull(i) ?: continue
            if (due in bill.paid) continue
            bills[i] = bill.withPaid(due, txnDates[txnId] ?: continue)
            names += bill.name
        }
        return names.distinct()
    }

    // On opening the app (at most every half hour), or when asked: get the latest from the bank.
    private fun syncBank(force: Boolean = false) {
        if (!bank.isLinked || bankSyncing) return
        if (!force && System.currentTimeMillis() - bank.checkedAt < 30 * 60_000L) return
        val url = bank.url ?: return
        bankSyncing = true
        if (force) balanceUpdatedView.text = "Syncing…"
        thread {
            val result = runCatching { BankServer.sync(url, bank.key()) }
            runOnUiThread {
                bankSyncing = false
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { applyBank(it, announce = force) }.onFailure { e ->
                    bank.saveError(e.message ?: "Bank sync failed.")
                    refresh()
                    if (force) Toast.makeText(this, e.message ?: "Bank sync failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // The checking balance becomes the bank balance, paychecks are checked off, and paid bills are marked.
    private fun applyBank(snapshot: BankSnapshot, announce: Boolean) {
        bank.saveSnapshot(snapshot)
        val account = bank.account
        val value = account?.let { it.available ?: it.current }
        if (account == null || value == null) {
            bank.saveError(if (account == null) "Your checking account wasn't found. Pick it again in Settings." else "The bank didn't send a balance.")
            refresh()
            return
        }
        val today = LocalDate.now()
        balance = cents(value)
        balanceUpdated = today
        // This week's pay not in yet (counted anyway, for up to two days), or next week's already in.
        val period = lastPayday(today)
        pendingPay = period.takeIf { ChronoUnit.DAYS.between(it, today) <= 2 && paycheckOn(it) > 0 && payDeposit(it) == null }
        earlyPay = today.with(TemporalAdjusters.next(payday)).takeIf { payDeposit(it) != null }
        val marked = markBillsFromBank()
        save()
        refresh()
        when {
            marked.isNotEmpty() -> showSnack("${marked.joinToString()} marked paid from your bank")
            announce -> showSnack("Synced with ${account.institution ?: "your bank"}")
        }
    }

    private fun bankName() = bank.account?.let { "${it.institution ?: "Bank"} ${it.label}" } ?: "your bank"

    private fun ago(epochMs: Long): String {
        val minutes = (System.currentTimeMillis() - epochMs) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} hr ago"
            minutes < 48 * 60 -> "yesterday"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    // Settings → Connect your bank: the server's address and access key, then which checking account.
    private fun connectBank() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
        }
        fun field(label: String, hint: String, secret: Boolean) = EditText(this).also { edit ->
            box.addView(TextView(this).apply {
                text = label
                setTextColor(secondary)
                textSize = 13f
                setPadding(dp(4), dp(12), 0, dp(4))
                labelFor = View.generateViewId().also { edit.id = it }
            })
            edit.hint = hint
            edit.setSingleLine(true)
            edit.textSize = 16f
            edit.minHeight = dp(48)
            edit.setPadding(dp(14), 0, dp(14), 0)
            edit.setBackgroundResource(R.drawable.input_bg)
            edit.inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
            box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val urlBox = field("Server address", "https://….workers.dev", secret = false)
        val keyBox = field("Access key", "APP_API_TOKEN", secret = true)
        bank.url?.let { urlBox.setText(it) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect your bank")
            .setMessage("Uses your ClearBudget bank server (Plaid). Your bank login and Plaid keys stay on that server; " +
                "this phone keeps only the access key, encrypted.")
            .setView(box)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        val connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun valid() = BankServer.cleanUrl(urlBox.text.toString()) != null && keyBox.text.toString().trim().length >= 32
        connect.isEnabled = valid()
        onTextChange(urlBox) { connect.isEnabled = valid() }
        onTextChange(keyBox) { connect.isEnabled = valid() }
        connect.setOnClickListener {
            val url = BankServer.cleanUrl(urlBox.text.toString()) ?: return@setOnClickListener
            val key = keyBox.text.toString().trim()
            connect.isEnabled = false
            connect.text = "Connecting…"
            thread {
                val result = runCatching { BankServer.snapshot(url, key) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val snapshot = result.getOrNull()
                    val checking = snapshot?.accounts.orEmpty().filter { it.isChecking }
                        .ifEmpty { snapshot?.accounts.orEmpty().filter { it.type == "depository" } }
                    if (snapshot == null || checking.isEmpty()) {
                        connect.isEnabled = true
                        connect.text = "Connect"
                        Toast.makeText(this, result.exceptionOrNull()?.message
                            ?: "No checking account on that server yet. Link your bank in ClearBudget first.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    dialog.dismiss()
                    pickAccount(checking) { account ->
                        bank.connect(url, key, account.id)
                        applyBank(snapshot, announce = false)
                        syncBank(force = true)
                    }
                }
            }
        }
        urlBox.requestFocus()
    }

    private fun pickAccount(choices: List<BankAccount>, onPick: (BankAccount) -> Unit) {
        if (choices.size == 1) return onPick(choices[0])
        AlertDialog.Builder(this)
            .setTitle("Which account is your balance?")
            .setItems(choices.map { a ->
                "${a.institution ?: "Bank"} ${a.label}" + ((a.available ?: a.current)?.let { " · ${money.format(it)}" } ?: "")
            }.toTypedArray()) { _, which -> onPick(choices[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Settings → the bank: sync, switch accounts, or disconnect.
    private fun bankSettings() {
        AlertDialog.Builder(this)
            .setTitle(bankName())
            .setItems(arrayOf("Sync now", "Use a different account", "Disconnect")) { _, which ->
                when (which) {
                    0 -> syncBank(force = true)
                    1 -> pickAccount(bank.accounts.filter { it.type == "depository" }) {
                        bank.chooseAccount(it.id)
                        applyBank(BankSnapshot(bank.accounts, bank.txns, emptyList()), announce = true)
                    }
                    else -> AlertDialog.Builder(this)
                        .setTitle("Disconnect your bank?")
                        .setMessage("You'll go back to updating your balance yourself. Your bank stays linked on the bank server.")
                        .setPositiveButton("Disconnect") { _, _ -> disconnectBank() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun disconnectBank() {
        bank.clear()
        earlyPay = null
        pendingPay = null
        // Spending counts from balance changes again, starting now.
        val today = LocalDate.now()
        weekStart = lastPayday(today)
        weekStartBalance = balance
        weekStartTaken = today
        weekStartProjected = false
        balanceUpdated = today
        save()
        refresh()
        showSnack("Bank disconnected")
    }

    // Tapping the bank balance when the bank is linked.
    private fun showBankStatus() {
        val account = bank.account
        val message = buildString {
            account?.let { a ->
                a.available?.let { append("Available: ${money.format(it)}\n") }
                a.current?.takeIf { it != a.available }?.let { append("Current: ${money.format(it)}\n") }
            }
            append(if (bank.checkedAt > 0) "Synced ${ago(bank.checkedAt)}" else "Not synced yet")
            bank.error?.let { append("\n\n$it") }
            append("\n\nYour bank sends new transactions a few times a day, so the newest can take a few hours to show up.")
        }
        AlertDialog.Builder(this)
            .setTitle(bankName())
            .setMessage(message)
            .setPositiveButton("Sync now") { _, _ -> syncBank(force = true) }
            .setNeutralButton("Purchases") { _, _ -> showPurchases() }
            .setNegativeButton("Close", null)
            .show()
    }

    // This pay week's transactions: what counted as spending, the bills, and what didn't count.
    private fun showPurchases() {
        val today = LocalDate.now()
        val period = lastPayday(today)
        val (spent, week) = bankSpending(period, today)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        lateinit var dialog: AlertDialog
        fun section(title: String, rows: List<Sorted>) {
            if (rows.isEmpty()) return
            dialogHeading(box, title)
            for (s in rows.sortedWith(compareByDescending<Sorted> { it.txn.date }.thenBy { it.txn.name })) {
                txnRow(box, s) {
                    dialog.dismiss()
                    changeTxn(s)
                }
            }
        }
        val counted = week.filter { it.kind == Kind.SPEND }
        if (counted.isEmpty()) dialogParagraph(box, "No purchases since payday (${period.format(dayFormat)}).")
        section("Spending since ${period.format(dayFormat)}", counted)
        section("Bills (not counted)", week.filter { it.kind == Kind.BILL })
        section("Not counted", week.filter { it.kind != Kind.SPEND && it.kind != Kind.BILL })
        dialogParagraph(box, "Tap one to change how it counts. Transfers, card payments and paychecks don't count as spending.")
        dialog = AlertDialog.Builder(this)
            .setTitle("Spent ${money.format(spent)} this week")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .create()
        dialog.show()
    }

    private fun txnRow(into: LinearLayout, s: Sorted, onClick: () -> Unit) {
        val t = s.txn
        val card = bank.accounts.firstOrNull { it.id == t.account }?.takeIf { it.isCredit }
        val note = buildString {
            append(t.date.format(dayFormat))
            if (t.pending) append(" · pending")
            if (card != null) append(" · card${card.mask?.let { " ····$it" } ?: ""}")
            s.bill?.let { (i, due) -> bills.getOrNull(i)?.let { append(" · ${it.name}, due ${due.format(shortDate)}") } }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(4), 0, dp(4))
            background = rippleBackground()
            setOnClickListener { onClick() }
            accessibilityDelegate = clickLabel("Change how it counts")
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = t.name
                setTextColor(if (s.kind == Kind.SPEND || s.kind == Kind.BILL) textColor else secondary)
                textSize = 15f
                maxLines = 2
            })
            addView(TextView(context).apply {
                text = note
                setTextColor(secondary)
                textSize = 13f
            })
        })
        row.addView(TextView(this).apply {
            text = if (t.amount > 0) "+" + money.format(t.amount) else "−" + money.format(-t.amount)
            setTextColor(if (t.amount > 0) positive else if (s.kind == Kind.SPEND || s.kind == Kind.BILL) textColor else secondary)
            textSize = 15f
            fontFeatureSettings = "tnum"
            setPadding(dp(8), 0, 0, 0)
        })
        into.addView(row)
    }

    // Count a transaction differently: not at all, as spending, or as a bill payment.
    private fun changeTxn(s: Sorted) {
        val t = s.txn
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        fun set(choice: String?) {
            bank.setOverride(t.id, choice)
            // No longer this bill's payment: take off the "paid" mark it gave the bill.
            if (choice?.startsWith("bill:") != true) s.bill?.let { (i, due) -> unmarkBankPaid(i, due, t.date) }
            markBillsFromBank()
            save()
            refresh()
            showPurchases()
        }
        when (s.kind) {
            Kind.SPEND -> {
                choices += "Don't count it" to { set("skip") }
                if (bills.isNotEmpty()) choices += "It paid a bill…" to {
                    AlertDialog.Builder(this)
                        .setTitle("Which bill did it pay?")
                        .setItems(bills.map { "${it.name} · ${money.format(it.amount)}" }.toTypedArray()) { _, which ->
                            set("bill:${bills[which].name}")
                        }
                        .setNegativeButton("Cancel") { _, _ -> showPurchases() }
                        .show()
                    Unit
                }
            }
            Kind.BILL -> choices += "Not a bill: count it as spending" to { set("spend") }
            Kind.SKIPPED -> choices += "Count it again" to { set(null) }
            Kind.TRANSFER, Kind.INCOME -> choices += "Count it as spending" to { set("spend") }
        }
        if (bank.overrides[t.id] != null && s.kind != Kind.SKIPPED) choices += "Undo my choice" to { set(null) }
        AlertDialog.Builder(this)
            .setTitle("${t.name} · ${if (t.amount > 0) "+" else "−"}${money.format(abs(t.amount))}")
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .setNegativeButton("Cancel") { _, _ -> showPurchases() }
            .setOnCancelListener { showPurchases() }
            .show()
    }

    // A bill that was marked paid because of this transaction isn't paid after all.
    private fun unmarkBankPaid(index: Int, due: LocalDate, on: LocalDate) {
        val bill = bills.getOrNull(index) ?: return
        if (bill.paid[due] == on) bills[index] = bill.withoutPaid(due)
    }

    // ---------- Drawing the screens ----------

    // Never let a bad number or date take the whole app down; show what happened instead.
    private fun refresh() {
        try {
            showSetup()
            showBills()
            recalculate()
        } catch (e: Exception) {
            Toast.makeText(this, "Something went wrong showing your budget. Try updating the app.", Toast.LENGTH_LONG).show()
        }
    }

    // First run: a three-step card until pay and bank balance are set.
    private fun showSetup() {
        val setUp = isSetUp()
        val payDone = weeklyIncome > 0
        val billsDone = bills.isNotEmpty() || billsNone
        val balanceDone = balanceUpdated != null
        setupCard.visibility = if (setUp) View.GONE else View.VISIBLE
        summaryMain.visibility = if (setUp) View.VISIBLE else View.GONE
        paydaysEmpty.visibility = if (setUp) View.GONE else View.VISIBLE
        paydaysContent.visibility = if (setUp) View.VISIBLE else View.GONE
        billsNoneLink.visibility = if (!setUp && !billsDone) View.VISIBLE else View.GONE

        val dayName = payday.getDisplayName(TextStyle.FULL, Locale.getDefault())
        payValue.text = if (payDone) "${shortMoney(weeklyIncome)} every $dayName" else "Not set"
        payValue.setTextColor(if (payDone) textColor else warning)
        if (setUp) return

        fun step(icon: Int, value: Int, done: Boolean, normalIcon: Int, text: String) {
            findViewById<ImageView>(icon).setImageResource(if (done) R.drawable.ic_check else normalIcon)
            findViewById<TextView>(value).apply {
                this.text = text
                setTextColor(if (done) textColor else secondary)
            }
        }
        step(R.id.setup_pay_icon, R.id.setup_pay_value, payDone, R.drawable.ic_tab_paydays,
            if (payDone) "${shortMoney(weeklyIncome)} every $dayName" else "Not set")
        step(R.id.setup_bills_icon, R.id.setup_bills_value, billsDone, R.drawable.ic_tab_bills, when {
            bills.isNotEmpty() -> "${bills.size} bill${if (bills.size == 1) "" else "s"}"
            billsNone -> "None"
            else -> "Rent, phone, subscriptions…"
        })
        step(R.id.setup_balance_icon, R.id.setup_balance_value, balanceDone, R.drawable.ic_wallet,
            if (balanceDone) money.format(balance) else "What's in your account now")
        // Only offered when bills are the next step.
        findViewById<View>(R.id.setup_no_bills).visibility = if (payDone && !billsDone) View.VISIBLE else View.GONE

        val (label, action) = when {
            !payDone -> "Set your pay" to { editPay() }
            !billsDone -> "Add a bill" to { openBillSheet(null) }
            else -> "Add bank balance" to { editBalance() }
        }
        findViewById<Button>(R.id.setup_next).apply {
            text = label
            setOnClickListener { action() }
        }
        if (!payDone) {
            paydaysEmptyBody.text = "Add your weekly pay to see what's left after each payday's bills."
            paydaysEmptyButton.text = "Set your pay"
            paydaysEmptyButton.setOnClickListener { editPay() }
        } else {
            paydaysEmptyBody.text = "Add your bank balance to see what's left after each payday."
            paydaysEmptyButton.text = "Add bank balance"
            paydaysEmptyButton.setOnClickListener { editBalance() }
        }
    }

    private fun setNoBills() {
        billsNone = true
        save()
        refresh()
    }

    // Bills, split into "Before payday" and "Later".
    private fun showBills() {
        val today = LocalDate.now()
        val nextPayday = today.with(TemporalAdjusters.next(payday))
        val next = bills.associateWith { it.nextDue(today) }
        val sorted = bills.sortedBy { next.getValue(it).toEpochDay() }
        val before = sorted.filter { next.getValue(it).isBefore(nextPayday) }
        val later = sorted.filter { !next.getValue(it).isBefore(nextPayday) }
        billList.removeAllViews()
        fun section(title: String, total: String?) {
            val header = layoutInflater.inflate(R.layout.bill_section, billList, false)
            header.findViewById<TextView>(R.id.section_title).text = title
            header.findViewById<TextView>(R.id.section_total).text = total ?: ""
            billList.addView(header)
        }
        if (before.isNotEmpty()) {
            section("Before payday · ${nextPayday.format(dayFormat)}", money.format(billsDueIn(today, nextPayday)))
            before.forEachIndexed { i, bill -> addBillRow(bill, next.getValue(bill), today, last = i == before.lastIndex) }
        }
        if (later.isNotEmpty()) {
            section(if (before.isEmpty()) "Coming up" else "Later", null)
            later.forEachIndexed { i, bill -> addBillRow(bill, next.getValue(bill), today, last = i == later.lastIndex) }
        }
        billsEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
        billsHint.visibility = if (bills.isEmpty()) View.GONE else View.VISIBLE
        val perWeek = bills.sumOf { it.perWeek }
        billsSubtitle.text = when {
            bills.isEmpty() -> "Add what you pay regularly"
            else -> "${bills.size} bill${if (bills.size == 1) "" else "s"} · about ${compactMoney(perWeek)} a week " +
                "(${compactMoney(perWeek * 365.25 / 7 / 12)} a month)"
        }
        // The dot on the Bills tab: something unpaid is due today or tomorrow.
        val dueSoon = bills.any { it.unpaid(today, today.plusDays(2)).isNotEmpty() }
        navItems[2].findViewById<View>(R.id.nav_badge).visibility = if (dueSoon) View.VISIBLE else View.GONE
        navItems[2].contentDescription = if (dueSoon) "Bills, a bill is due today or tomorrow" else "Bills"
        updateFab(animate = true)
    }

    private fun addBillRow(bill: Bill, due: LocalDate, today: LocalDate, last: Boolean) {
        val row = layoutInflater.inflate(R.layout.bill_row, billList, false)
        val daysLeft = ChronoUnit.DAYS.between(today, due)
        val soon = daysLeft <= 3
        row.findViewById<View>(R.id.bill_row_chip).setBackgroundResource(if (soon) R.drawable.chip_warn else R.drawable.chip_today)
        row.findViewById<TextView>(R.id.bill_row_month).apply {
            text = due.format(monthShort)
            setTextColor(if (soon) warning else secondary)
        }
        row.findViewById<TextView>(R.id.bill_row_day).apply {
            text = due.dayOfMonth.toString()
            setTextColor(if (soon) warning else textColor)
        }
        row.findViewById<TextView>(R.id.bill_row_name).text = bill.name
        row.findViewById<TextView>(R.id.bill_row_amount).text = money.format(bill.amount)
        // "Oct 10 paid ✓" stays for a week, unless the next one is already close.
        val paidMark = bill.recentPaidMark(today)?.takeIf { !soon }
        val dueText = buildString {
            if (paidMark != null) append("${paidMark.format(shortDate)} paid ✓ · next due ${dueIn(daysLeft)}")
            else append("Due ${dueIn(daysLeft)}")
            if (bill.freq != Freq.MONTHLY) append(" · ${bill.freq.label}")
        }
        row.findViewById<TextView>(R.id.bill_row_due).apply {
            text = dueText
            setTextColor(when {
                paidMark != null -> positive
                soon -> warning
                else -> secondary
            })
        }
        row.findViewById<View>(R.id.bill_row_divider).visibility = if (last) View.GONE else View.VISIBLE
        val content = row.findViewById<View>(R.id.bill_row_content)
        content.contentDescription = "${bill.name}, ${money.format(bill.amount)}, next due ${due.format(dayFormat)}. $dueText"
        makeSwipeable(row, content, onTap = { openBillSheet(bill) }, onDelete = { deleteBill(bill) }, onPaid = { markPaid(bill) })
        billList.addView(row)
    }

    // Summary and Paydays. The forecast walks forward one pay period at a time: each payday adds a paycheck,
    // and each bill comes out of the period it's due in. Today's paycheck (if today is payday) is assumed to
    // already be in the balance; bills due today are assumed not paid yet (unless marked paid).
    private fun recalculate() {
        val today = LocalDate.now()
        val week = computeWeek(today)
        val targetEnd = targetMonthEnd(today, week.nextPayday)
        val afterTarget = targetEnd.plusDays(1)
        val monthName = targetEnd.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val inTarget = generateSequence(week.nextPayday) { it.plusWeeks(1) }.takeWhile { !it.isAfter(targetEnd) }.toList()
        val paydays = generateSequence(week.nextPayday) { it.plusWeeks(1) }.take(maxOf(4, inTarget.size)).toList()
        val starts = listOf(today) + paydays

        // End of the month: after bills and before any other spending, and if you spend your weekly money.
        val endBillsOnly = cents(week.bank + inTarget.sumOf { paycheckOn(it) } - billsDueIn(today, afterTarget))
        val thisWeekPart = if (week.daysLeft > 0) minOf(1.0, ChronoUnit.DAYS.between(today, afterTarget).toDouble() / week.daysLeft) else 1.0
        val spendRest = inTarget.sumOf { p ->
            maxOf(0.0, paycheckOn(p) - week.billsShare) * minOf(7L, ChronoUnit.DAYS.between(p, afterTarget)) / 7
        }
        val endIfSpending = cents(endBillsOnly - maxOf(0.0, week.safe) * thisWeekPart - spendRest)

        forecastList.removeAllViews()
        var running = week.bank
        var firstShortRow: Pair<LocalDate, Double>? = null
        val chartLabels = mutableListOf<String>()
        val chartValues = mutableListOf<Double>()
        val spoken = mutableListOf<String>()
        starts.forEachIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: start.plusWeeks(1)
            // One entry per time a bill comes due in this pay period, in date order.
            val due = bills.flatMap { bill -> bill.unpaid(start, end).map { it to bill } }.sortedBy { it.first }
            val billsDue = cents(due.sumOf { it.second.amount })
            val isPayday = i > 0
            val paycheck = if (isPayday) paycheckOn(start) else 0.0
            running = cents(running + paycheck - billsDue)
            if (running < 0 && firstShortRow == null) firstShortRow = start to running
            chartLabels += when {
                i == 0 -> "Now"
                i == 1 || start.month != starts[i - 1].month -> start.format(shortDate)
                else -> start.dayOfMonth.toString()
            }
            chartValues += running
            spoken += "${if (isPayday) start.format(dayFormat) else "now"} ${compactMoney(running)}"
            forecastList.addView(forecastRow(start, isPayday, paycheck, due, billsDue, running, week))
            if (start == inTarget.lastOrNull()) {
                forecastList.addView(endOfMonthRow(monthName, endBillsOnly, endIfSpending, targetEnd, paydays, start))
            }
        }

        paydaysSubtitle.apply {
            val short = firstShortRow
            if (short != null) {
                text = "Heads up: ${if (short.first == today) "before payday" else short.first.format(dayFormat)} " +
                    "you'd be ${money.format(-short.second)} short"
                setTextColor(negative)
            } else {
                text = "Your next ${paydays.size} paydays"
                setTextColor(secondary)
            }
        }
        chartCard.visibility = if (chartValues.size >= 2) View.VISIBLE else View.GONE
        val low = chartValues.minOrNull() ?: 0.0
        chart.setData(
            chartLabels, chartValues,
            "Bank balance after each payday's bills: ${spoken.joinToString("; ")}. Lowest ${compactMoney(low)}.",
        ) { compactMoney(it) }

        showSummary(week, today, monthName, endIfSpending)
    }

    // One pay period on the Paydays tab. Tapping it shows each bill with its date.
    private fun forecastRow(
        start: LocalDate, isPayday: Boolean, paycheck: Double, due: List<Pair<LocalDate, Bill>>,
        billsDue: Double, running: Double, week: Week,
    ): View {
        val row = layoutInflater.inflate(R.layout.forecast_row, forecastList, false)
        row.findViewById<TextView>(R.id.chip_top).text = if (isPayday) start.format(weekday) else "Today"
        row.findViewById<TextView>(R.id.chip_day).text = start.dayOfMonth.toString()
        if (!isPayday) {
            row.findViewById<View>(R.id.chip).setBackgroundResource(R.drawable.chip_today)
            row.findViewById<TextView>(R.id.chip_top).setTextColor(secondary)
            row.findViewById<TextView>(R.id.chip_day).setTextColor(textColor)
        }
        row.findViewById<TextView>(R.id.forecast_title).apply {
            when {
                !isPayday -> text = "Bank now ${shortMoney(week.bank)}"
                paycheck > 0 -> {
                    text = "+" + money.format(paycheck)
                    setTextColor(positive)
                }
                else -> {
                    text = "No pay"
                    setTextColor(secondary)
                }
            }
        }
        row.findViewById<TextView>(R.id.forecast_changed).apply {
            visibility = if (isPayday && start in paycheckChanges) View.VISIBLE else View.GONE
            text = "usually ${shortMoney(weeklyIncome)}"
        }
        row.findViewById<TextView>(R.id.forecast_bills).text = SpannableStringBuilder().apply {
            if (due.isEmpty()) {
                append(if (isPayday) "No bills" else "No bills before payday")
            } else {
                append("−" + money.format(billsDue), ForegroundColorSpan(textColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val names = due.map { it.second }.groupingBy { it }.eachCount().entries
                    .joinToString { (bill, times) -> if (times > 1) "${bill.name} ×$times" else bill.name }
                append(if (isPayday) " · $names" else " before payday · $names")
            }
            if (!isPayday && week.payday == today() && week.pay > 0) append(" · includes today's pay")
        }
        row.findViewById<TextView>(R.id.forecast_note).apply {
            visibility = if (running < 0) View.VISIBLE else View.GONE
            text = "Short ${money.format(-running)}"
        }
        showMoney(row.findViewById(R.id.forecast_left), running)

        val details = row.findViewById<LinearLayout>(R.id.forecast_details)
        for ((date, bill) in due) detailLine(details, "${date.format(dayFormat)} · ${bill.name}", money.format(bill.amount))
        if (due.isEmpty()) detailLine(details, "No bills in this pay week", "")
        details.addView(link(
            when {
                isPayday -> "Change this paycheck (usually ${shortMoney(weeklyIncome)})"
                bank.isLinked -> "Bank details"
                else -> "Update bank balance"
            },
        ) { if (isPayday) editPaycheck(start) else editBalance() })
        details.visibility = if (start in openForecastRows) View.VISIBLE else View.GONE
        row.setOnClickListener {
            val open = details.visibility != View.VISIBLE
            details.visibility = if (open) View.VISIBLE else View.GONE
            if (open) openForecastRows += start else openForecastRows -= start
        }
        row.accessibilityDelegate = clickLabel("Show or hide details")
        return row
    }

    // "End of October": the bank on the last day of the month, after that month's last payday row.
    private fun endOfMonthRow(
        monthName: String, endBillsOnly: Double, endIfSpending: Double, targetEnd: LocalDate,
        paydays: List<LocalDate>, lastPayday: LocalDate,
    ): View {
        val row = layoutInflater.inflate(R.layout.forecast_end_row, forecastList, false)
        row.findViewById<TextView>(R.id.end_label).text = "End of $monthName"
        row.findViewById<TextView>(R.id.end_spending).text =
            "before spending · ${money.format(endIfSpending)} if you spend your weekly money"
        showMoney(row.findViewById(R.id.end_balance), endBillsOnly)
        // Bills due after month end but before the next payday are already taken out in the row above,
        // which is why this number can be higher.
        val nextAfter = paydays.firstOrNull { it.isAfter(targetEnd) } ?: lastPayday.plusWeeks(1)
        val laterDue = bills.flatMap { bill -> bill.unpaid(targetEnd.plusDays(1), nextAfter).map { it to bill } }
            .sortedBy { it.first }
        if (laterDue.isNotEmpty()) {
            val first = laterDue.first().first
            val last = laterDue.last().first
            val range = if (first == last) first.format(shortDate) else "${first.format(shortDate)}–${last.dayOfMonth}"
            val names = laterDue.map { it.second.name }.distinct().joinToString()
            row.findViewById<TextView>(R.id.end_after).apply {
                visibility = View.VISIBLE
                text = "Before $names (${money.format(laterDue.sumOf { it.second.amount })}) due $range"
            }
        }
        return row
    }

    private fun showSummary(week: Week, today: LocalDate, monthName: String, endIfSpending: Double) {
        val shown = maxOf(0.0, week.safe)
        hero.setBackgroundResource(if (week.safe < 0) R.drawable.hero_red else R.drawable.hero_green)
        animateHero(shown)
        val until = week.nextPayday.format(dayFormat)
        val staleDays = balanceUpdated?.let { ChronoUnit.DAYS.between(it, today) } ?: 0L
        heroNote.text = when {
            bills.isEmpty() && !billsNone -> "No bills added yet, so this may be too high"
            week.free < 0 -> "${money.format(-week.free)} short for bills" +
                (week.firstShort?.let { " by ${it.second.format(shortDate)} (${it.first.name})" } ?: "")
            week.pay < week.billsShare -> "Your bills cost ${money.format(week.billsShare - week.pay)} a week more than your pay"
            week.spendingMoney < 0 -> "You've spent ${money.format(-week.spendingMoney)} more than this week's money"
            week.free < week.spendingMoney -> "Lowered from ${money.format(week.spendingMoney)} so your bills stay covered"
            week.spent == null -> "Until payday, $until · update your balance to count spending"
            week.daysLeft > 1 && shown > 0 -> "Until payday, $until · about ${shortMoney(cents(shown / week.daysLeft))} a day"
            else -> "Until payday, $until"
        }
        // "Spent $60 of $229.55 this week", with a bar.
        val spent = week.spent
        if (spent != null && week.weekMoney > 0) {
            heroProgress.visibility = View.VISIBLE
            heroProgress.progress = (spent / week.weekMoney * 1000).toInt().coerceIn(0, 1000)
        } else {
            heroProgress.visibility = View.GONE
        }
        heroBreakdown.text = when {
            spent == null -> "Spending not counted yet" + (if (staleDays >= 2) " · balance is $staleDays days old" else "")
            else -> "Spent ${shortMoney(spent)} of ${shortMoney(week.weekMoney)} this week" +
                (if (week.daysTracked < 7) " (since ${week.trackedFrom?.format(weekday)})" else "")
        }
        hero.contentDescription = "Safe to spend this week ${money.format(shown)}. ${heroNote.text}. ${heroBreakdown.text}."

        heroEndLabel.text = "End of $monthName"
        heroEnd.text = money.format(endIfSpending)
        heroEnd.setTextColor(if (endIfSpending < 0) negative else textColor)
        val extra = cents(week.free - shown)
        if (extra >= 0) {
            heroCushion.text = money.format(extra)
            heroCushion.setTextColor(textColor)
            heroCushionCaption.text = "not needed for bills"
            heroCushionCaption.setTextColor(secondary)
        } else {
            heroCushion.text = money.format(0)
            heroCushion.setTextColor(negative)
            heroCushionCaption.text = "${money.format(-extra)} short for bills"
            heroCushionCaption.setTextColor(negative)
        }

        // Bank balance row
        val updated = balanceUpdated
        val trackingThisWeek = weekStart == week.payday
        balanceValue.text = if (week.bankIsEstimate) "≈ ${money.format(week.bank)}" else money.format(week.bank)
        val linked = bank.isLinked
        val pending = pendingPay?.takeIf { updated != null && !it.isAfter(updated) }
        val early = earlyPay?.takeIf { updated != null && it.isAfter(updated) && !today.isAfter(it) }
        val syncOld = linked && System.currentTimeMillis() - bank.checkedAt > 24 * 3_600_000L
        balanceUpdatedView.text = when {
            updated == null -> if (linked) "Syncing…" else "Tap to update"
            linked && bank.error != null -> "Couldn't sync · tap for details"
            pending != null -> "Includes ${if (pending == today) "today" else pending.format(weekday)}'s " +
                "${shortMoney(paycheckOn(pending))} pay" + if (linked) " (not in yet)" else ""
            early != null -> "Not counting ${early.format(weekday)}'s ${shortMoney(paycheckOn(early))} pay until then"
            linked -> "${bank.account?.institution ?: "Bank"} · synced ${ago(bank.checkedAt)}"
            week.bankIsEstimate -> "Estimated · you entered ${money.format(balance)} on ${updated.format(weekday)}"
            !trackingThisWeek -> "Update to count this week's spending"
            staleDays == 0L -> "Updated today"
            staleDays == 1L -> "Updated yesterday"
            else -> "Updated $staleDays days ago"
        }
        balanceUpdatedView.setTextColor(when {
            linked -> if (bank.error != null || syncOld) warning else secondary
            updated == null || week.bankIsEstimate || !trackingThisWeek || staleDays >= 3 -> warning
            else -> secondary
        })
        findViewById<ImageView>(R.id.balance_edit_icon).setImageResource(if (linked) R.drawable.ic_sync else R.drawable.ic_edit)
        findViewById<TextView>(R.id.log_purchase).text = if (linked) "This week's purchases" else "Log a purchase"

        glancePayday.text = "${week.nextPayday.format(shortDate)} · +${shortMoney(paycheckOn(week.nextPayday))}"
        val nextBill = bills.minByOrNull { it.nextDue(today) }
        if (nextBill == null) {
            glanceBill.text = "None"
            glanceBill.setTextColor(secondary)
        } else {
            val daysLeft = ChronoUnit.DAYS.between(today, nextBill.nextDue(today))
            glanceBill.text = "${nextBill.name} · ${shortMoney(nextBill.amount)} · ${dueIn(daysLeft)}"
            glanceBill.setTextColor(if (daysLeft <= 3) warning else textColor)
        }
        val dueBefore = bills.sumOf { it.unpaid(today, week.nextPayday).size }
        glanceDue.text = if (dueBefore == 0) {
            "Nothing"
        } else {
            "${money.format(billsDueIn(today, week.nextPayday))} · $dueBefore bill${if (dueBefore == 1) "" else "s"}"
        }
    }

    // Counts the headline number up or down to its new value, and tells screen readers it changed.
    private fun animateHero(target: Double) {
        val from = heroShown ?: target
        heroShown = target
        heroAnimator?.cancel()
        if (from == target) {
            heroAmount.text = bigMoney(target)
            return
        }
        if (a11y.isEnabled) hero.announceForAccessibility("Safe to spend now ${money.format(target)}")
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

    // "How we got $130.13": short version first, the full math one tap away.
    private fun explainSafeToSpend() {
        val today = LocalDate.now()
        val week = computeWeek(today)
        val shown = maxOf(0.0, week.safe)
        val usedStep1 = week.spendingMoney <= week.free
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        val from = week.trackedFrom
        val since = if (from == null || from == week.payday) "payday" else from.format(dayFormat)
        val spent = week.spent

        // Short version
        dialogRow(box, "This week's money", money.format(week.weekMoney),
            note = if (week.daysTracked < 7 && from != null) "${dayRange(from, week.nextPayday)} (${week.daysTracked} of 7 days)" else null)
        lateinit var dialog: AlertDialog
        if (spent != null && week.fromBank) {
            val count = bankSpending(week.payday, today).second.count { it.kind == Kind.SPEND }
            dialogRow(box, "Spent since $since", if (spent > 0) "−" + money.format(spent) else money.format(0.0),
                note = "$count purchase${if (count == 1) "" else "s"} from ${bank.account?.institution ?: "your bank"} · " +
                    "bills, transfers and pay don't count")
        } else if (spent != null && week.startBalance != null) {
            dialogRow(box, "Spent since $since", if (spent > 0) "−" + money.format(spent) else money.format(0.0),
                note = "${shortMoney(week.startBalance)} → ${shortMoney(effectiveBalance())}, not counting bills")
        } else {
            dialogRow(box, "Spent since payday", "not counted yet")
        }
        dialogLine(box)
        dialogRow(box, "Left to spend", money.format(week.spendingMoney), total = true)
        dialogParagraph(box,
            if (usedStep1) "✓ Your bank (${money.format(week.bank)}) covers this and every bill coming up."
            else "Your bank (${money.format(week.bank)}) can only spare ${money.format(maxOf(0.0, week.free))} after keeping " +
                "${money.format(week.setAside)} for bills, so that's what's safe."
        )
        if (spent == null) dialogParagraph(box, "Update your bank balance to count what you've spent since payday.")

        // Full math, hidden at first
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        dialogHeading(detail, "This week's money")
        dialogRow(detail, "Paycheck, ${week.payday.format(dayFormat)}", money.format(week.pay))
        dialogRow(detail, "Bills per week", "−" + money.format(week.billsShare),
            note = "every bill spread evenly over the weeks")
        dialogRow(detail, "Left after bills", money.format(week.afterBills), total = true)
        if (week.daysTracked < 7) dialogRow(detail, "× ${week.daysTracked} of 7 days", money.format(week.weekMoney))
        dialogHeading(detail, "What the bank can spare")
        dialogRow(detail, if (week.bankIsEstimate) "Bank balance (estimated)" else "Bank balance today", money.format(week.bank))
        dialogRow(detail, "Bill buffer", "−" + money.format(week.setAside), note = "kept for bills that come before enough paychecks")
        dialogRow(detail, "Spare money", money.format(week.free), total = true)
        val tightest = week.tightest
        dialogParagraph(detail,
            if (week.setAside > 0 && tightest != null) {
                "Each paycheck puts ${money.format(week.billsShare)} toward bills, but some weeks bills come due before " +
                    "enough paychecks arrive. Keeping ${money.format(week.setAside)} covers every gap for the next 12 " +
                    "months; the tightest point is ${tightest.first.name} on ${tightest.second.format(shortDate)}."
            } else {
                "Your paychecks cover every bill on time, so no buffer is needed."
            }
        )
        val extra = cents(week.free - shown)
        if (extra > 0) {
            dialogParagraph(detail, "Extra savings: ${money.format(week.free)} − ${money.format(shown)} = ${money.format(extra)}. " +
                "No bill needs it, and it isn't part of your weekly money.")
        }
        if (week.daysTracked < 7 && from != null) {
            dialogParagraph(detail, "This week only counts ${week.daysTracked} of 7 days because tracking started on " +
                "${from.format(weekday)}. Full weeks start ${week.nextPayday.format(weekday)}.")
        }
        if (week.bankIsEstimate) {
            dialogParagraph(detail, if (week.fromBank) {
                "Your balance is an estimate: the last one synced, plus paychecks and minus bills since then."
            } else {
                "Your balance is an estimate: the last one you entered, plus paychecks and minus bills since then."
            })
        }

        if (week.fromBank) box.addView(link("See this week's purchases") {
            dialog.dismiss()
            showPurchases()
        })
        val toggle = link("Show the full math") {}
        toggle.setOnClickListener {
            val open = detail.visibility != View.VISIBLE
            detail.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Hide the full math" else "Show the full math"
        }
        box.addView(toggle)
        box.addView(detail)
        box.addView(link("Change the ${week.payday.format(shortDate)} paycheck") {
            dialog.dismiss()
            editPaycheck(week.payday)
        })

        dialog = AlertDialog.Builder(this)
            .setTitle("How we got ${money.format(shown)}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Got it", null)
            .create()
        dialog.show()
    }

    private fun dayRange(from: LocalDate, nextPayday: LocalDate): String {
        val lastDay = nextPayday.minusDays(1)
        return if (from == lastDay) from.format(weekday) else "${from.format(weekday)}–${lastDay.format(weekday)}"
    }

    // ---------- Small views built in code ----------

    private fun dialogHeading(into: LinearLayout, text: String) = into.addView(TextView(this).apply {
        this.text = text
        setTextColor(secondary)
        textSize = 13f
        typeface = medium
        setPadding(0, dp(12), 0, dp(4))
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    })

    private fun dialogRow(into: LinearLayout, label: String, value: String, total: Boolean = false, note: String? = null) =
        into.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = label
                    setTextColor(textColor)
                    textSize = 15f
                    if (total) typeface = medium
                })
                if (note != null) addView(TextView(context).apply {
                    text = note
                    setTextColor(secondary)
                    textSize = 13f
                })
            })
            addView(TextView(context).apply {
                text = value
                setTextColor(textColor)
                textSize = 15f
                gravity = Gravity.END
                fontFeatureSettings = "tnum"
                setPadding(dp(8), 0, 0, 0)
                if (total) typeface = medium
            })
        })

    private fun dialogLine(into: LinearLayout) = into.addView(View(this).apply {
        setBackgroundColor(getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    })

    private fun dialogParagraph(into: LinearLayout, text: String) = into.addView(TextView(this).apply {
        this.text = text
        setTextColor(secondary)
        textSize = 14f
        setPadding(0, dp(12), 0, 0)
    })

    // A bill line in an opened payday row: "Sun, Nov 1 · Rent ........ $1,200.00".
    private fun detailLine(into: LinearLayout, label: String, value: String) = into.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, dp(2))
        addView(TextView(context).apply {
            text = label
            setTextColor(secondary)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = value
            setTextColor(textColor)
            textSize = 14f
            fontFeatureSettings = "tnum"
        })
    })

    // A green text button.
    private fun link(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(accent)
        textSize = 15f
        typeface = medium
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        background = rippleBackground()
        setOnClickListener { onClick() }
    }

    // ---------- Editing ----------

    private fun balanceState() =
        BalanceState(balance, balanceUpdated, pendingPay, weekStart, weekStartBalance, weekStartTaken, weekStartProjected)

    private fun restore(state: BalanceState) {
        balance = state.balance
        balanceUpdated = state.updated
        pendingPay = state.pendingPay
        weekStart = state.weekStart
        weekStartBalance = state.startBalance
        weekStartTaken = state.startTaken
        weekStartProjected = state.startProjected
    }

    private fun editBalance(
        message: String = "What's in your account right now? Spending is worked out from how this changes, so update it every few days.",
    ) {
        if (bank.isLinked) return showBankStatus()
        val week = computeWeek(LocalDate.now())
        askAmount(
            "Bank balance",
            message,
            if (balanceUpdated != null) week.bank else null,
            signed = true,
            // Only when the number shown is the one you entered (not an estimate moved forward from an old one).
            neutral = when {
                balanceUpdated == null -> "Connect bank" to { connectBank() }
                !week.bankIsEstimate -> "No change" to { saveBalance(effectiveBalance()) }
                else -> null
            },
        ) { saveBalance(it) }
    }

    // "I spent $12.50": takes it off the bank balance.
    private fun logPurchase() {
        val week = computeWeek(LocalDate.now())
        askAmount("Log a purchase", "How much did you spend? It comes off your bank balance (${money.format(week.bank)}).", null) { amount ->
            if (amount > 0) saveBalance(cents(week.bank - amount), logged = amount)
        }
    }

    private fun saveBalance(value: Double, logged: Double? = null) {
        val before = balanceState()
        val billsBefore = bills.toList()
        val today = LocalDate.now()
        val period = lastPayday(today)
        val oldDate = balanceUpdated
        val oldBalance = effectiveBalance()
        // The day tracking started: a balance lower than the one entered earlier today is either spending or
        // a fix to that earlier number, so ask (a logged purchase is always spending).
        val drop = if (logged == null && oldDate == today && weekStart == period && weekStartTaken == today &&
            !weekStartProjected
        ) cents(oldBalance - value) else 0.0
        if (weekStart != period) {
            // First balance this pay period. Carry the last one forward to payday so spending since payday
            // counts; with no earlier balance, start counting from today.
            weekStart = period
            if (oldDate != null && oldDate.isBefore(period)) {
                weekStartBalance = project(oldBalance, oldDate, period)
                weekStartTaken = period
                weekStartProjected = true
            } else {
                weekStartBalance = value
                weekStartTaken = today
                weekStartProjected = false
            }
        }
        balance = value
        balanceUpdated = today
        pendingPay = null
        // More money than expected (overtime, a refund): nothing counts as spent so far, but the whole week
        // stays tracked.
        val start = weekStartTaken ?: today
        val out = billsOut(start, today)
        if (balance > weekStartBalance - out + 0.004) weekStartBalance = cents(balance + out)
        save()
        refresh()

        fun finish() {
            save()
            refresh()
            val week = computeWeek(today)
            val message = if (logged != null) {
                "Logged ${money.format(logged)} · balance ${money.format(value)}"
            } else {
                "Balance saved" + (week.spent?.takeIf { it > 0 }?.let { " · ${money.format(it)} spent ${sinceText(week)}" } ?: "")
            }
            showSnack(message) {
                restore(before)
                bills.clear()
                bills.addAll(billsBefore)
                save()
                refresh()
            }
        }

        fun askCorrection(explained: Double) {
            val unexplained = cents(drop - explained)
            if (unexplained < 0.005) return finish()
            AlertDialog.Builder(this)
                .setTitle("Did you spend ${money.format(unexplained)}?")
                .setMessage("This is ${money.format(unexplained)} less than the ${money.format(oldBalance)} you entered " +
                    "earlier today. Was that spending, or was ${money.format(oldBalance)} a mistake?")
                .setPositiveButton("Spending") { _, _ -> finish() }
                .setNegativeButton("A mistake") { _, _ ->
                    weekStartBalance = cents(weekStartBalance - unexplained)
                    finish()
                }
                .setOnCancelListener { finish() }
                .show()
        }

        // Bills due today: if the balance dropped by at least that much, ask whether they've come out.
        fun askBillsOut() {
            val dueToday = bills.filter { it.unpaid(today, today.plusDays(1)).isNotEmpty() }
            val total = dueToday.sumOf { it.amount }
            val spent = computeWeek(today).spent ?: 0.0
            if (logged != null || dueToday.isEmpty() || spent < total - 0.004) return askCorrection(0.0)
            val names = dueToday.joinToString { it.name }
            AlertDialog.Builder(this)
                .setTitle("Did $names come out today?")
                .setMessage("$names (${money.format(total)}) is due today, and your balance dropped by at least that much.")
                .setPositiveButton("Yes, it's paid") { _, _ ->
                    for (bill in dueToday) {
                        val i = bills.indexOf(bill)
                        if (i >= 0) bills[i] = bill.withPaid(today, today)
                    }
                    // Paid marks only count after the starting balance's day, so take it off that balance instead.
                    if (weekStartTaken == today) weekStartBalance = cents(weekStartBalance - total)
                    askCorrection(total)
                }
                .setNegativeButton("Not yet") { _, _ -> askCorrection(0.0) }
                .setOnCancelListener { askCorrection(0.0) }
                .show()
        }

        // Payday morning: a balance about a whole paycheck lower than expected probably doesn't have it yet.
        val week = computeWeek(today)
        val pay = paycheckOn(period)
        if (logged == null && today == period && pay > 0 && weekStartProjected && weekStartTaken == period &&
            (week.spent ?: 0.0) >= 0.9 * pay
        ) {
            AlertDialog.Builder(this)
                .setTitle("Has today's ${money.format(pay)} pay landed?")
                .setMessage("Your balance looks like it's from before payday.")
                .setPositiveButton("Yes") { _, _ -> askBillsOut() }
                .setNegativeButton("Not yet") { _, _ ->
                    pendingPay = today
                    askBillsOut()
                }
                .setOnCancelListener { askBillsOut() }
                .show()
        } else {
            askBillsOut()
        }
    }

    private fun sinceText(week: Week) =
        if (week.trackedFrom == null || week.trackedFrom == week.payday) "since payday" else "since ${week.trackedFrom.format(weekday)}"

    // "Your weekly pay": amount and payday together.
    private fun editPay() {
        val content = layoutInflater.inflate(R.layout.dialog_pay, null)
        val amountBox = content.findViewById<EditText>(R.id.pay_amount)
        val daySpinner = content.findViewById<Spinner>(R.id.pay_day)
        val days = DayOfWeek.values()
        daySpinner.adapter = spinnerAdapter(days.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) })
        daySpinner.setSelection(payday.ordinal)
        if (weeklyIncome > 0) amountBox.setText(plain(weeklyIncome))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Your weekly pay")
            .setMessage("What lands in your bank each week, after tax.")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                parseMoney(amountBox.text.toString())?.let { savePay(it, days[daySpinner.selectedItemPosition]) }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun valid() = parseMoney(amountBox.text.toString())?.let { it > 0 } == true
        saveButton.isEnabled = valid()
        onTextChange(amountBox) { saveButton.isEnabled = valid() }
        amountBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && valid()) {
                saveButton.performClick()
                true
            } else {
                false
            }
        }
        amountBox.requestFocus()
    }

    private fun savePay(amount: Double, day: DayOfWeek) {
        val oldIncome = weeklyIncome
        val oldDay = payday
        val oldStart = weekStart
        val oldChanges = paycheckChanges.toMap()
        val dayChanged = day != payday
        weeklyIncome = amount
        paycheckChanges.entries.removeAll { it.value == amount } // a "change" to the usual amount isn't one
        if (dayChanged) {
            payday = day
            paycheckChanges.keys.removeAll { it.dayOfWeek != day } // one-off changes for the old weekday
            // Keep counting this week's spending if the starting balance still falls in the new pay week.
            val period = lastPayday(LocalDate.now())
            if (weekStartTaken?.isBefore(period) == false) weekStart = period
        }
        save()
        refresh()
        if (dayChanged && !bank.isLinked && balanceUpdated != null && balanceUpdated != LocalDate.now()) {
            // The old balance was moved forward using the old payday; start fresh from today's.
            editBalance("Your payday changed. What's in your account right now? This keeps the numbers right from here.")
            return
        }
        showSnack("Pay saved: ${shortMoney(amount)} every ${payday.getDisplayName(TextStyle.FULL, Locale.getDefault())}") {
            weeklyIncome = oldIncome
            payday = oldDay
            weekStart = oldStart
            paycheckChanges.clear()
            paycheckChanges.putAll(oldChanges)
            save()
            refresh()
        }
    }

    private fun editPaycheck(date: LocalDate) {
        if (weeklyIncome <= 0) return editPay()
        val landed = !date.isAfter(LocalDate.now())
        askAmount(
            "Paycheck on ${date.format(dayFormat)}",
            "Usually ${money.format(weeklyIncome)}. Enter what this paycheck " + (if (landed) "was." else "will be."),
            paycheckOn(date),
            showZero = true,
            neutral = if (date in paycheckChanges) "Use usual" to { setPaycheck(date, weeklyIncome) } else null,
        ) { setPaycheck(date, it) }
    }

    private fun setPaycheck(date: LocalDate, amount: Double) {
        val old = paycheckOn(date)
        if (amount == weeklyIncome) paycheckChanges.remove(date) else paycheckChanges[date] = amount
        // A starting balance carried forward over this payday already included the old amount.
        if (weekStartProjected && weekStartTaken == date) weekStartBalance = cents(weekStartBalance + amount - old)
        save()
        refresh()
        showSnack("${date.format(shortDate)} paycheck: ${money.format(amount)}")
    }

    private fun showSettings() {
        val dayName = payday.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val items = arrayOf(
            if (weeklyIncome > 0) "Your pay: ${money.format(weeklyIncome)} every $dayName" else "Your pay: not set",
            if (bank.isLinked) "Bank: ${bankName()}" else "Connect your bank (Plaid)",
            "Check for updates ($updateStatusText)",
        )
        AlertDialog.Builder(this)
            .setTitle("Settings · version ${BuildConfig.VERSION_NAME}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editPay()
                    1 -> if (bank.isLinked) bankSettings() else connectBank()
                    else -> checkForUpdate(fromUser = true)
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    // Small dialog with one "$" field and the keyboard already up. Save only works with a valid amount,
    // and the keyboard's Done key saves too.
    private fun askAmount(
        title: String,
        message: String,
        current: Double?,
        signed: Boolean = false,
        showZero: Boolean = false,
        neutral: Pair<String, () -> Unit>? = null,
        onSave: (Double) -> Unit,
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_amount, null)
        val input = content.findViewById<EditText>(R.id.dialog_amount_input).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            current?.takeIf { showZero || it != 0.0 }?.let { setText(amountText(it)) }
            setSelectAllOnFocus(true)
        }
        fun value() = parseMoney(input.text.toString())?.takeIf { signed || it >= 0 }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(content)
            .setPositiveButton("Save") { _, _ -> value()?.let(onSave) }
            .setNegativeButton("Cancel", null)
        neutral?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        saveButton.isEnabled = value() != null
        onTextChange(input) { saveButton.isEnabled = value() != null }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && value() != null) {
                saveButton.performClick()
                true
            } else {
                false
            }
        }
        input.requestFocus()
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, R.layout.spinner_item, items).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }

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
        window.setTitle(if (existing == null) "New bill" else "Edit bill")
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
        val shareText = dialog.findViewById<TextView>(R.id.sheet_share)
        val saveButton = dialog.findViewById<Button>(R.id.sheet_save)
        val deleteButton = dialog.findViewById<View>(R.id.sheet_delete)
        val paidButton = dialog.findViewById<TextView>(R.id.sheet_paid)
        val unmarkLink = dialog.findViewById<TextView>(R.id.sheet_unmark)

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

        // With large text, put Repeats and Next due on separate lines so neither gets cut off.
        if (resources.configuration.fontScale >= 1.3f) {
            dialog.findViewById<LinearLayout>(R.id.sheet_row_when).apply {
                orientation = LinearLayout.VERTICAL
                for (i in 0 until childCount) {
                    getChildAt(i).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
        }

        dialog.findViewById<TextView>(R.id.sheet_title).text = if (existing == null) "New bill" else "Edit bill"
        saveButton.text = if (existing == null) "Add bill" else "Save"
        deleteButton.visibility = if (existing == null) View.GONE else View.VISIBLE
        freqSpinner.adapter = spinnerAdapter(Freq.values().map { it.label })
        val shown = existing?.nextDue(today) // the "Next due" date shown when editing
        var pickedDate: LocalDate? = null
        if (existing != null && shown != null) {
            nameBox.setText(existing.name)
            amountBox.setText(amountText(existing.amount))
            freqSpinner.setSelection(existing.freq.ordinal)
            dateField.text = shown.format(dayFormat)
            paidButton.visibility = View.VISIBLE
            // The latest "paid" from the past week or ahead can be undone here.
            existing.paid.keys.filter { !it.isBefore(today.minusDays(7)) }.maxByOrNull { it.toEpochDay() }?.let { mark ->
                unmarkLink.visibility = View.VISIBLE
                unmarkLink.text = "✓ ${mark.format(shortDate)} is marked paid · tap to undo"
                unmarkLink.setOnClickListener { closeSheet = {}; dialog.dismiss(); unmarkPaid(existing, mark) }
            }
        }

        // Keeps the original schedule unless the date or the frequency changed, so a bill on the 31st stays on
        // the 31st and switching to yearly doesn't jump back to an old start date.
        fun buildBill(): Bill? {
            val name = nameBox.text.toString().trim()
            val amount = parseMoney(amountBox.text.toString())?.takeIf { it >= 0 } ?: return null
            if (name.isEmpty()) return null
            val freq = Freq.values()[freqSpinner.selectedItemPosition]
            val date = pickedDate?.takeIf { it != shown } ?: existing?.let { if (freq == it.freq) it.date else shown } ?: return null
            return Bill(name, amount, freq, date, existing?.paid ?: emptyMap())
        }
        fun updateLabels() {
            val freq = Freq.values()[freqSpinner.selectedItemPosition]
            val amount = parseMoney(amountBox.text.toString())?.takeIf { it > 0 }
            shareText.text = amount?.let { "≈ ${money.format(it * 7 / freq.cycleDays)} from each weekly paycheck" } ?: ""
            if (existing != null) {
                paidButton.text = "Mark ${(buildBill() ?: existing).nextDue(today).format(shortDate)} as paid"
            }
        }
        freqSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateLabels()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        onTextChange(amountBox) { updateLabels() }
        updateLabels()

        fun pickDate() {
            val start = pickedDate ?: shown ?: today
            DatePickerDialog(this, { _, year, month, day ->
                pickedDate = LocalDate.of(year, month + 1, day)
                dateField.text = pickedDate!!.format(dayFormat)
                dateField.error = null
                updateLabels()
            }, start.year, start.monthValue - 1, start.dayOfMonth).show()
        }
        dateField.setOnClickListener { pickDate() }
        if (existing == null) {
            // Next on the keyboard goes on to the date.
            amountBox.imeOptions = EditorInfo.IME_ACTION_NEXT
            amountBox.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT && pickedDate == null) {
                    hideKeyboardIn(dialog)
                    pickDate()
                    true
                } else {
                    false
                }
            }
        }

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

        // Saves the bill (with Undo) and closes the sheet.
        fun commit(bill: Bill, quiet: Boolean = false, then: (Bill) -> Unit = {}) {
            val before = bills.toList()
            if (existing == null) {
                bills.add(bill)
            } else {
                val index = bills.indexOf(existing)
                if (index >= 0) bills[index] = bill // gone already (deleted meanwhile): don't bring it back
            }
            save()
            haptic(saveButton)
            close {
                refresh()
                if (!quiet) {
                    showSnack(if (existing == null) "${bill.name} added" else "${bill.name} saved") {
                        bills.clear()
                        bills.addAll(before)
                        save()
                        refresh()
                    }
                }
                then(bill)
            }
        }

        saveButton.setOnClickListener {
            if (closing) return@setOnClickListener // a second tap while the sheet is closing
            val bill = buildBill()
            if (bill == null) {
                when {
                    nameBox.text.toString().isBlank() -> nameBox.apply {
                        error = "Enter a name"
                        requestFocus()
                    }
                    parseMoney(amountBox.text.toString())?.takeIf { it >= 0 } == null -> amountBox.apply {
                        error = "Enter an amount"
                        requestFocus()
                    }
                    else -> {
                        dateField.error = "Pick a date"
                        pickDate()
                    }
                }
                return@setOnClickListener
            }
            // Moving a bill that's due now to a later date: did they pay this one (so it isn't counted as spending)?
            val movedFrom = shown?.takeIf {
                existing != null && pickedDate?.isAfter(it) == true && ChronoUnit.DAYS.between(today, it) <= 3 && it !in existing.paid
            }
            if (movedFrom != null) {
                AlertDialog.Builder(this)
                    .setTitle("Did you pay the ${movedFrom.format(shortDate)} ${bill.name}?")
                    .setMessage("Moving the date skips that one. If you paid it, it's marked paid so the money isn't counted as spending.")
                    .setPositiveButton("Yes, it's paid") { _, _ -> commit(bill.withPaid(movedFrom, today)) }
                    .setNegativeButton("No, skip it") { _, _ -> commit(bill) }
                    .show()
            } else {
                commit(bill)
            }
        }
        deleteButton.setOnClickListener {
            if (!closing) close { if (existing != null) deleteBill(existing) }
        }
        // Saves any edits first, then marks the next one paid.
        paidButton.setOnClickListener {
            if (closing || existing == null) return@setOnClickListener
            commit(buildBill() ?: existing, quiet = true) { markPaid(it) }
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

    // Marks the bill's next due date paid as of today (paid early, or autopay already took it). If a balance
    // was entered today, asks whether that balance already reflects it.
    private fun markPaid(bill: Bill) {
        if (bill !in bills) return
        val today = LocalDate.now()
        val due = bill.nextDue(today)
        fun apply(takeOff: Boolean) {
            val index = bills.indexOf(bill)
            if (index < 0) return
            val before = balanceState()
            val billsBefore = bills.toList()
            val ahead = bill.paid.keys.any { !it.isBefore(today) }
            bills[index] = bill.withPaid(due, today)
            if (takeOff) {
                balance = cents(balance - bill.amount)
                if (weekStartTaken == today && !weekStartProjected) weekStartBalance = cents(weekStartBalance - bill.amount)
            }
            save()
            refresh()
            haptic(billList)
            showSnack("${bill.name} · ${due.format(shortDate)} ${if (ahead) "also " else ""}marked paid" +
                (if (takeOff) " · balance ${money.format(balance)}" else "")) {
                restore(before)
                bills.clear()
                bills.addAll(billsBefore)
                save()
                refresh()
            }
        }
        if (balanceUpdated == today && !bank.isLinked) {
            AlertDialog.Builder(this)
                .setTitle("Is it already out of your balance?")
                .setMessage("You entered ${money.format(balance)} today. Has the ${money.format(bill.amount)} for ${bill.name} already come out of that?")
                .setPositiveButton("Yes, it's out") { _, _ -> apply(takeOff = false) }
                .setNegativeButton("No, take it off") { _, _ -> apply(takeOff = true) }
                .setOnCancelListener { refresh() }
                .show()
        } else {
            apply(takeOff = false)
        }
    }

    private fun unmarkPaid(bill: Bill, due: LocalDate) {
        val index = bills.indexOf(bill)
        if (index < 0) return
        // If the bank's transaction was taken as this payment, it isn't: count it as spending instead.
        val txnId = if (bank.isLinked) billMatches().entries.firstOrNull { it.value == (index to due) }?.key else null
        txnId?.let { bank.setOverride(it, "spend") }
        val updated = bill.withoutPaid(due)
        bills[index] = updated
        save()
        refresh()
        showSnack("${bill.name} · ${due.format(shortDate)} no longer marked paid") {
            val i = bills.indexOf(updated)
            if (i >= 0) {
                txnId?.let { bank.setOverride(it, null) }
                bills[i] = bill
                save()
                refresh()
            }
        }
    }

    private fun deleteBill(bill: Bill) {
        val index = bills.indexOf(bill)
        if (index < 0) return
        bills.removeAt(index)
        save()
        refresh()
        haptic(billList)
        snackUndo = null
        pendingDeletes += index to bill
        showSnackBar(deletedText(), canUndo = true)
        if (a11y.isTouchExplorationEnabled) {
            undoButton.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun deletedText() =
        if (pendingDeletes.size == 1) "Deleted ${pendingDeletes[0].second.name}" else "Deleted ${pendingDeletes.size} bills"

    // ---------- Message bar with Undo ----------

    // A message after a change, with Undo when `undo` is given. A new message ends the chance to undo
    // the previous one.
    private fun showSnack(message: String, undo: (() -> Unit)? = null) {
        pendingDeletes.clear()
        snackUndo = undo
        showSnackBar(message, canUndo = undo != null)
    }

    private fun showSnackBar(message: String, canUndo: Boolean) {
        undoText.text = message
        undoButton.visibility = if (canUndo) View.VISIBLE else View.GONE
        positionSnackBar()
        handler.removeCallbacks(hideSnack)
        undoBar.animate().cancel()
        if (undoBar.visibility != View.VISIBLE) {
            undoBar.alpha = 0f
            undoBar.translationY = dp(16).toFloat()
            undoBar.visibility = View.VISIBLE
        }
        undoBar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        // Longer when a screen reader is on (or the person asked Android for more time to react).
        var timeout = 5000
        if (Build.VERSION.SDK_INT >= 29) {
            timeout = a11y.getRecommendedTimeoutMillis(
                timeout,
                AccessibilityManager.FLAG_CONTENT_TEXT or (if (canUndo) AccessibilityManager.FLAG_CONTENT_CONTROLS else 0),
            )
        }
        if (a11y.isTouchExplorationEnabled) timeout = maxOf(timeout, 20_000)
        handler.postDelayed(hideSnack, timeout.toLong())
    }

    // Leaves room for the + button when it's showing.
    private fun positionSnackBar() {
        val params = undoBar.layoutParams as FrameLayout.LayoutParams
        val end = dp(if (fabShown) 84 else 16)
        if (params.marginEnd != end) {
            params.marginEnd = end
            undoBar.layoutParams = params
        }
    }

    private fun undo() {
        if (pendingDeletes.isNotEmpty()) {
            for ((index, bill) in pendingDeletes.reversed()) bills.add(minOf(index, bills.size), bill)
            save()
            refresh()
        } else {
            snackUndo?.invoke()
        }
        handler.removeCallbacks(hideSnack)
        hideSnack.run()
    }

    // Swipe a bill row right to mark it paid, left to delete it; a tap opens it for editing.
    // Screen readers get "Mark paid" and "Delete" actions instead.
    @SuppressLint("ClickableViewAccessibility")
    private fun makeSwipeable(row: View, content: View, onTap: () -> Unit, onDelete: () -> Unit, onPaid: () -> Unit) {
        val paidLayer = row.findViewById<View>(R.id.bill_row_paid_layer)
        val deleteLayer = row.findViewById<View>(R.id.bill_row_delete_layer)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var swiping = false
        var moved = false
        var busy = false
        content.setOnClickListener { if (!busy) onTap() }
        content.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Edit"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_mark_paid, "Mark paid"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_delete_bill, "Delete"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                R.id.action_mark_paid -> true.also { onPaid() }
                R.id.action_delete_bill -> true.also { onDelete() }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
        fun settle() {
            content.animate().translationX(0f).setDuration(160).withEndAction {
                paidLayer.visibility = View.INVISIBLE
                deleteLayer.visibility = View.INVISIBLE
            }.start()
        }
        content.setOnTouchListener { view, event ->
            if (busy) return@setOnTouchListener true
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
                        if (abs(dx) > abs(dy)) {
                            swiping = true
                            view.parent.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    if (swiping) {
                        view.translationX = dx
                        paidLayer.visibility = if (dx > 0) View.VISIBLE else View.INVISIBLE
                        deleteLayer.visibility = if (dx < 0) View.VISIBLE else View.INVISIBLE
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    val dx = view.translationX
                    when {
                        swiping && -dx > view.width * 0.35f -> {
                            busy = true
                            view.animate().translationX(-view.width.toFloat()).setDuration(160)
                                .withEndAction { onDelete() }.start()
                        }
                        swiping && dx > view.width * 0.35f -> {
                            busy = true
                            view.animate().translationX(view.width.toFloat()).setDuration(160)
                                .withEndAction { onPaid() }.start()
                        }
                        swiping -> settle()
                        !moved -> view.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    settle()
                }
            }
            true
        }
    }

    // ---------- Formatting ----------

    private fun today() = LocalDate.now()

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

    private fun rippleBackground(): Drawable? {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return getDrawable(value.resourceId)
    }

    // Screen readers say "double-tap to <label>" for this row.
    private fun clickLabel(label: String) = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, label))
        }
    }

    private fun onTextChange(edit: EditText, block: () -> Unit) = edit.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = block()
    })

    private fun haptic(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun plain(amount: Double) = amount.toBigDecimal().stripTrailingZeros().toPlainString()

    // What goes in an amount field: "1200" or "80.50".
    private fun amountText(amount: Double) =
        if (amount == Math.rint(amount)) plain(amount) else amount.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toPlainString()

    // Accepts "1,234.56", "$50", "-20". Anything empty, unreadable or absurdly large counts as no answer.
    private fun parseMoney(text: String): Double? =
        text.replace(Regex("[^0-9.-]"), "").toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) < 1e9 }

    // ---------- Saving ----------

    private fun billToJson(bill: Bill) = JSONObject()
        .put("name", bill.name).put("amount", bill.amount)
        .put("freq", bill.freq.name).put("date", bill.date.toString())
        .put("paid", JSONObject().apply { for ((due, on) in bill.paid) put(due.toString(), on.toString()) })

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
        // Paid marks older than two months don't matter any more.
        val cutoff = LocalDate.now().minusDays(60)
        val paid = mutableMapOf<LocalDate, LocalDate>()
        json.optJSONObject("paid")?.let { marks ->
            for (key in marks.keys()) {
                val due = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                val on = runCatching { LocalDate.parse(marks.getString(key)) }.getOrNull() ?: continue
                if (!due.isBefore(cutoff) || !on.isBefore(cutoff)) paid[due] = on
            }
        }
        if (!amount.isFinite()) null else Bill(json.getString("name"), amount, freq, date, paid)
    }.getOrNull()

    private fun load() {
        fun date(key: String) = prefs.getString(key, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        fun number(key: String) = prefs.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        balance = number("balance") ?: 0.0
        balanceUpdated = date("balance_updated")
        pendingPay = date("pending_pay")
        earlyPay = date("early_pay")
        weeklyIncome = number("weekly_income") ?: 0.0
        payday = runCatching { DayOfWeek.of(prefs.getInt("payday", DayOfWeek.FRIDAY.value)) }.getOrDefault(DayOfWeek.FRIDAY)
        billsNone = prefs.getBoolean("bills_none", false)
        runCatching {
            val saved = JSONArray(prefs.getString("bills", null) ?: "[]")
            for (i in 0 until saved.length()) saved.optJSONObject(i)?.let { json -> billFromJson(json)?.let { bills.add(it) } }
        }

        // Keep paycheck changes from this pay week on, and any the last balance still depends on.
        val periodStart = lastPayday(LocalDate.now())
        val keepFrom = balanceUpdated?.takeIf { it.isBefore(periodStart) } ?: periodStart
        runCatching {
            val changes = JSONObject(prefs.getString("paycheck_changes", null) ?: "{}")
            for (key in changes.keys()) {
                val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                val amount = changes.optDouble(key)
                if (!day.isBefore(keepFrom) && amount.isFinite()) paycheckChanges[day] = amount
            }
        }

        weekStart = date("week_start")
        weekStartBalance = number("week_start_balance") ?: 0.0
        weekStartTaken = date("week_start_taken")
        weekStartProjected = prefs.getBoolean("week_start_projected", false)
    }

    private fun save() {
        val saved = JSONArray()
        for (bill in bills) saved.put(billToJson(bill))
        val changes = JSONObject()
        for ((date, amount) in paycheckChanges) changes.put(date.toString(), amount)
        prefs.edit()
            .putString("balance", balance.toString())
            .putString("balance_updated", balanceUpdated?.toString())
            .putString("pending_pay", pendingPay?.toString())
            .putString("early_pay", earlyPay?.toString())
            .putString("weekly_income", weeklyIncome.toString())
            .putInt("payday", payday.value)
            .putBoolean("bills_none", billsNone)
            .putString("bills", saved.toString())
            .putString("paycheck_changes", changes.toString())
            .putString("week_start", weekStart?.toString())
            .putString("week_start_balance", weekStartBalance.toString())
            .putString("week_start_taken", weekStartTaken?.toString())
            .putBoolean("week_start_projected", weekStartProjected)
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
    private fun checkForUpdate(fromUser: Boolean = false) {
        val current = BuildConfig.VERSION_NAME
        updateStatusText = "checking…"
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
                    result.isFailure -> updateStatusText = "couldn't check"
                    update == null -> updateStatusText = "up to date"
                    else -> {
                        latestVersion = update.first
                        updateUrl = update.second
                        updateStatusText = "version ${update.first} is ready"
                        if (!downloading) updateText.text = "Version ${update.first} is ready"
                        updateBanner.visibility = View.VISIBLE
                    }
                }
                if (fromUser) {
                    val message = when {
                        result.isFailure -> "Couldn't check for updates. Check your connection."
                        update == null -> "You're up to date (version $current)."
                        else -> "Version ${update.first} is ready. Tap Update on Summary."
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
                updateText.text = if (result.isFailure) "Update failed. Check your connection and try again." else "Installing…"
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
