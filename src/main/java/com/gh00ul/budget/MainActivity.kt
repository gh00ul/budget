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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import kotlin.concurrent.thread

private const val REPO = "gh00ul/budget"

class MainActivity : Activity() {
    private class Bill(val name: String, val amount: Double)

    private val prefs by lazy { getSharedPreferences("budget", MODE_PRIVATE) }
    private val money = NumberFormat.getCurrencyInstance()
    private val bills = mutableListOf<Bill>()
    private var income = 0.0
    private var updateUrl: String? = null

    private lateinit var billList: LinearLayout
    private lateinit var totalView: TextView
    private lateinit var leftView: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitToSystemBars()

        billList = findViewById(R.id.bill_list)
        totalView = findViewById(R.id.total)
        leftView = findViewById(R.id.left_over)
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update_button)
        val incomeBox = findViewById<EditText>(R.id.income)
        val nameBox = findViewById<EditText>(R.id.bill_name)
        val amountBox = findViewById<EditText>(R.id.bill_amount)

        load()
        if (income != 0.0) incomeBox.setText(income.toBigDecimal().stripTrailingZeros().toPlainString())
        incomeBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                income = parseMoney(s.toString()) ?: 0.0
                save()
                recalculate()
            }
        })

        fun addBill() {
            val name = nameBox.text.toString().trim()
            val amount = parseMoney(amountBox.text.toString())
            when {
                name.isEmpty() -> nameBox.requestFocus()
                amount == null -> amountBox.requestFocus()
                else -> {
                    bills.add(Bill(name, amount))
                    save()
                    showBills()
                    nameBox.text.clear()
                    amountBox.text.clear()
                    nameBox.requestFocus()
                }
            }
        }
        findViewById<Button>(R.id.add_button).setOnClickListener { addBill() }
        amountBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addBill(); true } else false
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

    private fun showBills() {
        billList.removeAllViews()
        for (bill in bills) {
            val row = layoutInflater.inflate(R.layout.bill_row, billList, false)
            row.findViewById<TextView>(R.id.bill_row_name).text = bill.name
            row.findViewById<TextView>(R.id.bill_row_amount).text = money.format(bill.amount)
            row.findViewById<View>(R.id.bill_row_remove).setOnClickListener {
                bills.remove(bill)
                save()
                showBills()
            }
            billList.addView(row)
        }
        recalculate()
    }

    private fun recalculate() {
        val total = bills.sumOf { it.amount }
        val left = income - total
        totalView.text = money.format(total)
        leftView.text = money.format(left)
        leftView.setTextColor(if (left < 0) 0xFFC62828.toInt() else 0xFF2E7D32.toInt())
    }

    private fun parseMoney(text: String): Double? = text.replace(Regex("[^0-9.]"), "").toDoubleOrNull()

    private fun load() {
        income = prefs.getString("income", null)?.toDoubleOrNull() ?: 0.0
        val saved = JSONArray(prefs.getString("bills", "[]"))
        for (i in 0 until saved.length()) {
            val bill = saved.getJSONObject(i)
            bills.add(Bill(bill.getString("name"), bill.getDouble("amount")))
        }
    }

    private fun save() {
        val saved = JSONArray()
        for (bill in bills) saved.put(JSONObject().put("name", bill.name).put("amount", bill.amount))
        prefs.edit().putString("income", income.toString()).putString("bills", saved.toString()).apply()
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
