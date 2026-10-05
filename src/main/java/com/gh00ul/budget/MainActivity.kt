package com.gh00ul.budget

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.concurrent.thread

private const val REPO = "gh00ul/budget"

class MainActivity : Activity() {
    // day = day of the month the bill is due (1-31).
    private class Bill(val name: String, val amount: Double, val day: Int)

    private val prefs by lazy { getSharedPreferences("budget", MODE_PRIVATE) }
    private val money = NumberFormat.getCurrencyInstance()
    private val bills = mutableListOf<Bill>()
    private var balance = 0.0
    private var weeklyIncome = 0.0
    private var payday = DayOfWeek.FRIDAY
    private var updateUrl: String? = null

    private lateinit var billList: LinearLayout
    private lateinit var billsTotal: TextView
    private lateinit var forecastList: LinearLayout
    private lateinit var endLabel: TextView
    private lateinit var endBalance: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitToSystemBars()

        billList = findViewById(R.id.bill_list)
        billsTotal = findViewById(R.id.bills_total)
        forecastList = findViewById(R.id.forecast_list)
        endLabel = findViewById(R.id.end_label)
        endBalance = findViewById(R.id.end_balance)
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
        updateButton.setOnClickListener { installUpdate() }

        showBills()
        checkForUpdate()
    }

    // Android 15+ draws the app behind the status/navigation bars, so pad the content clear of them.
    private fun fitToSystemBars() {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        val root = findViewById<View>(R.id.root)
        val pad = root.paddingTop
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
    }

    private fun bindMoneyField(id: Int, initial: Double, onChange: (Double) -> Unit) {
        val box = findViewById<EditText>(id)
        if (initial != 0.0) box.setText(initial.toBigDecimal().stripTrailingZeros().toPlainString())
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
        billList.removeAllViews()
        for (bill in bills) {
            val row = layoutInflater.inflate(R.layout.bill_row, billList, false)
            row.findViewById<TextView>(R.id.bill_row_name).text = bill.name
            row.findViewById<TextView>(R.id.bill_row_due).text = "Due the ${ordinal(bill.day)}"
            row.findViewById<TextView>(R.id.bill_row_amount).text = money.format(bill.amount)
            row.findViewById<View>(R.id.bill_row_remove).setOnClickListener {
                bills.remove(bill)
                save()
                showBills()
            }
            billList.addView(row)
        }
        billsTotal.text = "${money.format(bills.sumOf { it.amount })} / month"
        recalculate()
    }

    // Walks from today to the end of the month. Each payday adds a paycheck, and each bill comes out of
    // the pay period it's due in. Today's paycheck (if today is payday) is assumed to already be in the
    // balance; bills due today are assumed not paid yet.
    private fun recalculate() {
        val today = LocalDate.now()
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val paydays = generateSequence(today.with(TemporalAdjusters.next(payday))) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(monthEnd) }
            .toList()
        val periodStarts = listOf(today) + paydays

        forecastList.removeAllViews()
        var running = balance
        periodStarts.forEachIndexed { i, start ->
            val end = periodStarts.getOrNull(i + 1) ?: monthEnd.plusDays(1)
            val due = bills.filter {
                val dueDate = today.withDayOfMonth(minOf(it.day, today.lengthOfMonth()))
                !dueDate.isBefore(start) && dueDate.isBefore(end)
            }
            val isPayday = i > 0
            if (isPayday) running += weeklyIncome
            running -= due.sumOf { it.amount }

            val row = layoutInflater.inflate(R.layout.forecast_row, forecastList, false)
            row.findViewById<TextView>(R.id.forecast_title).text =
                if (isPayday) "Payday · ${start.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}" else "Today"
            row.findViewById<TextView>(R.id.forecast_detail).text = buildString {
                if (isPayday) append("+${money.format(weeklyIncome)} · ")
                append(if (due.isEmpty()) "No bills due" else "Pays " + due.joinToString { "${it.name} ${money.format(it.amount)}" })
            }
            showMoney(row.findViewById(R.id.forecast_amount), running)
            forecastList.addView(row)
        }

        endLabel.text = "End of month (${monthEnd.format(DateTimeFormatter.ofPattern("MMM d"))})"
        showMoney(endBalance, running)
    }

    private fun showMoney(view: TextView, amount: Double) {
        view.text = money.format(amount)
        view.setTextColor(if (amount < 0) 0xFFC62828.toInt() else 0xFF2E7D32.toInt())
    }

    private fun ordinal(n: Int) = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

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
    }

    private fun save() {
        val saved = JSONArray()
        for (bill in bills) {
            saved.put(JSONObject().put("name", bill.name).put("amount", bill.amount).put("day", bill.day))
        }
        prefs.edit()
            .putString("balance", balance.toString())
            .putString("weekly_income", weeklyIncome.toString())
            .putInt("payday", payday.value)
            .putString("bills", saved.toString())
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
