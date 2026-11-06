package com.keyhub.test

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val baseUrl = "http://127.0.0.1:${ProxyService.PORT}/v1"

    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var proxyBtn: Button
    private lateinit var proxyStatus: TextView
    private lateinit var universalView: TextView
    private lateinit var balanceView: TextView
    private lateinit var totalsView: TextView
    private lateinit var historyBox: LinearLayout

    private val ticker = object : Runnable {
        override fun run() {
            refreshProxy()
            refreshHistory()
            ui.postDelayed(this, 2000)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun body(t: String = "") = TextView(this).apply {
        text = t
        textSize = 14f
        setTextIsSelectable(true)
    }

    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { f() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it) }
    }

    private fun copy(text: String, what: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(what, text))
        Toast.makeText(this, "$what copied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "KeyHub (test) - OpenRouter"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })

        // 1. real key
        root.addView(header("1. Your OpenRouter API key"))
        keyInput = EditText(this).apply {
            hint = "sk-or-v1-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        keyStatus = body()
        root.addView(keyInput)
        root.addView(row(button("Save key") {
            val k = keyInput.text.toString().trim()
            if (k.isEmpty()) {
                Toast.makeText(this, "Paste a key first", Toast.LENGTH_SHORT).show()
            } else {
                Secrets.saveRealKey(this, k)
                keyInput.setText("")
                refreshKeyStatus()
                refreshBalance()
            }
        }))
        root.addView(keyStatus)

        // 2. proxy
        root.addView(header("2. Proxy"))
        proxyBtn = button("Start proxy") {
            val i = Intent(this, ProxyService::class.java)
            if (ProxyService.running) stopService(i) else startForegroundService(i)
            ui.postDelayed({ refreshProxy() }, 600)
        }
        proxyStatus = body()
        root.addView(proxyBtn)
        root.addView(proxyStatus)

        // 3. use in other apps
        root.addView(header("3. Use in your other AI apps"))
        root.addView(body("Base URL:"))
        root.addView(body(baseUrl))
        root.addView(row(button("Copy URL") { copy(baseUrl, "URL") }))
        root.addView(body("API key:"))
        universalView = body()
        root.addView(universalView)
        root.addView(row(
            button("Copy key") { copy(Secrets.universalKey(this), "Key") },
            button("New key") {
                Secrets.regenerateUniversal(this)
                refreshUniversal()
                Toast.makeText(this, "New key made - update your other apps", Toast.LENGTH_LONG).show()
            }
        ))

        // 4. balance
        root.addView(header("4. OpenRouter balance"))
        balanceView = body()
        root.addView(balanceView)
        root.addView(row(button("Refresh balance") { refreshBalance() }))

        // 5. history
        root.addView(header("5. Usage through KeyHub"))
        totalsView = body()
        root.addView(totalsView)
        root.addView(row(button("Clear history") {
            thread {
                UsageDb.get(this).clear()
                runOnUiThread { refreshHistory() }
            }
        }))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyBox)
    }

    override fun onResume() {
        super.onResume()
        refreshKeyStatus()
        refreshUniversal()
        refreshBalance()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refreshKeyStatus() {
        val k = Secrets.realKey(this)
        keyStatus.text = if (k == null) "No key saved" else "Saved: ${k.take(8)}...${k.takeLast(4)}"
    }

    private fun refreshUniversal() {
        universalView.text = Secrets.universalKey(this)
    }

    private fun refreshProxy() {
        if (ProxyService.running) {
            proxyBtn.text = "Stop proxy"
            proxyStatus.text = "Running on 127.0.0.1:${ProxyService.PORT}"
        } else {
            proxyBtn.text = "Start proxy"
            proxyStatus.text = ProxyService.lastError?.let { "Stopped ($it)" } ?: "Stopped"
        }
    }

    private fun refreshHistory() {
        thread {
            val db = UsageDb.get(this)
            val t = db.totals()
            val rows = db.recent(50)
            runOnUiThread {
                totalsView.text = "Requests: ${t.first}   Tokens: ${t.second}   Cost: ${usd(t.third)}"
                historyBox.removeAllViews()
                val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                for (r in rows) {
                    historyBox.addView(TextView(this).apply {
                        textSize = 12f
                        typeface = Typeface.MONOSPACE
                        setPadding(0, dp(6), 0, dp(6))
                        text = "${fmt.format(Date(r.ts))}  [${r.status}]\n" +
                            "${r.model.ifEmpty { "?" }}\n" +
                            "in ${r.prompt} / out ${r.completion} / total ${r.total} tok   ${usd(r.cost)}"
                    })
                }
            }
        }
    }

    private fun usd(v: Double?): String =
        if (v == null) "-" else "\$" + String.format(Locale.US, "%.5f", v)

    private fun JSONObject.dbl(k: String): Double? =
        if (has(k) && !isNull(k)) getDouble(k) else null

    private fun getJson(url: String, key: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $key")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        val code = c.responseCode
        val s = (if (code >= 400) c.errorStream else c.inputStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code >= 400) throw Exception("HTTP $code")
        return JSONObject(s)
    }

    private fun refreshBalance() {
        val key = Secrets.realKey(this)
        if (key == null) {
            balanceView.text = "Save your OpenRouter key first."
            return
        }
        balanceView.text = "Loading..."
        thread {
            val sb = StringBuilder()
            try {
                val d = getJson("https://openrouter.ai/api/v1/key", key).getJSONObject("data")
                val limit = d.dbl("limit")
                val remaining = d.dbl("limit_remaining")
                sb.append("This key\n")
                sb.append("  Used total: ${usd(d.dbl("usage"))}\n")
                sb.append("  Today: ${usd(d.dbl("usage_daily"))}\n")
                sb.append("  This week: ${usd(d.dbl("usage_weekly"))}\n")
                sb.append("  This month: ${usd(d.dbl("usage_monthly"))}\n")
                if (limit != null) {
                    sb.append("  Limit: ${usd(limit)}   Left: ${usd(remaining)}\n")
                } else {
                    sb.append("  No spending limit set on this key\n")
                }
            } catch (e: Exception) {
                sb.append("Could not load key info: ${e.message}\n")
            }
            try {
                val d = getJson("https://openrouter.ai/api/v1/credits", key).getJSONObject("data")
                val total = d.dbl("total_credits")
                val used = d.dbl("total_usage")
                sb.append("\nAccount credits\n")
                sb.append("  Bought: ${usd(total)}   Used: ${usd(used)}\n")
                if (total != null && used != null) sb.append("  Left: ${usd(total - used)}\n")
            } catch (e: Exception) {
                sb.append("\nAccount credits: not available with this key")
            }
            runOnUiThread { balanceView.text = sb.toString().trim() }
        }
    }
}
