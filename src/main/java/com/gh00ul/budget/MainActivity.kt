package com.gh00ul.budget

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
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
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.concurrent.thread

private const val REPO = "gh00ul/budget"

class MainActivity : Activity() {
    // day = day of the month the bill is due (1-31).
    private class Bill(val name: String, val amount: Double, val day: Int)

    private class Tab(val label: String, val icon: Int, val page: Int)

    private val tabs = listOf(
        Tab("Summary", R.drawable.ic_tab_summary, R.id.page_summary),
        Tab("Paydays", R.drawable.ic_tab_paydays, R.id.page_paydays),
        Tab("Bills", R.drawable.ic_tab_bills, R.id.page_bills),
        Tab("Income", R.drawable.ic_tab_income, R.id.page_income),
    )
    private val navItems = mutableListOf<View>()
    private var currentTab = 0

    private val prefs by lazy { getSharedPreferences("budget", MODE_PRIVATE) }
    private val money = NumberFormat.getCurrencyInstance()
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val bills = mutableListOf<Bill>()
    private var balance = 0.0
    private var weeklyIncome = 0.0
    private var payday = DayOfWeek.FRIDAY
    // One-off paycheck amounts (overtime, short week) that replace the weekly income on that date.
    private val paycheckChanges = mutableMapOf<LocalDate, Double>()
    private var updateUrl: String? = null

    // Theme colors (they change in dark mode).
    private val positive by lazy { getColor(R.color.positive) }
    private val negative by lazy { getColor(R.color.negative) }
    private val warning by lazy { getColor(R.color.warning) }
    private val secondary by lazy { getColor(R.color.text_secondary) }
    private val textColor by lazy { getColor(R.color.text) }
    private val accent by lazy { getColor(R.color.chip_text) }

    private lateinit var billList: LinearLayout
    private lateinit var billsEmpty: View
    private lateinit var billsTotalView: TextView
    private lateinit var forecastList: LinearLayout
    private lateinit var endLabel: TextView
    private lateinit var endBalance: TextView
    private lateinit var hero: View
    private lateinit var heroLabel: TextView
    private lateinit var heroAmount: TextView
    private lateinit var heroNext: TextView
    private lateinit var glancePayday: TextView
    private lateinit var glanceBill: TextView
    private lateinit var glanceDue: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitToSystemBars()
        setUpTabs()

        billList = findViewById(R.id.bill_list)
        billsEmpty = findViewById(R.id.bills_empty)
        billsTotalView = findViewById(R.id.bills_total)
        forecastList = findViewById(R.id.forecast_list)
        endLabel = findViewById(R.id.end_label)
        endBalance = findViewById(R.id.end_balance)
        hero = findViewById(R.id.hero)
        heroLabel = findViewById(R.id.hero_label)
        heroAmount = findViewById(R.id.hero_amount)
        heroNext = findViewById(R.id.hero_next)
        glancePayday = findViewById(R.id.glance_payday)
        glanceBill = findViewById(R.id.glance_bill)
        glanceDue = findViewById(R.id.glance_due)
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update_button)

        load()
        bindMoneyField(R.id.balance, balance) { balance = it }
        bindMoneyField(R.id.weekly_income, weeklyIncome) { weeklyIncome = it }

        val days = DayOfWeek.values()
        val paydaySpinner = findViewById<Spinner>(R.id.payday)
        paydaySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, days.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        paydaySpinner.setSelection(payday.ordinal)
        paydaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                payday = days[position]
                save()
                recalculate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val nameBox = findViewById<EditText>(R.id.bill_name)
        val dayBox = findViewById<EditText>(R.id.bill_day)
        val amountBox = findViewById<EditText>(R.id.bill_amount)
        findViewById<Button>(R.id.add_button).setOnClickListener {
            val name = nameBox.text.toString().trim()
            val day = dayBox.text.toString().toIntOrNull()
            val amount = parseMoney(amountBox.text.toString())
            when {
                name.isEmpty() -> nameBox.requestFocus()
                day == null || day !in 1..31 -> dayBox.requestFocus()
                amount == null -> amountBox.requestFocus()
                else -> {
                    bills.add(Bill(name, amount, day))
                    bills.sortBy { it.day }
                    save()
                    showBills()
                    nameBox.text.clear()
                    dayBox.text.clear()
                    amountBox.text.clear()
                    nameBox.requestFocus()
                }
            }
        }

        findViewById<View>(R.id.glance_payday_row).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.glance_bill_row).setOnClickListener { showTab(2) }
        findViewById<View>(R.id.glance_due_row).setOnClickListener { showTab(2) }
        updateButton.setOnClickListener { installUpdate() }

        checkForUpdate()
    }

    // Redraw on every return to the app so "due in X days" and the paydays stay current.
    override fun onResume() {
        super.onResume()
        showBills()
    }

    // Back from another tab goes to Summary before leaving the app.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentTab != 0) showTab(0) else super.onBackPressed()
    }

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
        showTab(0)
    }

    private fun showTab(index: Int) {
        currentTab = index
        tabs.forEachIndexed { i, tab ->
            val selected = i == index
            findViewById<View>(tab.page).visibility = if (selected) View.VISIBLE else View.GONE
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
        currentFocus?.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    // Android 15+ draws the app behind the status/navigation bars, so pad the pages and tab bar clear of
    // them. The tab bar hides while the keyboard is open so the page has room.
    private fun fitToSystemBars() {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
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

    private fun bindMoneyField(id: Int, initial: Double, onChange: (Double) -> Unit) {
        val box = findViewById<EditText>(id)
        if (initial != 0.0) box.setText(plain(initial))
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(parseMoney(s.toString()) ?: 0.0)
                save()
                recalculate()
            }
        })
    }

    private fun showBills() {
        val today = LocalDate.now()
        billList.removeAllViews()
        for (bill in bills) {
            val row = layoutInflater.inflate(R.layout.bill_row, billList, false)
            row.findViewById<TextView>(R.id.bill_row_name).text = bill.name
            row.findViewById<TextView>(R.id.bill_row_amount).text = money.format(bill.amount)
            val daysLeft = ChronoUnit.DAYS.between(today, nextDueDate(bill.day, today))
            row.findViewById<TextView>(R.id.bill_row_due).apply {
                text = "${ordinal(bill.day)} · ${dueIn(daysLeft)}"
                setTextColor(if (daysLeft <= 3) warning else secondary)
            }
            row.findViewById<View>(R.id.bill_row_remove).setOnClickListener {
                bills.remove(bill)
                save()
                showBills()
            }
            billList.addView(row)
        }
        billsEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
        billsTotalView.text = "${money.format(bills.sumOf { it.amount })} / month"
        recalculate()
    }

    // Walks from today to the end of the month. Each payday adds a paycheck, and each bill comes out of
    // the pay period it's due in. Today's paycheck (if today is payday) is assumed to already be in the
    // balance; bills due today are assumed not paid yet.
    private fun recalculate() {
        val today = LocalDate.now()
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val nextPayday = today.with(TemporalAdjusters.next(payday))
        val paydays = generateSequence(nextPayday) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(monthEnd) }
            .toList()
        val periodStarts = listOf(today) + paydays
        fun dueThisMonth(bill: Bill) = today.withDayOfMonth(minOf(bill.day, today.lengthOfMonth()))
        fun paycheckOn(date: LocalDate) = paycheckChanges[date] ?: weeklyIncome

        forecastList.removeAllViews()
        var running = balance
        var leftAfterNextPayday = 0.0
        periodStarts.forEachIndexed { i, start ->
            val end = periodStarts.getOrNull(i + 1) ?: monthEnd.plusDays(1)
            val due = bills.filter { val d = dueThisMonth(it); !d.isBefore(start) && d.isBefore(end) }
            val billsDue = due.sumOf { it.amount }
            val isPayday = i > 0
            val paycheck = if (isPayday) paycheckOn(start) else 0.0
            running += paycheck - billsDue
            if (i == 1) leftAfterNextPayday = running

            val row = layoutInflater.inflate(R.layout.forecast_row, forecastList, false)
            row.findViewById<TextView>(R.id.chip_top).text =
                if (isPayday) start.format(DateTimeFormatter.ofPattern("EEE")) else "Today"
            row.findViewById<TextView>(R.id.chip_day).text = start.dayOfMonth.toString()
            if (!isPayday) {
                row.findViewById<View>(R.id.chip).setBackgroundResource(R.drawable.chip_today)
                row.findViewById<TextView>(R.id.chip_top).setTextColor(secondary)
                row.findViewById<TextView>(R.id.chip_day).setTextColor(textColor)
            }
            row.findViewById<TextView>(R.id.forecast_title).text = if (isPayday) "Payday" else "Before payday"
            row.findViewById<TextView>(R.id.forecast_pay).apply {
                if (isPayday) {
                    text = SpannableStringBuilder()
                        .append("+" + money.format(paycheck), ForegroundColorSpan(positive), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        .append(if (start in paycheckChanges) " pay (changed)" else " pay")
                } else {
                    visibility = View.GONE
                }
            }
            row.findViewById<TextView>(R.id.forecast_bills).text = if (due.isEmpty()) {
                "No bills"
            } else {
                SpannableStringBuilder()
                    .append("-" + money.format(billsDue), ForegroundColorSpan(negative), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    .append(" bills · " + due.joinToString { it.name })
            }
            showMoney(row.findViewById(R.id.forecast_left), running)
            if (isPayday) row.setOnClickListener { editPaycheck(start) }
            forecastList.addView(row)
        }

        val monthName = monthEnd.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        endLabel.text = "End of $monthName"
        showMoney(endBalance, running)

        // Summary page
        hero.setBackgroundResource(if (running < 0) R.drawable.hero_red else R.drawable.hero_green)
        heroLabel.text = "End of $monthName"
        heroAmount.text = money.format(running)
        heroNext.text = if (paydays.isEmpty()) {
            "No more paydays this month"
        } else {
            "${money.format(leftAfterNextPayday)} left after bills on ${paydays.first().format(dayFormat)}"
        }
        glancePayday.text = "${nextPayday.format(dayFormat)} · +${money.format(paycheckOn(nextPayday))}"

        val nextBill = bills.minByOrNull { ChronoUnit.DAYS.between(today, nextDueDate(it.day, today)) }
        if (nextBill == null) {
            glanceBill.text = "None"
            glanceBill.setTextColor(secondary)
        } else {
            val daysLeft = ChronoUnit.DAYS.between(today, nextDueDate(nextBill.day, today))
            glanceBill.text = "${nextBill.name} · ${dueIn(daysLeft)}"
            glanceBill.setTextColor(if (daysLeft <= 3) warning else textColor)
        }
        val stillDue = bills.filter { !dueThisMonth(it).isBefore(today) }.sumOf { it.amount }
        glanceDue.text = if (stillDue == 0.0) "All paid" else money.format(stillDue)
    }

    private fun editPaycheck(date: LocalDate) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = plain(weeklyIncome)
            paycheckChanges[date]?.let { setText(plain(it)) }
            setSelectAllOnFocus(true)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Paycheck on ${date.format(dayFormat)}")
            .setMessage("Usually ${money.format(weeklyIncome)}. Enter this week's amount.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val amount = parseMoney(input.text.toString())
                if (amount == null || amount == weeklyIncome) paycheckChanges.remove(date) else paycheckChanges[date] = amount
                save()
                recalculate()
            }
            .setNeutralButton("Use usual") { _, _ ->
                paycheckChanges.remove(date)
                save()
                recalculate()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun nextDueDate(day: Int, today: LocalDate): LocalDate {
        val thisMonth = today.withDayOfMonth(minOf(day, today.lengthOfMonth()))
        if (!thisMonth.isBefore(today)) return thisMonth
        val nextMonth = today.plusMonths(1)
        return nextMonth.withDayOfMonth(minOf(day, nextMonth.lengthOfMonth()))
    }

    private fun dueIn(days: Long) = when (days) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $days days"
    }

    private fun showMoney(view: TextView, amount: Double) {
        view.text = money.format(amount)
        view.setTextColor(if (amount < 0) negative else positive)
    }

    private fun ordinal(n: Int) = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun plain(amount: Double) = amount.toBigDecimal().stripTrailingZeros().toPlainString()

    private fun parseMoney(text: String): Double? = text.replace(Regex("[^0-9.-]"), "").toDoubleOrNull()

    private fun load() {
        balance = prefs.getString("balance", null)?.toDoubleOrNull() ?: 0.0
        weeklyIncome = prefs.getString("weekly_income", null)?.toDoubleOrNull() ?: 0.0
        payday = DayOfWeek.of(prefs.getInt("payday", DayOfWeek.FRIDAY.value))
        val saved = JSONArray(prefs.getString("bills", "[]"))
        for (i in 0 until saved.length()) {
            val bill = saved.getJSONObject(i)
            bills.add(Bill(bill.getString("name"), bill.getDouble("amount"), bill.optInt("day", 1)))
        }
        bills.sortBy { it.day }

        val today = LocalDate.now()
        val changes = JSONObject(prefs.getString("paycheck_changes", "{}"))
        for (key in changes.keys()) {
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
            if (!date.isBefore(today)) paycheckChanges[date] = changes.getDouble(key) // drop past weeks
        }
    }

    private fun save() {
        val saved = JSONArray()
        for (bill in bills) {
            saved.put(JSONObject().put("name", bill.name).put("amount", bill.amount).put("day", bill.day))
        }
        val changes = JSONObject()
        for ((date, amount) in paycheckChanges) changes.put(date.toString(), amount)
        prefs.edit()
            .putString("balance", balance.toString())
            .putString("weekly_income", weeklyIncome.toString())
            .putInt("payday", payday.value)
            .putString("bills", saved.toString())
            .putString("paycheck_changes", changes.toString())
            .apply()
    }

    // Looks at the latest GitHub Release and shows an "Update now" button if it's newer than this build.
    private fun checkForUpdate() {
        val current = BuildConfig.VERSION_NAME
        updateStatus.text = "Checking for updates…"
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
                    result.isFailure -> updateStatus.text = "Couldn't check for updates (v$current)"
                    update == null -> updateStatus.text = "Up to date (v$current)"
                    else -> {
                        updateUrl = update.second
                        updateStatus.text = "v${update.first} is available"
                        updateButton.visibility = View.VISIBLE
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
            Toast.makeText(this, "Allow Budget to install updates, then tap Update now again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        updateButton.isEnabled = false
        updateStatus.text = "Downloading update…"
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
                updateStatus.text = result.exceptionOrNull()?.let { "Update failed: ${it.message}" } ?: "Installing…"
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
