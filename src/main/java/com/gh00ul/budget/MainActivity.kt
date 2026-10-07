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
import android.content.ActivityNotFoundException
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
import android.os.StrictMode
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
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
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
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
    // Where a bill (any copy of it) is in `bills` now, or -1 if it was deleted.
    private fun indexOfBill(bill: Bill) = bills.indexOfFirst { it.id === bill.id }

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
    private var earlyPay: LocalDate? = null // an upcoming payday whose paycheck is already in the synced balance
    // "Connect your bank" while it waits for the server: the dialog, which attempt it started, and whether what's
    // typed in it is still valid.
    private var connectDialog: AlertDialog? = null
    private var connectDialogAttempt = 0
    private var connectFieldsValid: () -> Boolean = { false }

    private val handler = Handler(Looper.getMainLooper())
    private val pendingDeletes = mutableListOf<Pair<Int, Bill>>() // (index it was at, bill)
    private var snackUndo: (() -> Unit)? = null
    private var snackHideAt = 0L // when the message bar goes away (wall clock), so a restored Undo can expire
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
        enableStrictModeInDebug()
        super.onCreate(savedInstanceState)
        Running.screen = WeakReference(this)
        setContentView(R.layout.activity_main)

        fitToSystemBars()
        setUpTabs()
        // The update check runs before anything reads saved data, so a data problem can never block an
        // update that fixes it.
        clearStaleInstallSessions()
        checkForUpdate()
        load()

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

        // Coming back from a dark-mode / font-size change: same tab, an Undo that was showing, and a balance
        // question that was open.
        if (savedInstanceState != null) {
            showTab(savedInstanceState.getInt("tab", 0), animate = false)
            restoreUndo(savedInstanceState)
            restoreFollowUp(savedInstanceState)
        } else if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            // (Reopened from Recents, the shortcut that first opened the app isn't being used again.)
            handleShortcut(intent)
        }
    }

    // The launcher icon while the app is open (launchMode singleTop), or a shortcut started by another app.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    // Whether Budget is on screen at all: Android 15+ cuts an app's network soon after it isn't (see syncBank). A
    // rotation stops the old screen before the new one starts; isChangingConfigurations keeps that from counting.
    override fun onStart() {
        super.onStart()
        Running.visibleScreens++
    }

    override fun onStop() {
        Running.visibleScreens = maxOf(0, Running.visibleScreens - 1)
        if (Running.visibleScreens == 0 && !isChangingConfigurations) Running.leftAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    // Redraw on every return to the app so "due in X days" and the paydays stay current.
    override fun onResume() {
        super.onResume()
        Running.screen = WeakReference(this)
        Running.resumed = WeakReference(this)
        refresh()
        syncBank()
        scheduleMidnightRefresh()
        // An update check that failed (no signal when the app opened, say) is tried again after a few minutes.
        if (Running.updateFailedAt > 0 && SystemClock.elapsedRealtime() - Running.updateFailedAt > 5 * 60_000L) {
            checkForUpdate()
        }
        // Back from the install prompt without installing (or it failed): let them try again.
        showUpdateState()
        Running.installFailure?.let { message ->
            Running.installFailure = null
            tell(message)
        }
        // Android's "Update this app?" screen, if the download finished while Budget wasn't on screen.
        Running.installPrompt?.let { prompt ->
            Running.installPrompt = null
            Running.showInstallPrompt(this, prompt)
        }
    }

    override fun onPause() {
        if (Running.resumed?.get() === this) Running.resumed = null
        rowHeld = false // a touch never ends while the app is in the background; onResume redraws anyway
        refreshHeld = false
        handler.removeCallbacks(midnightRefresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
        if (pendingDeletes.isNotEmpty()) {
            val undo = JSONArray()
            for ((index, bill) in pendingDeletes) undo.put(JSONObject().put("index", index).put("bill", billToJson(bill)))
            outState.putString("undo", undo.toString())
            outState.putLong("undo_until", snackHideAt)
        }
        followUp?.let { f ->
            outState.putLong("follow_up_day", LocalDate.now().toEpochDay())
            outState.putDouble("follow_up_value", f.value)
            f.logged?.let { outState.putDouble("follow_up_logged", it) }
            outState.putDouble("follow_up_drop", f.drop)
            outState.putDouble("follow_up_old", f.oldBalance)
        }
    }

    // Deleted bills whose Undo was still showing when the screen was replaced, as long as its time hasn't run out.
    private fun restoreUndo(state: Bundle) {
        val saved = state.getString("undo") ?: return
        if (System.currentTimeMillis() >= state.getLong("undo_until")) return
        try {
            val list = JSONArray(saved)
            for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                billFromJson(entry.getJSONObject("bill"))?.let { pendingDeletes += entry.getInt("index") to it }
            }
        } catch (e: JSONException) {
            Log.w("Budget", "Couldn't restore Undo: ${e.javaClass.name}") // only the Undo is lost; the delete was saved
            pendingDeletes.clear()
        }
        if (pendingDeletes.isNotEmpty()) showSnackBar(deletedText(), canUndo = true)
    }

    // A balance question that was open when the screen was replaced is asked again (without Undo), as long as it's
    // still the same day: its "earlier today" and "due today" wouldn't mean the same thing tomorrow.
    private fun restoreFollowUp(state: Bundle) {
        if (!state.containsKey("follow_up_value")) return
        if (state.getLong("follow_up_day") != LocalDate.now().toEpochDay()) return
        val f = BalanceFollowUp(
            value = state.getDouble("follow_up_value"),
            logged = if (state.containsKey("follow_up_logged")) state.getDouble("follow_up_logged") else null,
            drop = state.getDouble("follow_up_drop"),
            oldBalance = state.getDouble("follow_up_old"),
        )
        hero.post { askBalanceFollowUps(f, undo = null) }
    }

    // Redraws at midnight while the app stays open, so the date, "due in" days and the pay week move on.
    private val midnightRefresh: Runnable = Runnable {
        refresh()
        scheduleMidnightRefresh()
    }

    private fun scheduleMidnightRefresh() {
        handler.removeCallbacks(midnightRefresh)
        val now = ZonedDateTime.now()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        handler.postDelayed(midnightRefresh, Duration.between(now, midnight).toMillis() + 1_000)
    }

    override fun onDestroy() {
        if (Running.screen?.get() === this) Running.screen = null
        handler.removeCallbacksAndMessages(null)
        heroAnimator?.cancel()
        // dismiss(), never cancel(): the balance questions go on to the next one from their cancel listeners,
        // which would open a dialog on a screen that's going away. An open question is asked again after a
        // rotation instead (see BalanceFollowUp).
        for (dialog in openDialogs) if (dialog.isShowing) dialog.dismiss()
        openDialogs.clear()
        sheetDialog?.dismiss()
        super.onDestroy()
    }

    // A message that asks the user to do something. A dialog stays until it's read; a toast is cut to two lines
    // (Android 12+) and gone in a few seconds. Short confirmations stay toasts.
    internal fun tell(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).present()
    }

    // Every dialog is shown through here, so the open ones close with the screen (instead of leaking their
    // window on rotation) and nothing is shown on a screen that's finishing or already replaced, e.g. by a late
    // network result or a posted shortcut. Returns false when it didn't show, so callers that set up buttons after
    // show() can stop.
    private val openDialogs = mutableListOf<Dialog>()

    private fun present(dialog: Dialog): Boolean {
        if (isFinishing || isDestroyed) return false
        openDialogs.removeAll { !it.isShowing }
        openDialogs += dialog
        dialog.show()
        return true
    }

    private fun AlertDialog.Builder.present() {
        present(create())
    }

    // Back from another tab goes to Summary before leaving the app.
    // Deprecated since API 33 in favor of OnBackInvokedCallback, but the app doesn't opt in to predictive back
    // (no android:enableOnBackInvokedCallback) and targets 35, so Android still calls this on every version.
    // It has to move to OnBackInvokedCallback before targetSdk 36 (DEBUG_REPORT.md F-25).
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
            // obtain() is deprecated from API 33, but its replacement constructor only exists from 33 (minSdk 26).
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
                // Same as above: the non-deprecated constructor is API 33+.
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
    // setDecorFitsSystemWindows and navigationBarColor are deprecated in API 35 because 35+ is always
    // edge-to-edge (where they do nothing); Android 11-14 still need them to get the same layout.
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

    // The deposit that is that payday's paycheck: income into checking, at least half the expected pay, from
    // two days before (paid early) to a day after.
    private fun payDeposit(payday: LocalDate, upTo: LocalDate = LocalDate.now()): BankTxn? {
        val expected = paycheckOn(payday)
        if (expected <= 0) return null
        val last = minOf(payday.plusDays(1).toEpochDay(), upTo.toEpochDay())
        return bank.txns.firstOrNull {
            it.account == bank.accountId && it.amount >= expected * 0.5 &&
                (isPayroll(it) || it.category.startsWith("Income", true)) &&
                it.date.toEpochDay() in payday.minusDays(2).toEpochDay()..last
        }
    }

    // Which transactions paid which bill due dates (see matchBills in BudgetLogic.kt).
    private fun billMatches(): Map<String, Pair<Int, LocalDate>> = matchBills(bills, bank.txns, bank.overrides, spendAccounts())

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
    // Payments older than the paid marks kept on bills (PAID_MARK_DAYS) are left alone: their marks were trimmed on
    // purpose, and marking them again would announce the same old bills after every reload.
    private fun markBillsFromBank(): List<String> {
        val txnDates = bank.txns.associate { it.id to it.date }
        val cutoff = LocalDate.now().minusDays(PAID_MARK_DAYS)
        val names = mutableListOf<String>()
        for ((txnId, match) in billMatches()) {
            val (i, due) = match
            val bill = bills.getOrNull(i) ?: continue
            if (due in bill.paid) continue
            val paidOn = txnDates[txnId] ?: continue
            if (due.isBefore(cutoff) && paidOn.isBefore(cutoff)) continue
            bills[i] = bill.withPaid(due, paidOn)
            names += bill.name
        }
        return names.distinct()
    }

    // On opening the app (at most every half hour, or 5 minutes after a sync that failed), or when asked: get the
    // latest from the bank.
    private fun syncBank(force: Boolean = false) {
        if (!bank.isLinked) return
        val url = bank.url ?: return
        val connection = bank.connection ?: return
        if (Running.visibleScreens == 0) {
            // Budget isn't on screen (a sync for a new connection, say): Android would cut it off; go on return.
            Running.syncAgain = true
            Running.syncAgainAnnounce = Running.syncAgainAnnounce || force
            return
        }
        if (Running.syncing != null) {
            // Already syncing, maybe for an earlier screen: say how it went once it's done.
            if (force) {
                Running.syncAnnounce = true
                balanceUpdatedView.text = "Syncing…"
            }
            return
        }
        val now = System.currentTimeMillis()
        val due = Running.syncAgain || now - bank.checkedAt >= 30 * 60_000L && now - bank.failedAt >= 5 * 60_000L
        if (!force && !due) return
        val announce = force || Running.syncAgainAnnounce
        Running.syncAgain = false
        Running.syncAgainAnnounce = false
        Running.syncing = connection
        Running.syncAnnounce = announce
        if (announce) balanceUpdatedView.text = "Syncing…"
        val startedAt = SystemClock.elapsedRealtime()
        val store = bank // holds only the preferences, not this screen
        thread(name = "budget-bank-sync") {
            val result = runCatching { BankServer.sync(url, store.key()) }
            Running.main.post {
                Running.syncing = null
                val announced = Running.syncAnnounce
                Running.syncAnnounce = false
                if ((result.exceptionOrNull() as? BankException)?.network == true && Running.leftSince(startedAt)) {
                    // Cut off because Budget left the screen: try again on return, keeping the last good data.
                    Log.i("Budget", "Bank sync stopped when the app was left; will retry")
                    Running.syncAgain = true
                    Running.syncAgainAnnounce = announced
                    Running.resumedScreen()?.syncBank()
                    return@post
                }
                Running.liveScreen()?.bankSynced(connection, result, announced)
            }
        }
    }

    // A sync finished. If the bank was disconnected or connected again meanwhile, the result belongs to the old
    // connection and is dropped; a new connection then gets its own sync.
    private fun bankSynced(connection: String, result: Result<BankSnapshot>, announce: Boolean) {
        if (bank.connection != connection) {
            if (bank.isLinked) syncBank(force = announce)
            return
        }
        result.onSuccess { applyBank(it, announce) }.onFailure { e ->
            val message = bankErrorText(e, "Bank sync failed. Try again later.")
            bank.saveError(message)
            refresh()
            if (announce) tell(message)
        }
    }

    // What to tell the user about a failed bank request. BankSync's own messages are written for people; anything
    // else is unexpected, so it gets `fallback` (its message could hold the address or the key), and only the
    // error's type goes to the log.
    private fun bankErrorText(e: Throwable, fallback: String): String {
        if (e is BankException) return e.message ?: fallback
        Log.w("Budget", "Bank request failed: ${e.javaClass.name}")
        return fallback
    }

    // The checking balance becomes the bank balance, paychecks are checked off, and paid bills are marked.
    // snapshot = null re-reads what the last sync saved (after picking a different account).
    private fun applyBank(snapshot: BankSnapshot?, announce: Boolean) {
        snapshot?.let { bank.saveSnapshot(it) }
        val account = bank.account
        val value = account?.let { it.available ?: it.current }
        if (account == null || value == null) {
            val message = if (account == null) {
                "Your checking account isn't on the bank server any more. In Settings, tap your bank, then Use a different account."
            } else {
                "Your bank didn't send a balance for this account. Try Sync now later, or in Settings tap your bank and pick another account."
            }
            bank.saveError(message)
            refresh()
            if (announce) tell(message)
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
            // A sync nobody asked for doesn't take away a delete's Undo, which only puts the deleted bill back. (Other
            // Undos restore everything as it was, which would also undo this sync, so the message replaces them.)
            !announce && pendingDeletes.isNotEmpty() -> Unit
            marked.isNotEmpty() && announce && bank.error != null ->
                showSnack("${marked.joinToString()} marked paid · sync problem, tap your bank balance")
            marked.isNotEmpty() -> showSnack("${marked.joinToString()} marked paid from your bank")
            announce && bank.error != null -> showSnack("Sync problem · tap your bank balance for details")
            announce -> showSnack("Synced with ${account.institution ?: "your bank"}")
        }
    }

    private fun bankName() = bank.account?.let { "${it.institution ?: "Bank"} ${it.label}" } ?: "Your bank"

    private fun ago(epochMs: Long): String {
        if (epochMs <= 0) return "not yet"
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
            // Cancel (or Back) stops a connect that's still checking; the dialog closing with a rotated screen
            // doesn't, and the account choice then comes up on the new screen.
            .setNegativeButton("Cancel") { _, _ -> Running.connecting = 0 }
            .setOnCancelListener { Running.connecting = 0 }
            .create()
        if (!present(dialog)) return
        val connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun valid() = BankServer.cleanUrl(urlBox.text.toString()) != null && BankServer.isUsableKey(keyBox.text.toString().trim())
        connect.isEnabled = valid()
        // Why Connect is greyed out, once something's been typed.
        onTextChange(urlBox) {
            connect.isEnabled = valid()
            urlBox.error = if (urlBox.text.isNotBlank() && BankServer.cleanUrl(urlBox.text.toString()) == null) {
                "Use the https:// address of your bank server"
            } else {
                null
            }
        }
        onTextChange(keyBox) {
            connect.isEnabled = valid()
            val key = keyBox.text.toString().trim()
            keyBox.error = if (key.isNotEmpty() && !BankServer.isUsableKey(key)) "That's not the whole access key. Copy it again." else null
        }
        connect.setOnClickListener {
            val url = BankServer.cleanUrl(urlBox.text.toString()) ?: return@setOnClickListener
            val key = keyBox.text.toString().trim()
            connect.isEnabled = false
            connect.text = "Connecting…"
            val attempt = ++Running.connectAttempts
            Running.connecting = attempt
            connectDialog = dialog
            connectDialogAttempt = attempt
            connectFieldsValid = ::valid
            thread(name = "budget-bank-connect") {
                // The key is also sealed with the phone's keystore here, off the main thread (that can take a moment).
                val result = runCatching { BankServer.snapshot(url, key) to sealAccessKey(key) }
                Running.main.post {
                    if (Running.connecting != attempt) return@post // cancelled, or tried again since
                    Running.connecting = 0
                    Running.liveScreen()?.bankChecked(url, attempt, result)
                }
            }
        }
        urlBox.requestFocus()
    }

    // The server answered "Connect": pick the checking account, or say what went wrong.
    private fun bankChecked(url: String, attempt: Int, result: Result<Pair<BankSnapshot, SealedKey>>) {
        // The dialog that started this attempt; none if the screen was replaced (or it was opened again) meanwhile.
        val dialog = connectDialog?.takeIf { it.isShowing && connectDialogAttempt == attempt }
        val (snapshot, key) = result.getOrNull() ?: (null to null)
        val checking = snapshot?.accounts.orEmpty().filter { it.isChecking }
            .ifEmpty { snapshot?.accounts.orEmpty().filter { it.type == "depository" } }
        if (snapshot == null || key == null || checking.isEmpty()) {
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = connectFieldsValid()
                text = "Connect"
            }
            val message = result.exceptionOrNull()?.let { bankErrorText(it, "Couldn't connect to the bank server. Try again.") }
                ?: "No checking account on that server yet. Link your bank in ClearBudget first."
            if (dialog != null) {
                // Shown in the dialog itself, so it stays while the address or key is fixed.
                dialog.setMessage(message)
                dialog.window?.decorView?.announceForAccessibility(message)
            } else {
                tell(message)
            }
            return
        }
        dialog?.dismiss()
        pickAccount(checking) { account ->
            bank.connect(url, key, account.id)
            applyBank(snapshot, announce = false)
            syncBank(force = true)
        }
    }

    private fun pickAccount(choices: List<BankAccount>, onPick: (BankAccount) -> Unit) {
        if (choices.size == 1) return onPick(choices[0])
        AlertDialog.Builder(this)
            .setTitle("Which account is your balance?")
            .setItems(choices.map { a ->
                "${a.institution ?: "Bank"} ${a.label}" + ((a.available ?: a.current)?.let { " · ${money.format(it)}" } ?: "")
            }.toTypedArray()) { _, which -> onPick(choices[which]) }
            .setNegativeButton("Cancel", null)
            .present()
    }

    // Settings → the bank: sync, switch accounts, or disconnect.
    private fun bankSettings() {
        AlertDialog.Builder(this)
            .setTitle(bankName())
            .setItems(arrayOf("Sync now", "Use a different account", "Disconnect")) { _, which ->
                when (which) {
                    0 -> syncBank(force = true)
                    // The saved balance right away, then a real sync (which keeps any sync problem showing).
                    1 -> {
                        val choices = bank.accounts.filter { it.type == "depository" }
                        when {
                            choices.isEmpty() -> tell("There's no bank account to choose yet. Tap Sync now, or link your " +
                                "checking account in ClearBudget first.")
                            choices.size == 1 && choices[0].id == bank.accountId ->
                                tell("${choices[0].label} is the only account on your bank server.")
                            else -> pickAccount(choices) {
                                bank.chooseAccount(it.id)
                                applyBank(null, announce = false)
                                syncBank(force = true)
                            }
                        }
                    }
                    else -> AlertDialog.Builder(this)
                        .setTitle("Disconnect your bank?")
                        .setMessage("You'll go back to updating your balance yourself. Your bank stays linked on the bank server.")
                        .setPositiveButton("Disconnect") { _, _ -> disconnectBank() }
                        .setNegativeButton("Cancel", null)
                        .present()
                }
            }
            .setNegativeButton("Close", null)
            .present()
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
            .present()
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
        if (bank.error != null || System.currentTimeMillis() - bank.checkedAt > 24 * 3_600_000L) {
            val synced = if (bank.checkedAt > 0) "Last synced ${ago(bank.checkedAt)}" else "Not synced yet"
            dialogParagraph(box, "$synced, so the newest purchases may be missing.")
        }
        if (counted.isEmpty()) dialogParagraph(box, "No purchases since payday (${period.format(dayFormat)}).")
        section("Spending since ${period.format(dayFormat)}", counted)
        section("Bills (not counted)", week.filter { it.kind == Kind.BILL })
        section("Not counted", week.filter { it.kind != Kind.SPEND && it.kind != Kind.BILL })
        dialogParagraph(box, "Tap one to change how it counts. Moving money between your own accounts, card and loan " +
            "payments, and paychecks don't count as spending.")
        dialog = AlertDialog.Builder(this)
            .setTitle("Spent ${money.format(spent)} this week")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .create()
        present(dialog)
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
                if (bills.isNotEmpty() && t.amount < 0) choices += "It paid a bill…" to {
                    AlertDialog.Builder(this)
                        .setTitle("Which bill did it pay?")
                        .setItems(bills.map { "${it.name} · ${money.format(it.amount)}" }.toTypedArray()) { _, which ->
                            set("bill:${bills[which].name}")
                        }
                        .setNegativeButton("Cancel") { _, _ -> showPurchases() }
                        .present()
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
            .present()
    }

    // A bill that was marked paid because of this transaction isn't paid after all.
    private fun unmarkBankPaid(index: Int, due: LocalDate, on: LocalDate) {
        val bill = bills.getOrNull(index) ?: return
        if (bill.paid[due] == on) bills[index] = bill.withoutPaid(due)
    }

    // ---------- Drawing the screens ----------

    // A finger is on a bill row. Redrawing would replace the row under it and cancel the swipe, so a redraw that
    // comes in then (a bank sync finishing) waits until the finger lifts.
    private var rowHeld = false
    private var refreshHeld = false

    private fun releaseRow() {
        rowHeld = false
        if (refreshHeld) {
            refreshHeld = false
            billList.post { refresh() }
        }
    }

    // Never let a bad number or date take the whole app down; show what happened instead.
    private fun refresh() {
        if (rowHeld) {
            refreshHeld = true
            return
        }
        findViewById<TextView>(R.id.today_label).text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        try {
            showSetup()
            showBills()
            recalculate()
        } catch (e: Exception) {
            Log.e("Budget", "refresh failed", e) // adb logcat -s Budget
            // Said once per screen: every redraw would hit the same problem.
            if (!refreshFailureShown) {
                refreshFailureShown = true
                tell("Budget couldn't show everything. Your numbers are still saved. Check Settings › Check for updates.")
            }
        }
    }

    private var refreshFailureShown = false

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
        } + unreadableBills.size.takeIf { it > 0 }?.let { n ->
            // Kept, but not counted anywhere until an update can read them.
            " · $n saved bill${if (n == 1) "" else "s"} can't be shown by this version; update the app"
        }.orEmpty()
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
            // "Syncing…" only while a sync someone asked for really is running.
            linked && Running.syncing != null && Running.syncAnnounce -> "Syncing…"
            linked && bank.error != null -> "Sync problem · tap for details"
            updated == null -> if (!linked) "Tap to update" else if (Running.syncing != null) "Syncing…" else "Tap to sync"
            pending != null -> "Includes ${if (pending == today) "today" else pending.format(weekday)}'s " +
                "${shortMoney(paycheckOn(pending))} pay" + if (linked) " (not in yet)" else ""
            early != null -> "Not counting ${early.format(weekday)}'s ${shortMoney(paycheckOn(early))} pay until then"
            linked -> "${bank.account?.institution ?: "Bank"} · synced ${ago(bank.checkedAt)}"
            // A weekday is enough for the last week; older, the date.
            week.bankIsEstimate -> "Estimated · you entered ${money.format(balance)} on " +
                (if (staleDays < 7) updated.format(weekday) else "${updated.format(shortDate)} · tap to update")
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
        present(dialog)
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
        askAmount("Log a purchase", "How much did you spend? It comes off your bank balance (${money.format(week.bank)}).", null,
            positive = true) { amount ->
            // Worked out again: the box may have been open across midnight (payday), which moves the balance.
            if (amount > 0) saveBalance(cents(computeWeek(LocalDate.now()).bank - amount), logged = amount)
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
        askBalanceFollowUps(BalanceFollowUp(value, logged, drop, oldBalance)) {
            restore(before)
            bills.clear()
            bills.addAll(billsBefore)
            save()
            refresh()
        }
    }

    // A balance that was just saved, while the questions after it are open: "Has today's pay landed?", "Did Rent
    // come out today?", "Did you spend $X?". Kept across rotation and dark-mode changes (onSaveInstanceState), so
    // an open question is asked again instead of being lost along with its answer. drop/oldBalance: how much
    // lower than the balance entered earlier today it is, and that balance.
    private class BalanceFollowUp(val value: Double, val logged: Double?, val drop: Double, val oldBalance: Double)

    private var followUp: BalanceFollowUp? = null

    // Answers change state in memory and are saved together at the end, so a question lost with the screen is
    // simply asked again from the start (`undo` is null then: the screen that could undo it is gone).
    private fun askBalanceFollowUps(f: BalanceFollowUp, undo: (() -> Unit)?) {
        followUp = f
        val today = LocalDate.now()
        val period = lastPayday(today)

        fun finishBalance() {
            followUp = null
            save()
            refresh()
            val week = computeWeek(today)
            val message = if (f.logged != null) {
                "Logged ${money.format(f.logged)} · balance ${money.format(f.value)}"
            } else {
                "Balance saved" + (week.spent?.takeIf { it > 0 }?.let { " · ${money.format(it)} spent ${sinceText(week)}" } ?: "")
            }
            showSnack(message, undo)
        }

        fun askCorrection(explained: Double) {
            val unexplained = cents(f.drop - explained)
            if (unexplained < 0.005) return finishBalance()
            AlertDialog.Builder(this)
                .setTitle("Did you spend ${money.format(unexplained)}?")
                .setMessage("This is ${money.format(f.drop)} less than the ${money.format(f.oldBalance)} from earlier today" +
                    (if (explained > 0) ", and ${money.format(explained)} of that is bills due today. Was the other " +
                        "${money.format(unexplained)} spending" else ". Was that spending") +
                    ", or was ${money.format(f.oldBalance)} a mistake?")
                .setPositiveButton("Spending") { _, _ -> finishBalance() }
                .setNegativeButton("A mistake") { _, _ ->
                    weekStartBalance = cents(weekStartBalance - unexplained)
                    finishBalance()
                }
                .setOnCancelListener { finishBalance() }
                .present()
        }

        // Bills due today: if the balance dropped by at least that much, ask whether they've come out.
        fun askBillsOut() {
            val dueToday = bills.filter { it.unpaid(today, today.plusDays(1)).isNotEmpty() }
            val total = dueToday.sumOf { it.amount }
            val spent = computeWeek(today).spent ?: 0.0
            if (f.logged != null || dueToday.isEmpty() || spent < total - 0.004) return askCorrection(0.0)
            val names = dueToday.joinToString { it.name }
            AlertDialog.Builder(this)
                .setTitle("Did $names come out today?")
                .setMessage("$names (${money.format(total)}) is due today, and your balance dropped by at least that much.")
                .setPositiveButton("Yes, it's paid") { _, _ ->
                    for (bill in dueToday) {
                        val i = indexOfBill(bill)
                        if (i >= 0) bills[i] = bills[i].withPaid(today, today)
                    }
                    // Paid marks only count after the starting balance's day, so take it off that balance instead.
                    if (weekStartTaken == today) weekStartBalance = cents(weekStartBalance - total)
                    askCorrection(total)
                }
                .setNegativeButton("Not yet") { _, _ -> askCorrection(0.0) }
                .setOnCancelListener { askCorrection(0.0) }
                .present()
        }

        // Payday morning: a balance about a whole paycheck lower than expected probably doesn't have it yet.
        val week = computeWeek(today)
        val pay = paycheckOn(period)
        if (f.logged == null && today == period && pay > 0 && weekStartProjected && weekStartTaken == period &&
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
                .present()
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
        if (!present(dialog)) return
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
        showSnack("Pay saved: ${shortMoney(amount)} every ${payday.getDisplayName(TextStyle.FULL, Locale.getDefault())}") {
            weeklyIncome = oldIncome
            payday = oldDay
            weekStart = oldStart
            paycheckChanges.clear()
            paycheckChanges.putAll(oldChanges)
            save()
            refresh()
        }
        if (dayChanged && !bank.isLinked && balanceUpdated != null && balanceUpdated != LocalDate.now()) {
            // The old balance was moved forward using the old payday; start fresh from today's.
            editBalance("Your payday changed. What's in your account right now? This keeps the numbers right from here.")
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
            "Check for updates (${Running.updateStatus})",
        )
        AlertDialog.Builder(this)
            .setTitle("Settings · version ${BuildConfig.VERSION_NAME}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editPay()
                    1 -> if (bank.isLinked) bankSettings() else connectBank()
                    else -> {
                        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
                        checkForUpdate(fromUser = true)
                    }
                }
            }
            .setPositiveButton("Close", null)
            .present()
    }

    // Small dialog with one "$" field and the keyboard already up. Save only works with a valid amount,
    // and the keyboard's Done key saves too.
    private fun askAmount(
        title: String,
        message: String,
        current: Double?,
        signed: Boolean = false,
        showZero: Boolean = false,
        positive: Boolean = false, // more than zero (a purchase of $0 isn't one)
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
        fun value() = parseMoney(input.text.toString())?.takeIf { (signed || it >= 0) && (!positive || it > 0) }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(content)
            .setPositiveButton("Save") { _, _ -> value()?.let(onSave) }
            .setNegativeButton("Cancel", null)
        neutral?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        if (!present(dialog)) return
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

    // Slide-up sheet for adding a bill (tapped == null) or editing one.
    private fun openBillSheet(tapped: Bill?) {
        if (isFinishing || isDestroyed) return // a late tap or posted shortcut on a screen that's going away
        if (sheetDialog?.isShowing == true) return // a quick double-tap shouldn't stack two sheets
        // The latest copy of the bill (a sync may have marked it paid since the row was drawn); none if it was
        // deleted meanwhile.
        val existing = tapped?.let { bills.getOrNull(indexOfBill(it)) ?: return }
        val today = LocalDate.now()
        var closeSheet: () -> Unit = {}
        val dialog = object : Dialog(this, R.style.SheetDialog) {
            // Still called at targetSdk 35 without predictive back; see MainActivity.onBackPressed (F-25).
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() = closeSheet()
        }
        sheetDialog = dialog
        dialog.setContentView(R.layout.sheet_bill)
        val window = dialog.window!!
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setTitle(if (existing == null) "New bill" else "Edit bill")
        // ADJUST_RESIZE is deprecated from API 30 (insets are handled below there), but Android 8-10 need it so
        // the keyboard doesn't cover the sheet.
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
            // Deprecated in API 35, where windows are always edge-to-edge; Android 11-14 still need both.
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(R.color.card)
            @Suppress("DEPRECATION")
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
            return Bill(name, amount, freq, date, existing?.paid ?: emptyMap(), existing?.id ?: Any())
        }
        fun updateLabels() {
            val freq = Freq.values()[freqSpinner.selectedItemPosition]
            val amount = parseMoney(amountBox.text.toString())?.takeIf { it > 0 }
            val share = amount?.let { "≈ ${money.format(it * 7 / freq.cycleDays)} from each weekly paycheck" }
            val passed = pickedDate?.takeIf { it.isBefore(today) }?.let { picked ->
                buildBill()?.let { "${picked.format(shortDate)} has passed, so it's next due ${it.nextDue(today).format(shortDate)}." }
            }
            shareText.text = listOfNotNull(passed, share).joinToString("\n")
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
            present(DatePickerDialog(this, { _, year, month, day ->
                val picked = LocalDate.of(year, month + 1, day)
                pickedDate = picked
                dateField.text = picked.format(dayFormat)
                dateField.error = null
                updateLabels()
            }, start.year, start.monthValue - 1, start.dayOfMonth))
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
                    if (!isFinishing && !isDestroyed) then()
                }.start()
        }
        closeSheet = { close() }

        // Saves the bill (with Undo) and closes the sheet.
        fun commit(bill: Bill, quiet: Boolean = false, then: (Bill) -> Unit = {}) {
            val before = bills.toList()
            var saved = bill
            if (existing == null) {
                bills.add(bill)
            } else {
                val index = indexOfBill(existing)
                if (index >= 0) { // gone already (deleted meanwhile): don't bring it back
                    // Keep paid marks a bank sync added while the sheet was open, plus any added here.
                    val current = bills[index]
                    saved = Bill(bill.name, bill.amount, bill.freq, bill.date, current.paid + (bill.paid - existing.paid.keys), bill.id)
                    bills[index] = saved
                }
            }
            // Transactions assigned to this bill by hand follow it to its new name.
            val renamedFrom = existing?.name?.takeIf { it != saved.name }
            val movedTxns = if (renamedFrom != null) bank.renameBillOverrides(renamedFrom, saved.name) else emptySet()
            save()
            haptic(saveButton)
            close {
                refresh()
                if (!quiet) {
                    showSnack(if (existing == null) "${saved.name} added" else "${saved.name} saved") {
                        bills.clear()
                        bills.addAll(before)
                        for (txnId in movedTxns) bank.setOverride(txnId, "bill:$renamedFrom")
                        save()
                        refresh()
                    }
                }
                then(saved)
            }
        }

        var duplicateWarned: String? = null
        fun fieldError(field: TextView, message: String) {
            field.error = message
            field.requestFocus()
            field.announceForAccessibility(message)
        }
        saveButton.setOnClickListener {
            if (closing) return@setOnClickListener // a second tap while the sheet is closing
            val bill = buildBill()
            if (bill == null) {
                when {
                    nameBox.text.toString().isBlank() -> fieldError(nameBox, "Enter a name")
                    parseMoney(amountBox.text.toString())?.takeIf { it >= 0 } == null -> fieldError(amountBox, "Enter an amount")
                    else -> {
                        dateField.error = "Pick a date"
                        dateField.announceForAccessibility("Pick a date")
                        pickDate()
                    }
                }
                return@setOnClickListener
            }
            val sameName = bills.any { it.id !== bill.id && it.name.equals(bill.name, ignoreCase = true) }
            if (sameName && duplicateWarned != bill.name) {
                duplicateWarned = bill.name
                fieldError(nameBox, "You already have a bill called ${bill.name}. Bank payments you assign by name go to " +
                    "the first one, so a different name (like ${bill.name} 2) is safer. Tap ${saveButton.text} again to keep it.")
                return@setOnClickListener
            }
            // Moving a bill that's due now to a later date: did they pay this one (so it isn't counted as spending)?
            // `shown` is only set when editing, so `existing` is non-null here (the compiler knows it too).
            val movedFrom = shown?.takeIf {
                pickedDate?.isAfter(it) == true && ChronoUnit.DAYS.between(today, it) <= 3 && it !in existing.paid
            }
            if (movedFrom != null) {
                AlertDialog.Builder(this)
                    .setTitle("Did you pay the ${movedFrom.format(shortDate)} ${bill.name}?")
                    .setMessage("Moving the date skips that one. If you paid it, it's marked paid so the money isn't counted as spending.")
                    .setPositiveButton("Yes, it's paid") { _, _ -> commit(bill.withPaid(movedFrom, today)) }
                    .setNegativeButton("No, skip it") { _, _ -> commit(bill) }
                    .present()
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
    private fun markPaid(tapped: Bill) {
        val bill = bills.getOrNull(indexOfBill(tapped)) ?: return
        val today = LocalDate.now()
        val due = bill.nextDue(today)
        fun apply(takeOff: Boolean) {
            // Looked up again: a sync may have changed the bill while the question was open.
            val index = indexOfBill(bill)
            if (index < 0) return
            val current = bills[index]
            val before = balanceState()
            val billsBefore = bills.toList()
            val ahead = current.paid.keys.any { !it.isBefore(today) }
            bills[index] = current.withPaid(due, today)
            if (takeOff) {
                balance = cents(balance - bill.amount)
                if (weekStartTaken == today && !weekStartProjected) weekStartBalance = cents(weekStartBalance - bill.amount)
            }
            save()
            refresh()
            haptic(billList)
            showSnack("${bill.name} · ${due.format(shortDate)} ${if (ahead) "also " else ""}marked paid" +
                (if (takeOff) " · balance ${money.format(balance)}" else "")) {
                if (takeOff) {
                    restore(before)
                    bills.clear()
                    bills.addAll(billsBefore)
                } else {
                    // Just this mark (the bank may have changed other things since).
                    val i = indexOfBill(bill)
                    if (i >= 0 && bills[i].paid[due] == today) bills[i] = bills[i].withoutPaid(due)
                }
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
                .present()
        } else {
            apply(takeOff = false)
        }
    }

    private fun unmarkPaid(tapped: Bill, due: LocalDate) {
        val index = indexOfBill(tapped)
        if (index < 0) return
        val bill = bills[index]
        val paidOn = bill.paid[due] ?: return // not marked paid any more
        // If the bank's transaction was taken as this payment, it isn't: count it as spending instead.
        val txnId = if (bank.isLinked) billMatches().entries.firstOrNull { it.value == (index to due) }?.key else null
        txnId?.let { bank.setOverride(it, "spend") }
        bills[index] = bill.withoutPaid(due)
        save()
        refresh()
        showSnack("${bill.name} · ${due.format(shortDate)} no longer marked paid") {
            val i = indexOfBill(bill)
            if (i >= 0) {
                txnId?.let { bank.setOverride(it, null) }
                bills[i] = bills[i].withPaid(due, paidOn)
                save()
                refresh()
            }
        }
    }

    private fun deleteBill(tapped: Bill) {
        val index = indexOfBill(tapped)
        if (index < 0) return
        val bill = bills.removeAt(index)
        save()
        refresh()
        haptic(billList)
        snackUndo = null
        pendingDeletes += index to bill
        showSnackBar(deletedText(), canUndo = true)
        moveScreenReaderToUndo()
    }

    // The deleted row is gone, so a screen reader's focus would otherwise drop to the top of the page; putting
    // it on Undo instead lets a TalkBack user take the delete back. Lint warns against moving accessibility
    // focus in general, but here it replaces focus that was just destroyed, and only while TalkBack is on.
    @SuppressLint("AccessibilityFocus")
    private fun moveScreenReaderToUndo() {
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
        snackHideAt = System.currentTimeMillis() + timeout
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
                    rowHeld = true
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
                    // A swipe that goes through keeps redraws held until the bill is deleted / marked paid, so the
                    // row doesn't come back for a moment; actions are skipped on a screen that was replaced.
                    when {
                        swiping && -dx > view.width * 0.35f -> {
                            busy = true
                            view.animate().translationX(-view.width.toFloat()).setDuration(160)
                                .withEndAction {
                                    if (!isFinishing && !isDestroyed) onDelete()
                                    releaseRow()
                                }.start()
                        }
                        swiping && dx > view.width * 0.35f -> {
                            busy = true
                            view.animate().translationX(view.width.toFloat()).setDuration(160)
                                .withEndAction {
                                    if (!isFinishing && !isDestroyed) onPaid()
                                    releaseRow()
                                }.start()
                        }
                        swiping -> {
                            releaseRow()
                            settle()
                        }
                        else -> {
                            releaseRow()
                            if (!moved) view.performClick()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    releaseRow()
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

    // Saved bills this version can't read (damaged, or written by a newer version). They're written back untouched
    // on every save instead of being dropped, so nothing is lost for good.
    private val unreadableBills = mutableListOf<Any>()

    // A saved value of the wrong type can only come from a damaged file. It's treated as missing rather than
    // crashing the app before the update banner (which could bring a fix) shows up; a list is copied aside first.
    private fun <T> read(key: String, get: () -> T): T? = try {
        get()
    } catch (e: ClassCastException) {
        Log.w("Budget", "Ignoring saved \"$key\": wrong type")
        if (key == "bills" || key == "paycheck_changes") keepUnreadable(key, prefs.all[key].toString())
        null
    }

    private fun load() {
        fun text(key: String) = read(key) { prefs.getString(key, null) }
        fun date(key: String) = savedDate(text(key))
        fun number(key: String) = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        fun flag(key: String) = read(key) { prefs.getBoolean(key, false) } ?: false
        balance = number("balance") ?: 0.0
        balanceUpdated = date("balance_updated")
        pendingPay = date("pending_pay")
        earlyPay = date("early_pay")
        weeklyIncome = number("weekly_income") ?: 0.0
        payday = read("payday") { prefs.getInt("payday", DayOfWeek.FRIDAY.value) }?.takeIf { it in 1..7 }
            ?.let { DayOfWeek.of(it) } ?: DayOfWeek.FRIDAY
        billsNone = flag("bills_none")
        text("bills")?.let { saved ->
            try {
                val list = JSONArray(saved)
                for (i in 0 until list.length()) {
                    val item = list.opt(i)
                    val bill = (item as? JSONObject)?.let { billFromJson(it) }
                    when {
                        bill != null -> bills += bill
                        item == null -> Unit
                        // An item JSON can read but not write back (e.g. an amount of NaN) would make the whole
                        // list unwritable on the next save; keep the original text aside instead.
                        !jsonWritable(item) -> keepUnreadable("bills", saved)
                        else -> unreadableBills += item
                    }
                }
                if (unreadableBills.isNotEmpty()) Log.w("Budget", "Keeping ${unreadableBills.size} saved bills this version can't read")
            } catch (e: JSONException) {
                keepUnreadable("bills", saved)
            }
        }

        // Keep paycheck changes from this pay week on, and any the last balance still depends on.
        val periodStart = lastPayday(LocalDate.now())
        val keepFrom = balanceUpdated?.takeIf { it.isBefore(periodStart) } ?: periodStart
        text("paycheck_changes")?.let { saved ->
            try {
                val changes = JSONObject(saved)
                for (key in changes.keys()) {
                    val day = savedDate(key) ?: continue
                    val amount = changes.optDouble(key)
                    if (!day.isBefore(keepFrom) && amount.isFinite()) paycheckChanges[day] = amount
                }
            } catch (e: JSONException) {
                keepUnreadable("paycheck_changes", saved)
            }
        }

        weekStart = date("week_start")
        weekStartBalance = number("week_start_balance") ?: 0.0
        weekStartTaken = date("week_start_taken")
        weekStartProjected = flag("week_start_projected")
    }

    // A whole saved list that isn't valid JSON is copied aside before the next save replaces it, so it can still be
    // recovered (it's included in Android's backup like the rest). The first copy is kept; a later, different one
    // goes next to it.
    private fun keepUnreadable(key: String, raw: String) {
        Log.w("Budget", "Saved \"$key\" is unreadable; keeping a copy")
        val first = "${key}_unreadable"
        val copyKey = if (!prefs.contains(first) || read(first) { prefs.getString(first, null) } == raw) first else "${first}_latest"
        prefs.edit().putString(copyKey, raw).apply()
    }

    private fun save() {
        val saved = JSONArray()
        for (bill in bills) saved.put(billToJson(bill))
        for (item in unreadableBills) saved.put(item)
        val changes = JSONObject()
        for ((date, amount) in paycheckChanges) changes.put(date.toString(), amount)
        // A list JSON can't write (toString() would give null) must never erase the saved bills.
        val billsText = if (jsonWritable(saved)) saved.toString() else {
            Log.w("Budget", "Bills couldn't be written; keeping the saved copy")
            read("bills") { prefs.getString("bills", null) }
        }
        prefs.edit()
            // Left by v2.0 ("income") and v2.3-2.4 ("bills_open"); nothing has read them since.
            .remove("income")
            .remove("bills_open")
            .putString("balance", balance.toString())
            .putString("balance_updated", balanceUpdated?.toString())
            .putString("pending_pay", pendingPay?.toString())
            .putString("early_pay", earlyPay?.toString())
            .putString("weekly_income", weeklyIncome.toString())
            .putInt("payday", payday.value)
            .putBoolean("bills_none", billsNone)
            .putString("bills", billsText)
            .putString("paycheck_changes", changes.toString())
            .putString("week_start", weekStart?.toString())
            .putString("week_start_balance", weekStartBalance.toString())
            .putString("week_start_taken", weekStartTaken?.toString())
            .putBoolean("week_start_projected", weekStartProjected)
            .apply()
    }

    // ---------- Updates ----------

    // A download that died (no signal, app closed) can leave a half-written install behind; clear those. Once per
    // app process, and never the one this process is still writing or waiting on: a home-screen shortcut starts a
    // fresh screen in the middle of an update, too.
    private fun clearStaleInstallSessions() {
        if (Running.sessionsCleared) return
        Running.sessionsCleared = true
        val installer = packageManager.packageInstaller
        thread(name = "budget-clear-installs") {
            for (session in installer.mySessions) {
                if (session.sessionId != Running.sessionId) abandon(installer, session.sessionId)
            }
        }
    }

    // Looks at the latest GitHub Release; if it's newer than this build, shows the update banner. A new screen
    // (rotation, dark mode) reuses a check from the last half hour instead of asking GitHub again.
    private fun checkForUpdate(fromUser: Boolean = false) {
        if (!fromUser && Running.updateCheckedAt > 0 && System.currentTimeMillis() - Running.updateCheckedAt < 30 * 60_000L) {
            showUpdateState()
            return
        }
        val current = BuildConfig.VERSION_NAME
        Running.updateStatus = "checking…"
        thread(name = "budget-update-check") {
            val result = runCatching {
                val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) throw GitHubReplyException("HTTP ${conn.responseCode}")
                    val release = JSONObject(readUpTo(conn.inputStream, 1 shl 20))
                    val latest = release.getString("tag_name").removePrefix("v")
                    val assets = release.getJSONArray("assets")
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.getString("name").endsWith(".apk") }
                    if (apk != null && isNewer(latest, current)) {
                        Release(latest, apk.getString("browser_download_url"), apk.optLong("size", -1L))
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }
            Running.main.post {
                val update = result.getOrNull()
                val error = result.exceptionOrNull()
                error?.let { Log.w("Budget", "Update check failed: ${it.javaClass.name} ${(it as? GitHubReplyException)?.message.orEmpty()}") }
                // No signal vs. GitHub answering oddly (rate limit, outage): different advice.
                val offline = error is IOException
                Running.updateFailedAt = if (error != null) SystemClock.elapsedRealtime() else 0L
                Running.updateStatus = when {
                    error != null -> if (offline) "couldn't check" else "GitHub didn't answer"
                    update == null -> "up to date"
                    // A debug build is its own app (com.gh00ul.budget.debug), so it can't install a release
                    // over itself; it only says one is out.
                    BuildConfig.DEBUG -> "version ${update.version} is out; debug builds don't update"
                    else -> "version ${update.version} is ready"
                }
                if (result.isSuccess) {
                    Running.update = update
                    Running.updateCheckedAt = System.currentTimeMillis()
                }
                val screen = Running.liveScreen() ?: return@post
                screen.showUpdateState()
                if (fromUser) {
                    val message = when {
                        error != null && offline -> "Couldn't check for updates. Check your connection."
                        error != null -> "GitHub didn't answer the update check. Try again later."
                        update == null -> "You're up to date (version $current)."
                        BuildConfig.DEBUG -> "Version ${update.version} is out. Debug builds don't update themselves."
                        else -> "Version ${update.version} is ready. Tap Update on Summary."
                    }
                    Toast.makeText(screen, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // The update banner, from what the last check and download left in Running.
    private fun showUpdateState() {
        val release = Running.update?.takeIf { !BuildConfig.DEBUG }
        updateBanner.visibility = if (release != null) View.VISIBLE else View.GONE
        if (release == null) return
        when {
            Running.downloading -> {
                updateText.text = "Downloading…"
                updateButton.isEnabled = false
            }
            else -> {
                updateText.text = Running.downloadFailure ?: "Version ${release.version} is ready"
                updateButton.isEnabled = true
            }
        }
    }

    // Streams the new APK into Android's installer; Android then asks you to confirm the update.
    private fun installUpdate() {
        val release = Running.update?.takeIf { !BuildConfig.DEBUG } ?: return
        if (Running.downloading) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Budget to install updates, then tap Update again", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                // Some phones (work profiles, managed devices) don't have this screen.
                tell("Turn on Settings › Apps › Budget › Install unknown apps, then tap Update again.")
            }
            return
        }

        Running.downloading = true
        Running.downloadFailure = null
        showUpdateState()
        val startedAt = SystemClock.elapsedRealtime()
        val app = applicationContext // not this screen: the download can outlive it
        val installer = app.packageManager.packageInstaller
        thread(name = "budget-update-download") {
            var sessionId = -1
            val result = runCatching {
                val conn = URL(release.url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("download: HTTP ${conn.responseCode}")
                    val length = conn.contentLengthLong.takeIf { it > 0 } ?: release.size.takeIf { it > 0 } ?: -1L
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(app.packageName) // only ever installs Budget itself
                    sessionId = installer.createSession(params)
                    Running.sessionId = sessionId
                    installer.openSession(sessionId).use { session ->
                        val copied = conn.inputStream.use { input ->
                            session.openWrite("budget.apk", 0, length).use { output ->
                                copyUpTo(input, output, release.size).also { session.fsync(output) }
                            }
                        }
                        // A connection that drops can end the stream early without an error; don't install half an APK.
                        if (release.size > 0 && copied != release.size) throw IOException("download: incomplete")
                        val callback = PendingIntent.getBroadcast(
                            app, sessionId, Intent(app, InstallReceiver::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )
                        session.commit(callback.intentSender)
                    }
                } finally {
                    conn.disconnect()
                }
            }
            if (result.isFailure && sessionId >= 0) {
                abandon(installer, sessionId)
                Running.sessionId = -1
            }
            result.exceptionOrNull()?.let { Log.w("Budget", "Update download failed: ${it.javaClass.name}") }
            Running.main.post {
                Running.downloading = false
                val error = result.exceptionOrNull()
                Running.downloadFailure = when {
                    error == null -> null
                    // Android 15+ cuts an app's network soon after it leaves the screen.
                    error is IOException && Running.leftSince(startedAt) ->
                        "The download stopped when you left Budget. Tap Update to try again."
                    error is IOException -> "The update didn't finish downloading. Check your connection and tap Update."
                    else -> "The update didn't download. Tap Update to try again."
                }
                val screen = Running.liveScreen() ?: return@post
                screen.showUpdateState()
                if (error == null) screen.updateText.text = "Installing…"
            }
        }
    }
}

// "3.10.0" is newer than "3.9.2". (Not on the screen class, so the update-check thread doesn't hold a screen.)
internal fun isNewer(latest: String, current: String): Boolean {
    val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
    val b = current.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

// Whether Android's JSON can write this back out. It refuses NaN and infinite numbers anywhere inside, and
// toString() then returns null (which Kotlin can't see: it types toString() as never null). Checked by walking the
// value, so it's plain logic that unit tests can run.
internal fun jsonWritable(value: Any?): Boolean = when (value) {
    is Double -> value.isFinite()
    is Float -> value.isFinite()
    is JSONObject -> value.keys().asSequence().all { jsonWritable(value.opt(it)) }
    is JSONArray -> (0 until value.length()).all { jsonWritable(value.opt(it)) }
    else -> true
}

// What to say when the phone won't open Android's installer.
private const val INSTALLER_BLOCKED =
    "This phone won't let Budget open the installer. Open github.com/$REPO/releases in your browser to install the update."

// GitHub answered, but not with a usable release (rate limit, outage, unexpected reply) — not a connection problem.
private class GitHubReplyException(detail: String) : Exception(detail)

// Copies a download into the installer, stopping if it's bigger than the release said (`limit`, or -1 if unknown),
// so a wrong or endless download can't fill the phone first.
internal fun copyUpTo(input: InputStream, output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return copied
        copied += n
        if (limit > 0 && copied > limit) throw IOException("download: larger than the release")
        output.write(buffer, 0, n)
    }
}

// A GitHub release that's newer than this build. size = the APK's size in bytes, or -1 if GitHub didn't say.
private class Release(val version: String, val url: String, val size: Long)

// Work that outlives one screen. Rotation, dark mode, font size, split-screen resizing and the home-screen
// shortcuts (which always start a fresh screen) replace MainActivity while a bank sync, a bank connect or an
// update download keeps running, so their state lives here, once per app process. Main thread only (except
// sessionId): worker threads hand results back through `main`, never runOnUiThread, which would keep the old
// screen alive and deliver to it rather than to the current one.
private object Running {
    val main = Handler(Looper.getMainLooper())
    var screen: WeakReference<MainActivity>? = null // the newest screen, where results go
    var resumed: WeakReference<MainActivity>? = null // the screen that's on top right now, if any
    var visibleScreens = 0 // screens between onStart and onStop
    var leftAt = 0L // SystemClock.elapsedRealtime() when Budget last went off screen (not counting rotations)

    var syncing: String? = null // the bank connection a sync is running for
    var syncAnnounce = false // "Sync now" was asked for: say how it went
    var syncAgain = false // a sync was cut off by leaving Budget (or due while it was away): run it on return
    var syncAgainAnnounce = false
    var connectAttempts = 0
    var connecting = 0 // the "Connect your bank" attempt still waiting for the server; 0 = none

    var update: Release? = null // from the last update check
    var updateCheckedAt = 0L
    var updateStatus = "checking…" // for Settings
    var updateFailedAt = 0L // SystemClock.elapsedRealtime() of the last failed check, 0 if it worked
    var downloading = false
    var downloadFailure: String? = null // shown on the update banner until the next try
    @Volatile var sessionId = -1 // the install session this process is writing or waiting on
    var sessionsCleared = false
    var installPrompt: Intent? = null // Android's "Update this app?" screen, waiting for Budget to be on screen
    var installFailure: String? = null // why the last install failed, if no screen was there to say so
    var strictMode = false // debug builds: StrictMode is on for this process

    fun liveScreen() = screen?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    fun resumedScreen() = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    // Whether Budget went off screen after `since` (an elapsedRealtime), or is off screen now.
    fun leftSince(since: Long) = visibleScreens == 0 || leftAt >= since

    // Since Android 10 (and the 2023 security fix on older versions), the installer's result can only open the
    // confirm screen while Budget is on screen. If it isn't, the screen opens the next time Budget is.
    fun showInstallPrompt(context: Context, prompt: Intent) {
        prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val top = resumed?.get()
        if (top == null && Build.VERSION.SDK_INT >= 29) {
            installPrompt = prompt
            return
        }
        try {
            (top ?: context).startActivity(prompt)
        } catch (e: ActivityNotFoundException) { // the installer is disabled or hidden by a device policy
            Log.w("Budget", "Couldn't open the install prompt: ${e.javaClass.name}")
            installFailed(context, INSTALLER_BLOCKED)
        } catch (e: SecurityException) { // Android refused the launch (e.g. its intent-redirect checks)
            Log.w("Budget", "Couldn't open the install prompt: ${e.javaClass.name}")
            installFailed(context, INSTALLER_BLOCKED)
        }
    }

    // Shown on the screen that's on top, or kept for the next one (a toast from the background may not show).
    fun installFailed(context: Context, message: String) {
        val top = resumedScreen()
        if (top != null) top.tell(message) else installFailure = message
    }
}

// Debug builds only (once per process): log, never crash on, disk or network work on the main thread, leaked
// objects and similar mistakes, so they show up in `adb logcat -s StrictMode` while testing. Untagged-socket
// checks are left out: every plain HttpURLConnection request would trip them.
private fun enableStrictModeInDebug() {
    if (!BuildConfig.DEBUG || Running.strictMode) return
    Running.strictMode = true
    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
    val vm = StrictMode.VmPolicy.Builder()
        .detectActivityLeaks()
        .detectLeakedClosableObjects()
        .detectLeakedRegistrationObjects()
        .detectLeakedSqlLiteObjects()
        .detectFileUriExposure()
        .detectCleartextNetwork()
        .detectContentUriWithoutPermission()
    if (Build.VERSION.SDK_INT >= 28) vm.detectNonSdkApiUsage()
    if (Build.VERSION.SDK_INT >= 29) vm.detectCredentialProtectedWhileLocked()
    if (Build.VERSION.SDK_INT >= 31) vm.detectUnsafeIntentLaunch()
    StrictMode.setVmPolicy(vm.penaltyLog().build())
}

// Abandons an install session; one that's already gone is fine.
private fun abandon(installer: PackageInstaller, sessionId: Int) {
    try {
        installer.abandonSession(sessionId)
    } catch (e: SecurityException) {
        Log.w("Budget", "Couldn't abandon install session: ${e.javaClass.name}") // finished or not ours any more
    }
}

// Reads a response body, refusing one larger than `limit` bytes.
internal fun readUpTo(input: InputStream, limit: Int): String = input.use { stream ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        if (out.size() + n > limit) throw IOException("response too large")
        out.write(buffer, 0, n)
    }
    out.toString("UTF-8")
}

// Receives the installer's result and shows Android's "Do you want to update this app?" prompt.
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The typed getParcelableExtra is API 33+ and unreliable on 33 itself, so it's used from 34 on.
            val prompt = if (Build.VERSION.SDK_INT >= 34) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            if (prompt != null) {
                // This process may have just been started for this result; the session is still in use.
                Running.sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, Running.sessionId)
                Running.showInstallPrompt(context, prompt)
                return
            }
            Log.w("Budget", "Installer asked for confirmation without a prompt")
            Running.installFailed(context, "The update didn't install. Tap Update to try again.")
            return
        }
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == Running.sessionId) Running.sessionId = -1
        if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_FAILURE_ABORTED) return
        Log.w("Budget", "Update install failed with status $status") // the status code only; the message can be long
        Running.installFailed(context, when (status) {
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "Your phone's security settings blocked the update. On Samsung phones, check Settings › Security and privacy › Auto Blocker."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "Don't uninstall Budget: that deletes your data. This update can't replace the Budget on this phone; " +
                    "your current version keeps working. Ask whoever set it up for help."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update doesn't work on this phone. Your current version still works."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage for the update. Free up some space and try again."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded update was damaged. Tap Update to try again."
            else -> "The update didn't install. Tap Update to try again."
        })
    }
}
