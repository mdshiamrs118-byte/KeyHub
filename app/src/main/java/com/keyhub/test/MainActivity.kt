package com.keyhub.test

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class ModelInfo(
    val id: String,
    val name: String,
    val ctx: Int,
    val promptPer1M: Double,
    val compPer1M: Double
) {
    val free: Boolean get() = promptPer1M == 0.0 && compPer1M == 0.0
}

class RowAdapter<T>(private val build: (T) -> View) : BaseAdapter() {
    var items: List<T> = emptyList()
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any? = items[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
        build(items[position])
}

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val baseUrl = "http://127.0.0.1:${ProxyService.PORT}/v1"
    private val baseUrlGemini = "http://127.0.0.1:${ProxyService.PORT}/gemini/v1"
    private val providers = listOf("openrouter" to "OpenRouter", "gemini" to "Gemini")
    private val geminiNoPrice = -2.0

    // shell
    private lateinit var frame: FrameLayout
    private lateinit var statusChip: TextView
    private val tabViews = ArrayList<View>()
    private val tabButtons = ArrayList<TextView>()
    private val tabNames = listOf("Home", "Keys", "History", "Models")
    private var currentTab = 0

    // home
    private lateinit var proxyInfo: TextView
    private lateinit var proxySub: TextView
    private lateinit var proxyToggle: TextView
    private lateinit var universalView: TextView
    private lateinit var activeOr: TextView
    private lateinit var subOr: TextView
    private lateinit var activeGem: TextView
    private lateinit var subGem: TextView
    private lateinit var todayReq: TextView
    private lateinit var todayTok: TextView
    private lateinit var todayCost: TextView

    // keys
    private lateinit var keysBox: LinearLayout
    private val balances = ConcurrentHashMap<String, String>()
    private var keySig = ""

    // history
    private lateinit var histList: ListView
    private lateinit var histEmpty: TextView
    private lateinit var histReq: TextView
    private lateinit var histTok: TextView
    private lateinit var histCost: TextView
    private lateinit var histFail: TextView
    private val histAdapter = RowAdapter<UsageRow> { r -> historyRow(r) }
    private val histPills = ArrayList<TextView>()
    private var histFilter = 0 // 0 all, 1 today, 2 errors

    // models
    private lateinit var modelList: ListView
    private lateinit var modelStatus: TextView
    private lateinit var modelSearch: EditText
    private val modelAdapter = RowAdapter<ModelInfo> { m -> modelRow(m) }
    private val provPills = ArrayList<TextView>()
    private val modelPills = ArrayList<TextView>()
    private var orModels: List<ModelInfo> = emptyList()
    private var gemModels: List<ModelInfo> = emptyList()
    private var orLoading = false
    private var gemLoading = false
    private var modelProvider = 0 // 0 openrouter, 1 gemini
    private var freeOnly = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshHeader()
            when (currentTab) {
                0 -> refreshHome()
                1 -> checkKeySig()
                2 -> refreshHistory()
            }
            ui.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.bg
        window.navigationBarColor = C.card

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.bg)
        }

        val header = hrow().apply { setPadding(dp(20), dp(16), dp(20), dp(8)) }
        header.addView(tv("KeyHub", 26f, bold = true), lp(0, WRAP, weight = 1f))
        statusChip = chip("Stopped", C.muted)
        header.addView(statusChip)
        root.addView(header)

        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.card)
        }
        for ((i, name) in tabNames.withIndex()) {
            val b = tv(name, 13f, C.muted, bold = true)
            b.gravity = android.view.Gravity.CENTER
            b.setPadding(0, dp(16), 0, dp(16))
            b.setOnClickListener { selectTab(i) }
            tabButtons.add(b)
            bar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(bar)

        tabViews.add(buildHome())
        tabViews.add(buildKeys())
        tabViews.add(buildHistory())
        tabViews.add(buildModels())
        for (v in tabViews) frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        selectTab(0)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun selectTab(i: Int) {
        currentTab = i
        for ((n, v) in tabViews.withIndex()) {
            v.visibility = if (n == i) View.VISIBLE else View.GONE
        }
        for ((n, b) in tabButtons.withIndex()) {
            b.setTextColor(if (n == i) C.accent else C.muted)
        }
        refreshHeader()
        when (i) {
            0 -> refreshHome()
            1 -> {
                rebuildKeys()
                fetchBalances()
            }
            2 -> refreshHistory()
            3 -> if (currentModels().isEmpty()) loadModels()
        }
    }

    private fun pageColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(24))
    }

    private fun scroll(col: LinearLayout): ScrollView = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(col)
    }

    private fun copy(text: String, what: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(what, text))
        Toast.makeText(this, "$what copied", Toast.LENGTH_SHORT).show()
    }

    private fun setChip(v: TextView, t: String, color: Int) {
        v.text = t
        v.setTextColor(color)
        v.background = rounded(withAlpha(color, 40), 12)
    }

    private fun usd(v: Double?): String =
        if (v == null) "-" else "\$" + String.format(Locale.US, "%.5f", v)

    private fun JSONObject.dbl(k: String): Double? =
        if (has(k) && !isNull(k)) getDouble(k) else null

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun statCol(valueView: TextView, label: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(valueView)
            addView(tv(label, 12f, C.muted))
        }

    private fun activeKeyId(keys: List<ApiKey>, pid: String): String? {
        val enabled = keys.filter { it.provider == pid && it.enabled }
        val now = System.currentTimeMillis()
        val a = ProxyService.activeKeys[pid]
        return enabled.firstOrNull { it.id == a }?.id
            ?: enabled.firstOrNull { (ProxyService.cooldownUntil[it.id] ?: 0L) <= now }?.id
            ?: enabled.firstOrNull()?.id
    }

    private fun keyStatus(k: ApiKey, activeId: String?): Pair<String, Int> {
        if (!k.enabled) return Pair("Disabled", C.muted)
        val until = ProxyService.cooldownUntil[k.id] ?: 0L
        if (until > System.currentTimeMillis()) {
            val code = ProxyService.lastFailCode[k.id]
            return Pair("Cooling down" + (if (code != null) " - $code" else ""), C.amber)
        }
        if (k.id == activeId) return Pair("Active", C.green)
        return Pair("Ready", C.accent)
    }

    // ------------------------------------------------------------------ header

    private fun refreshHeader() {
        when {
            ProxyService.running -> setChip(statusChip, "Running", C.green)
            ProxyService.lastError != null -> setChip(statusChip, "Error", C.red)
            else -> setChip(statusChip, "Stopped", C.muted)
        }
    }

    // ------------------------------------------------------------------ HOME

    private fun buildHome(): View {
        val col = pageColumn()

        val c1 = card()
        c1.addView(tv("PROXY", 11f, C.muted, bold = true))
        proxyInfo = tv("Stopped", 20f, bold = true)
        c1.addView(proxyInfo, lp(top = 4))
        proxySub = tv("", 13f, C.muted)
        c1.addView(proxySub, lp(top = 2))
        proxyToggle = pill("Start proxy", C.accent, Color.BLACK) { toggleProxy() }
        c1.addView(proxyToggle, lp(top = 14))
        col.addView(c1)

        val c2 = card()
        c2.addView(tv("CONNECT YOUR APPS", 11f, C.muted, bold = true))
        c2.addView(tv("Base URL", 12f, C.muted), lp(top = 10))
        val r1 = hrow()
        r1.addView(tv(baseUrl, 13f, mono = true), lp(0, WRAP, weight = 1f))
        r1.addView(pill("Copy") { copy(baseUrl, "URL") })
        c2.addView(r1, lp(top = 4))
        c2.addView(tv("API key", 12f, C.muted), lp(top = 12))
        universalView = tv("", 13f, mono = true)
        val r2 = hrow()
        r2.addView(universalView, lp(0, WRAP, weight = 1f))
        r2.addView(pill("Copy") { copy(Secrets.universalKey(this), "Key") })
        c2.addView(r2, lp(top = 4))
        c2.addView(pill("Generate new key") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Generate new key?")
                .setMessage("Your other apps will stop working until you paste the new key into them.")
                .setPositiveButton("Generate") { _, _ ->
                    Secrets.regenerateUniversal(this)
                    refreshHome()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, lp(top = 12))
        c2.addView(
            tv(
                "Model names starting with gemini use your Gemini keys. Everything else uses OpenRouter.",
                12f, C.muted
            ),
            lp(top = 14)
        )
        c2.addView(tv("Gemini-only URL (for apps with a Gemini mode)", 12f, C.muted), lp(top = 12))
        val r3 = hrow()
        r3.addView(tv(baseUrlGemini, 13f, mono = true), lp(0, WRAP, weight = 1f))
        r3.addView(pill("Copy") { copy(baseUrlGemini, "URL") })
        c2.addView(r3, lp(top = 4))
        col.addView(c2)

        val c3 = card()
        c3.addView(tv("KEYS IN USE", 11f, C.muted, bold = true))
        c3.addView(tv("OpenRouter", 12f, C.muted), lp(top = 10))
        activeOr = tv("-", 18f, bold = true)
        c3.addView(activeOr, lp(top = 2))
        subOr = tv("", 12f, C.muted)
        c3.addView(subOr, lp(top = 2))
        c3.addView(tv("Gemini", 12f, C.muted), lp(top = 14))
        activeGem = tv("-", 18f, bold = true)
        c3.addView(activeGem, lp(top = 2))
        subGem = tv("", 12f, C.muted)
        c3.addView(subGem, lp(top = 2))
        col.addView(c3)

        val c4 = card()
        c4.addView(tv("TODAY", 11f, C.muted, bold = true))
        todayReq = tv("0", 20f, bold = true)
        todayTok = tv("0", 20f, bold = true)
        todayCost = tv("\$0", 20f, C.accent, bold = true)
        val sr = hrow()
        sr.addView(statCol(todayReq, "Requests"), lp(0, WRAP, weight = 1f))
        sr.addView(statCol(todayTok, "Tokens"), lp(0, WRAP, weight = 1f))
        sr.addView(statCol(todayCost, "Cost"), lp(0, WRAP, weight = 1f))
        c4.addView(sr, lp(top = 8))
        c4.addView(tv("Cost covers OpenRouter only. Gemini does not report cost.", 11f, C.muted), lp(top = 8))
        col.addView(c4)

        val c5 = card()
        c5.addView(tv("KEEP IT RUNNING", 11f, C.muted, bold = true))
        c5.addView(
            tv("If Android stops the proxy when the screen is off, set KeyHub to Not optimized.", 13f, C.muted),
            lp(top = 4)
        )
        c5.addView(pill("Open battery settings") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
            }
        }, lp(top = 10))
        col.addView(c5)

        return scroll(col)
    }

    private fun toggleProxy() {
        val i = Intent(this, ProxyService::class.java)
        if (ProxyService.running) stopService(i) else startForegroundService(i)
        ui.postDelayed({
            refreshHeader()
            refreshHome()
        }, 600)
    }

    private fun describeProvider(keys: List<ApiKey>, pid: String, label: TextView, sub: TextView) {
        val group = keys.filter { it.provider == pid }
        val enabled = group.filter { it.enabled }
        val aid = activeKeyId(keys, pid)
        label.text = group.firstOrNull { it.id == aid }?.label ?: "No key added"
        val now = System.currentTimeMillis()
        val cooling = enabled.count { (ProxyService.cooldownUntil[it.id] ?: 0L) > now }
        sub.text = when {
            group.isEmpty() -> "Add one in the Keys tab"
            enabled.size < 2 -> "Add a second key to enable failover"
            cooling > 0 -> "${enabled.size} keys enabled - $cooling cooling down - failover on"
            else -> "${enabled.size} keys enabled - failover on"
        }
    }

    private fun refreshHome() {
        if (ProxyService.running) {
            proxyInfo.text = "Running"
            proxySub.text = "Listening on 127.0.0.1:${ProxyService.PORT}"
            proxyToggle.text = "Stop proxy"
            stylePill(proxyToggle, withAlpha(C.red, 60), C.red)
        } else {
            proxyInfo.text = "Stopped"
            proxySub.text = ProxyService.lastError ?: "Tap start to begin"
            proxyToggle.text = "Start proxy"
            stylePill(proxyToggle, C.accent, Color.BLACK)
        }
        universalView.text = Secrets.universalKey(this)

        val keys = Secrets.keys(this)
        describeProvider(keys, "openrouter", activeOr, subOr)
        describeProvider(keys, "gemini", activeGem, subGem)

        thread {
            val t = UsageDb.get(this).totals(startOfToday())
            runOnUiThread {
                todayReq.text = t.ok.toString()
                todayTok.text = t.tokens.toString()
                todayCost.text = usd(t.cost)
            }
        }
    }

    // ------------------------------------------------------------------ KEYS

    private fun buildKeys(): View {
        val col = pageColumn()
        val head = hrow()
        head.addView(tv("Your keys", 18f, bold = true), lp(0, WRAP, weight = 1f))
        head.addView(pill("Add key", C.accent, Color.BLACK) { showAddKey() })
        col.addView(head, lp(bottom = 6))
        col.addView(
            tv(
                "One list for all your keys. A Gemini model uses the first enabled Gemini key " +
                    "in this list, any other model uses the first enabled OpenRouter key. If a key " +
                    "is rejected (invalid, out of credit, rate limited) KeyHub tries the next key " +
                    "of the same provider.",
                12f, C.muted
            ),
            lp(bottom = 12)
        )
        keysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(keysBox)
        col.addView(pill("Refresh balances") { fetchBalances() }, lp(top = 4))
        return scroll(col)
    }

    private fun computeKeySig(): String {
        val keys = Secrets.keys(this)
        return keys.joinToString("|") { k ->
            k.id + k.label + k.enabled + k.provider +
                keyStatus(k, activeKeyId(keys, k.provider)).first
        }
    }

    private fun checkKeySig() {
        if (computeKeySig() != keySig) rebuildKeys()
    }

    private fun rebuildKeys() {
        thread {
            val stats = UsageDb.get(this).perKey()
            runOnUiThread { renderKeys(stats) }
        }
    }

    private fun renderKeys(stats: Map<String, Totals>) {
        val keys = Secrets.keys(this)
        keysBox.removeAllViews()
        keySig = computeKeySig()
        if (keys.isEmpty()) {
            val c = card()
            c.addView(tv("No keys yet. Tap Add key to start.", 14f, C.muted))
            keysBox.addView(c)
            return
        }
        for ((i, k) in keys.withIndex()) {
            keysBox.addView(keyCard(k, i, keys.size, activeKeyId(keys, k.provider), stats[k.id]))
        }
    }

    private fun provColor(pid: String): Int =
        if (pid == "gemini") Color.parseColor("#4FC3F7") else Color.parseColor("#B28DFF")

    private fun keyCard(k: ApiKey, index: Int, count: Int, activeId: String?, st: Totals?): View {
        val c = card()
        val (statusText, statusColor) = keyStatus(k, activeId)

        val top = hrow()
        top.addView(tv(k.label, 16f, bold = true), lp(0, WRAP, weight = 1f))
        top.addView(chip(if (k.provider == "gemini") "Gemini" else "OpenRouter", provColor(k.provider)))
        top.addView(chip(statusText, statusColor), lp(start = 6))
        c.addView(top)

        val real = Secrets.keyValue(k)
        val masked = if (real == null) "unreadable" else real.take(8) + "..." + real.takeLast(4)
        c.addView(tv(masked, 12f, C.muted, mono = true), lp(top = 4))

        val defaultInfo = if (k.provider == "gemini") {
            "Gemini does not report balance. Tap Refresh to check the key is valid."
        } else {
            "Balance: tap Refresh balances"
        }
        c.addView(tv(balances[k.id] ?: defaultInfo, 13f), lp(top = 10))

        val via = if (st == null) {
            "Through KeyHub: nothing yet"
        } else {
            val costPart = if (k.provider == "gemini") "" else " - ${usd(st.cost)}"
            "Through KeyHub: ${st.ok} requests - ${st.tokens} tokens$costPart" +
                (if (st.failed > 0) " - ${st.failed} failed" else "")
        }
        c.addView(tv(via, 12f, C.muted), lp(top = 4))

        val a = hrow()
        val sw = Switch(this)
        sw.isChecked = k.enabled
        sw.setOnCheckedChangeListener { _, on ->
            Secrets.setEnabled(this, k.id, on)
            rebuildKeys()
        }
        a.addView(tv("Enabled", 13f, C.muted), lp(0, WRAP, weight = 1f))
        a.addView(sw)
        c.addView(a, lp(top = 12))

        val b = hrow()
        if (index > 0) {
            b.addView(pill("Up") {
                Secrets.move(this, k.id, -1)
                rebuildKeys()
            })
        }
        if (index < count - 1) {
            b.addView(pill("Down") {
                Secrets.move(this, k.id, 1)
                rebuildKeys()
            }, lp(start = if (index > 0) 8 else 0))
        }
        b.addView(View(this), lp(0, 1, weight = 1f))
        b.addView(pill("Rename") { showRename(k) })
        b.addView(pill("Delete", withAlpha(C.red, 60), C.red) { confirmDelete(k) }, lp(start = 8))
        c.addView(b, lp(top = 10))
        return c
    }

    private fun showAddKey() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val sel = arrayOf("openrouter")
        val label = field("Label (e.g. Main, Backup)")
        val key = field("Paste the key", password = true)
        val pills = ArrayList<TextView>()
        fun styleSel() {
            for ((i, pair) in providers.withIndex()) {
                if (sel[0] == pair.first) stylePill(pills[i], C.accent, Color.BLACK)
                else stylePill(pills[i], C.card2, C.text)
            }
        }
        val pr = hrow()
        for ((i, pair) in providers.withIndex()) {
            val p = pill(pair.second) {
                sel[0] = pair.first
                styleSel()
            }
            pills.add(p)
            pr.addView(p, lp(start = if (i > 0) 8 else 0))
        }
        styleSel()
        key.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString()?.trim() ?: ""
                if (t.startsWith("AIza")) {
                    sel[0] = "gemini"
                    styleSel()
                } else if (t.startsWith("sk-or-")) {
                    sel[0] = "openrouter"
                    styleSel()
                }
            }

            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        box.addView(label, lp(bottom = 10))
        box.addView(key, lp(bottom = 14))
        box.addView(tv("Provider (detected from the key when possible)", 12f, C.muted), lp(bottom = 6))
        box.addView(pr)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Add key")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val kv = key.text.toString().trim()
                if (kv.isNotEmpty()) {
                    Secrets.addKey(this, label.text.toString(), kv, sel[0])
                    rebuildKeys()
                    fetchBalances()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRename(k: ApiKey) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val label = field("Label")
        label.setText(k.label)
        box.addView(label)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Rename key")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Secrets.rename(this, k.id, label.text.toString())
                rebuildKeys()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(k: ApiKey) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Delete ${k.label}?")
            .setMessage("The key is removed from KeyHub. It stays valid with the provider.")
            .setPositiveButton("Delete") { _, _ ->
                Secrets.removeKey(this, k.id)
                balances.remove(k.id)
                rebuildKeys()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getJson(url: String, headerName: String?, headerValue: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        if (headerName != null && headerValue != null) c.setRequestProperty(headerName, headerValue)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val code = c.responseCode
        val s = (if (code >= 400) c.errorStream else c.inputStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code >= 400) throw Exception("HTTP $code")
        return JSONObject(s)
    }

    private fun httpCode(url: String, headerName: String, headerValue: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty(headerName, headerValue)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val code = c.responseCode
        c.disconnect()
        return code
    }

    private fun fetchBalances() {
        val keys = Secrets.keys(this)
        if (keys.isEmpty()) return
        thread {
            for (k in keys) {
                val real = Secrets.keyValue(k) ?: continue
                if (k.provider == "gemini") {
                    balances[k.id] = try {
                        val code = httpCode(
                            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1",
                            "x-goog-api-key", real
                        )
                        if (code == 200) "Key is valid. Gemini does not report balance."
                        else "Key rejected (HTTP $code)"
                    } catch (e: Exception) {
                        "Could not check key (${e.message})"
                    }
                    continue
                }
                val sb = StringBuilder()
                try {
                    val d = getJson("https://openrouter.ai/api/v1/key", "Authorization", "Bearer $real")
                        .getJSONObject("data")
                    val used = d.dbl("usage")
                    val limit = d.dbl("limit")
                    val rem = d.dbl("limit_remaining")
                    if (limit != null) {
                        sb.append("Used ${usd(used)} - Left ${usd(rem)} of ${usd(limit)}")
                    } else {
                        sb.append("Used ${usd(used)} - no spending limit")
                    }
                } catch (e: Exception) {
                    sb.append("Could not check key (${e.message})")
                }
                try {
                    val d = getJson("https://openrouter.ai/api/v1/credits", "Authorization", "Bearer $real")
                        .getJSONObject("data")
                    val total = d.dbl("total_credits")
                    val used = d.dbl("total_usage")
                    if (total != null && used != null) {
                        sb.append("\nAccount credits left: ${usd(total - used)}")
                    }
                } catch (_: Exception) {
                }
                balances[k.id] = sb.toString()
            }
            runOnUiThread { rebuildKeys() }
        }
    }

    // ------------------------------------------------------------------ HISTORY

    private fun buildHistory(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val sc = card(10)
        histReq = tv("0", 18f, bold = true)
        histTok = tv("0", 18f, bold = true)
        histCost = tv("\$0", 18f, C.accent, bold = true)
        histFail = tv("0", 18f, C.red, bold = true)
        val sr = hrow()
        sr.addView(statCol(histReq, "Requests"), lp(0, WRAP, weight = 1f))
        sr.addView(statCol(histTok, "Tokens"), lp(0, WRAP, weight = 1f))
        sr.addView(statCol(histCost, "Cost"), lp(0, WRAP, weight = 1f))
        sr.addView(statCol(histFail, "Failed"), lp(0, WRAP, weight = 1f))
        sc.addView(sr)
        top.addView(sc)

        val fr = hrow()
        val names = listOf("All", "Today", "Errors")
        for ((i, n) in names.withIndex()) {
            val p = pill(n) {
                histFilter = i
                styleHistPills()
                refreshHistory()
            }
            histPills.add(p)
            fr.addView(p, lp(start = if (i > 0) 8 else 0))
        }
        fr.addView(View(this), lp(0, 1, weight = 1f))
        fr.addView(pill("Clear", withAlpha(C.red, 60), C.red) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Clear history?")
                .setMessage("This deletes the usage log kept by KeyHub.")
                .setPositiveButton("Clear") { _, _ ->
                    thread {
                        UsageDb.get(this).clear()
                        runOnUiThread { refreshHistory() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        top.addView(fr, lp(bottom = 8))
        styleHistPills()
        outer.addView(top)

        histEmpty = tv("No requests yet. Use the proxy from another app and they will show up here.", 13f, C.muted)
        histEmpty.setPadding(dp(20), dp(20), dp(20), dp(8))
        histEmpty.visibility = View.GONE
        outer.addView(histEmpty)

        histList = makeList()
        histList.adapter = histAdapter
        outer.addView(histList, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return outer
    }

    private fun makeList(): ListView = ListView(this).apply {
        divider = null
        dividerHeight = 0
        setSelector(ColorDrawable(Color.TRANSPARENT))
        setBackgroundColor(Color.TRANSPARENT)
        cacheColorHint = Color.TRANSPARENT
        setPadding(dp(20), dp(4), dp(20), dp(12))
        clipToPadding = false
        isVerticalScrollBarEnabled = false
    }

    private fun styleHistPills() {
        for ((i, p) in histPills.withIndex()) {
            if (i == histFilter) stylePill(p, C.accent, Color.BLACK) else stylePill(p, C.card2, C.text)
        }
    }

    private fun wrapRow(c: View): View {
        val w = FrameLayout(this)
        w.setPadding(0, 0, 0, dp(10))
        w.addView(c, FrameLayout.LayoutParams(MATCH, WRAP))
        return w
    }

    private fun historyRow(r: UsageRow): View {
        val fmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
        val c = card(0)
        val ok = r.status in 200..399
        val gem = r.provider == "gemini"

        val l1 = hrow()
        val name = tv(if (r.model.isEmpty()) "unknown model" else r.model, 14f, bold = true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        l1.addView(name, lp(0, WRAP, weight = 1f))
        l1.addView(chip(r.status.toString(), if (ok) C.green else C.red))
        c.addView(l1)

        var meta = fmt.format(Date(r.ts)) + "  -  " + (if (gem) "Gemini" else "OpenRouter")
        if (r.keyLabel.isNotEmpty()) meta += "  -  " + r.keyLabel
        if (r.latency > 0) {
            meta += if (r.latency >= 1000) {
                "  -  " + String.format(Locale.US, "%.1fs", r.latency / 1000.0)
            } else {
                "  -  ${r.latency}ms"
            }
        }
        c.addView(tv(meta, 12f, C.muted), lp(top = 3))

        if (ok) {
            val l3 = hrow()
            l3.addView(
                tv("in ${r.prompt}   out ${r.completion}   total ${r.total}", 13f),
                lp(0, WRAP, weight = 1f)
            )
            if (gem) {
                l3.addView(tv("no cost data", 12f, C.muted))
            } else {
                l3.addView(tv(usd(r.cost), 13f, C.accent, bold = true))
            }
            c.addView(l3, lp(top = 8))
        } else {
            c.addView(tv("Request failed - the next key was tried if available", 12f, C.red), lp(top = 8))
        }
        return wrapRow(c)
    }

    private fun refreshHistory() {
        val filter = histFilter
        thread {
            val db = UsageDb.get(this)
            val since = if (filter == 1) startOfToday() else 0L
            val t = db.totals(since)
            val rows = db.recent(150, since, filter == 2)
            runOnUiThread {
                histReq.text = t.ok.toString()
                histTok.text = t.tokens.toString()
                histCost.text = usd(t.cost)
                histFail.text = t.failed.toString()
                histEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                if (rows != histAdapter.items) {
                    histAdapter.items = rows
                    histAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    // ------------------------------------------------------------------ MODELS

    private fun currentModels(): List<ModelInfo> = if (modelProvider == 0) orModels else gemModels

    private fun buildModels(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val pr = hrow()
        for ((i, pair) in providers.withIndex()) {
            val p = pill(pair.second) {
                modelProvider = i
                styleModelPills()
                if (currentModels().isEmpty()) loadModels()
                applyModelFilter()
            }
            provPills.add(p)
            pr.addView(p, lp(start = if (i > 0) 8 else 0))
        }
        top.addView(pr, lp(bottom = 10))

        modelSearch = field("Search models")
        modelSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                applyModelFilter()
            }

            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        top.addView(modelSearch, lp(bottom = 10))

        val fr = hrow()
        val names = listOf("All", "Free only")
        for ((i, n) in names.withIndex()) {
            val p = pill(n) {
                freeOnly = i == 1
                styleModelPills()
                applyModelFilter()
            }
            modelPills.add(p)
            fr.addView(p, lp(start = if (i > 0) 8 else 0))
        }
        top.addView(fr, lp(bottom = 6))
        styleModelPills()

        modelStatus = tv("", 12f, C.muted)
        top.addView(modelStatus, lp(bottom = 4))
        outer.addView(top)

        modelList = makeList()
        modelList.adapter = modelAdapter
        modelList.setOnItemClickListener { _, _, pos, _ ->
            val m = modelAdapter.items.getOrNull(pos)
            if (m != null) copy(m.id, "Model ID")
        }
        outer.addView(modelList, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return outer
    }

    private fun styleModelPills() {
        for ((i, p) in provPills.withIndex()) {
            if (i == modelProvider) stylePill(p, C.accent, Color.BLACK) else stylePill(p, C.card2, C.text)
        }
        for ((i, p) in modelPills.withIndex()) {
            val on = (i == 1) == freeOnly
            if (on) stylePill(p, C.accent, Color.BLACK) else stylePill(p, C.card2, C.text)
            // the free filter only makes sense for OpenRouter
            p.visibility = if (modelProvider == 0) View.VISIBLE else View.GONE
        }
    }

    private fun price(v: Double): String =
        if (v < 0) "varies" else if (v == 0.0) "free" else "\$" + String.format(Locale.US, "%.2f", v)

    private fun modelRow(m: ModelInfo): View {
        val c = card(0)
        val l1 = hrow()
        val id = tv(m.id, 13f, mono = true)
        id.maxLines = 1
        id.ellipsize = TextUtils.TruncateAt.END
        l1.addView(id, lp(0, WRAP, weight = 1f))
        if (m.free) l1.addView(chip("FREE", C.green))
        c.addView(l1)
        c.addView(tv(m.name, 12f, C.muted), lp(top = 2))
        val ctxK = if (m.ctx > 0) "${m.ctx / 1000}k context" else "context n/a"
        val info = if (m.promptPer1M == geminiNoPrice) {
            ctxK
        } else {
            "in ${price(m.promptPer1M)} / out ${price(m.compPer1M)} per 1M tokens  -  $ctxK"
        }
        c.addView(tv(info, 12f), lp(top = 6))
        return wrapRow(c)
    }

    private fun applyModelFilter() {
        val q = modelSearch.text.toString().trim().lowercase()
        val useFree = freeOnly && modelProvider == 0
        val list = currentModels().filter {
            (!useFree || it.free) &&
                (q.isEmpty() || it.id.lowercase().contains(q) || it.name.lowercase().contains(q))
        }
        modelAdapter.items = list
        modelAdapter.notifyDataSetChanged()
        if (currentModels().isNotEmpty()) {
            modelStatus.text = "${list.size} models - tap one to copy its ID"
        }
    }

    private fun loadModels() {
        if (modelProvider == 0) loadOpenRouterModels() else loadGeminiModels()
    }

    private fun loadOpenRouterModels() {
        if (orLoading) return
        orLoading = true
        modelStatus.text = "Loading models..."
        thread {
            try {
                val arr = getJson("https://openrouter.ai/api/v1/models", null, null).getJSONArray("data")
                val out = ArrayList<ModelInfo>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id", "")
                    if (id.isEmpty()) continue
                    val pr = o.optJSONObject("pricing")
                    val p = (pr?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0) * 1_000_000.0
                    val cp = (pr?.optString("completion", "0")?.toDoubleOrNull() ?: 0.0) * 1_000_000.0
                    out.add(ModelInfo(id, o.optString("name", id), o.optInt("context_length", 0), p, cp))
                }
                out.sortBy { it.id }
                orModels = out
                runOnUiThread {
                    orLoading = false
                    if (modelProvider == 0) applyModelFilter()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    orLoading = false
                    if (modelProvider == 0) modelStatus.text = "Could not load models: ${e.message}"
                }
            }
        }
    }

    private fun loadGeminiModels() {
        if (gemLoading) return
        val key = Secrets.keys(this).firstOrNull { it.provider == "gemini" && it.enabled }
        val real = if (key == null) null else Secrets.keyValue(key)
        if (real == null) {
            modelStatus.text = "Add a Gemini key in the Keys tab to load Gemini models."
            return
        }
        gemLoading = true
        modelStatus.text = "Loading models..."
        thread {
            try {
                val arr = getJson(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
                    "x-goog-api-key", real
                ).getJSONArray("models")
                val out = ArrayList<ModelInfo>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val methods = o.optJSONArray("supportedGenerationMethods")
                    var chat = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") chat = true
                        }
                    }
                    if (!chat) continue
                    val id = o.optString("name", "").removePrefix("models/")
                    if (id.isEmpty()) continue
                    out.add(
                        ModelInfo(
                            id, o.optString("displayName", id), o.optInt("inputTokenLimit", 0),
                            geminiNoPrice, geminiNoPrice
                        )
                    )
                }
                out.sortBy { it.id }
                gemModels = out
                runOnUiThread {
                    gemLoading = false
                    if (modelProvider == 1) applyModelFilter()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    gemLoading = false
                    if (modelProvider == 1) modelStatus.text = "Could not load models: ${e.message}"
                }
            }
        }
    }
}
