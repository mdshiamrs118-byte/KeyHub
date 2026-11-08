package com.keyhub.test

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class UsageInfo {
    var model = ""
    var prompt = 0
    var completion = 0
    var total = 0
    var cost = 0.0
}

/**
 * Local proxy on 127.0.0.1:8787 for OpenRouter and Gemini.
 *
 * Other apps use  http://127.0.0.1:8787/v1  + the universal key.
 *  - model names starting with "gemini" go to Gemini (OpenAI-compatible endpoint)
 *  - everything else goes to OpenRouter
 *  - /gemini/...  forces Gemini (also supports native Gemini paths like
 *    /gemini/v1beta/models/<model>:generateContent)
 * If a key is rejected the next key of the same provider is tried automatically.
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 8787
        private const val CHANNEL = "keyhub_proxy"
        private const val GEMINI_HOST = "https://generativelanguage.googleapis.com"
        private const val CORS =
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Expose-Headers: *\r\n"

        @Volatile
        var running = false

        @Volatile
        var lastError: String? = null

        /** provider -> id of the key that served the last successful request */
        val activeKeys = ConcurrentHashMap<String, String>()

        /** key id -> time (ms) until which the key is skipped */
        val cooldownUntil = ConcurrentHashMap<String, Long>()

        /** key id -> last failing HTTP status */
        val lastFailCode = ConcurrentHashMap<String, Int>()

        private val FAILOVER_OR = setOf(401, 402, 403, 429)
        private val FAILOVER_GEM = setOf(401, 403, 429)

        private val SKIP_REQ = setOf(
            "host", "authorization", "x-api-key", "x-goog-api-key", "content-length",
            "connection", "accept-encoding", "transfer-encoding", "expect", "keep-alive",
            "proxy-connection", "upgrade"
        )
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (!running) startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Proxy", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_key)
            .setContentTitle("KeyHub proxy running")
            .setContentText("127.0.0.1:$PORT")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun startServer() {
        thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50)
                server = ss
                running = true
                lastError = null
                while (!ss.isClosed) {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                }
            } catch (e: Exception) {
                if (!running) lastError = e.message
            } finally {
                running = false
            }
        }
    }

    // ---------- HTTP helpers ----------

    private fun readLine(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code && b.size() < 65536) b.write(c)
        }
        return b.toString("ISO-8859-1")
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val a = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(a, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) a else a.copyOf(off)
    }

    private fun readChunked(inp: InputStream): ByteArray {
        val o = ByteArrayOutputStream()
        while (true) {
            val l = readLine(inp) ?: break
            val size = l.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val t = readLine(inp)
                    if (t == null || t.isEmpty()) break
                }
                break
            }
            o.write(readFully(inp, size))
            readLine(inp)
        }
        return o.toByteArray()
    }

    private fun errJson(msg: String, code: Int): String =
        JSONObject().put("error", JSONObject().put("message", msg).put("code", code)).toString()

    private fun sendSimple(out: java.io.OutputStream, code: Int, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code < 400) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${b.size}\r\n" +
            CORS +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(b)
        out.flush()
    }

    // ---------- usage parsing ----------

    private fun applyJson(o: JSONObject, u: UsageInfo) {
        // native Gemini format
        val um = o.optJSONObject("usageMetadata")
        if (um != null) {
            u.prompt = um.optInt("promptTokenCount", 0)
            u.completion = um.optInt("candidatesTokenCount", 0) + um.optInt("thoughtsTokenCount", 0)
            u.total = um.optInt("totalTokenCount", u.prompt + u.completion)
            val mv = o.optString("modelVersion", "")
            if (mv.isNotEmpty()) u.model = mv
            return
        }
        // OpenAI-style format (OpenRouter and Gemini compat)
        val us = o.optJSONObject("usage") ?: return
        val m = o.optString("model", "")
        if (m.isNotEmpty()) u.model = m
        u.prompt = us.optInt("prompt_tokens", us.optInt("input_tokens", 0))
        u.completion = us.optInt("completion_tokens", us.optInt("output_tokens", 0))
        u.total = us.optInt("total_tokens", u.prompt + u.completion)
        u.cost = us.optDouble("cost", 0.0)
    }

    private fun scanLine(l: String, u: UsageInfo) {
        val t = l.trim()
        if (!t.startsWith("data:")) return
        val p = t.substring(5).trim()
        if (p == "[DONE]") return
        if (!p.contains("\"usage\"") && !p.contains("usageMetadata")) return
        try {
            applyJson(JSONObject(p), u)
        } catch (_: Exception) {
        }
    }

    private fun parseBuffered(text: String, u: UsageInfo) {
        val t = text.trim()
        try {
            if (t.startsWith("[")) {
                val arr = JSONArray(t)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o != null) applyJson(o, u)
                }
            } else {
                applyJson(JSONObject(t), u)
            }
        } catch (_: Exception) {
        }
    }

    private fun logRow(
        k: ApiKey?, provider: String, model: String, u: UsageInfo?,
        status: Int, path: String, ms: Long
    ) {
        try {
            UsageDb.get(this).add(
                UsageRow(
                    System.currentTimeMillis(), model,
                    u?.prompt ?: 0, u?.completion ?: 0, u?.total ?: 0, u?.cost ?: 0.0,
                    status, path, k?.id ?: "", k?.label ?: "", ms, provider
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun openUpstream(
        url: String, method: String, headers: Map<String, String>, body: ByteArray,
        real: String, provider: String, native: Boolean
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30000
        c.readTimeout = 300000
        c.instanceFollowRedirects = false
        for ((k, v) in headers) {
            if (k !in SKIP_REQ) c.setRequestProperty(k, v)
        }
        if (provider == "gemini" && native) {
            c.setRequestProperty("x-goog-api-key", real)
        } else {
            c.setRequestProperty("Authorization", "Bearer $real")
        }
        c.setRequestProperty("Accept-Encoding", "identity")
        if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        return c
    }

    // ---------- connection handling ----------

    private fun handle(sock: Socket) {
        try {
            sock.use { process(it) }
        } catch (_: Throwable) {
        }
    }

    private fun process(sock: Socket) {
        sock.soTimeout = 60000
        val inp = java.io.BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()

        val reqLine = readLine(inp) ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }

        if (headers["expect"]?.lowercase() == "100-continue") {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }

        var body: ByteArray =
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                readChunked(inp)
            } else {
                readFully(inp, headers["content-length"]?.toIntOrNull() ?: 0)
            }

        if (method == "OPTIONS") {
            val reqH = headers["access-control-request-headers"] ?: "authorization, content-type"
            val resp = "HTTP/1.1 204 No Content\r\n" +
                CORS +
                "Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: $reqH\r\n" +
                "Access-Control-Allow-Private-Network: true\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            return
        }
        if (target == "/" || target.startsWith("/health")) {
            sendSimple(out, 200, "{\"ok\":true,\"service\":\"KeyHub\"}")
            return
        }

        // optional provider prefix
        val forced: String? = when {
            target.startsWith("/gemini/") -> "gemini"
            target.startsWith("/openrouter/") -> "openrouter"
            else -> null
        }
        val stripped = when (forced) {
            "gemini" -> target.removePrefix("/gemini")
            "openrouter" -> target.removePrefix("/openrouter")
            else -> target
        }
        val qIdx = stripped.indexOf('?')
        val pathOnly = if (qIdx >= 0) stripped.substring(0, qIdx) else stripped
        val query = if (qIdx >= 0) stripped.substring(qIdx + 1) else ""
        val queryParts = query.split("&").filter { it.isNotEmpty() }
        val cleanQuery = queryParts.filter { !it.startsWith("key=") }.joinToString("&")

        // auth with the universal key (header or ?key= for native Gemini clients)
        val a = headers["authorization"]
        val token = when {
            a != null && a.startsWith("bearer ", ignoreCase = true) -> a.substring(7).trim()
            headers["x-api-key"] != null -> headers["x-api-key"] ?: ""
            headers["x-goog-api-key"] != null -> headers["x-goog-api-key"] ?: ""
            else -> queryParts.firstOrNull { it.startsWith("key=") }?.removePrefix("key=") ?: ""
        }
        if (token != Secrets.universalKey(this)) {
            sendSimple(out, 401, errJson("Invalid KeyHub key", 401))
            return
        }

        // read the model name from a JSON body
        var reqModel = ""
        var bodyJson: JSONObject? = null
        if (method == "POST" && body.isNotEmpty() &&
            (headers["content-type"] ?: "").contains("json", ignoreCase = true)
        ) {
            try {
                val o = JSONObject(String(body, Charsets.UTF_8))
                bodyJson = o
                reqModel = o.optString("model", "")
            } catch (_: Exception) {
            }
        }

        // native Gemini API paths (generateContent etc.)
        val native = pathOnly.contains(":generateContent") ||
            pathOnly.contains(":streamGenerateContent") ||
            pathOnly.contains(":countTokens") ||
            pathOnly.contains(":embedContent") ||
            pathOnly.contains(":batchEmbedContents") ||
            (pathOnly.startsWith("/v1beta/") && !pathOnly.startsWith("/v1beta/openai"))

        val allKeys = Secrets.keys(this).filter { it.enabled }
        val lm = reqModel.lowercase()
        var provider: String = when {
            forced != null -> forced
            native -> "gemini"
            lm.startsWith("gemini") || lm.startsWith("models/gemini") || lm.startsWith("gemma") -> "gemini"
            else -> "openrouter"
        }
        // model list request with only Gemini keys present
        if (forced == null && reqModel.isEmpty() && provider == "openrouter" &&
            allKeys.none { it.provider == "openrouter" } && allKeys.any { it.provider == "gemini" }
        ) {
            provider = "gemini"
        }
        val providerName = if (provider == "gemini") "Gemini" else "OpenRouter"

        val keys = allKeys.filter { it.provider == provider }
        if (keys.isEmpty()) {
            sendSimple(out, 503, errJson("No enabled $providerName key in KeyHub", 503))
            return
        }
        val nowMs = System.currentTimeMillis()
        val ready = keys.filter { (cooldownUntil[it.id] ?: 0L) <= nowMs }
        val order = ready + keys.filter { k -> ready.none { it.id == k.id } }
        val failoverCodes = if (provider == "gemini") FAILOVER_GEM else FAILOVER_OR

        // build upstream URL
        val url: String
        val p: String
        if (provider == "gemini") {
            if (native) {
                p = if (pathOnly.startsWith("/")) pathOnly else "/$pathOnly"
                url = GEMINI_HOST + p + (if (cleanQuery.isNotEmpty()) "?$cleanQuery" else "")
                if (reqModel.isEmpty()) {
                    reqModel = Regex("models/([^:/]+)").find(pathOnly)?.groupValues?.get(1) ?: ""
                }
            } else {
                var rest = pathOnly.removePrefix("/v1beta/openai").removePrefix("/v1")
                if (!rest.startsWith("/")) rest = "/$rest"
                p = rest
                url = "$GEMINI_HOST/v1beta/openai$rest" +
                    (if (cleanQuery.isNotEmpty()) "?$cleanQuery" else "")
            }
        } else {
            var q = pathOnly
            if (q.startsWith("/api/")) q = q.removePrefix("/api")
            if (!q.startsWith("/v1")) q = "/v1" + (if (q.startsWith("/")) q else "/$q")
            p = q
            url = "https://openrouter.ai/api$q" + (if (cleanQuery.isNotEmpty()) "?$cleanQuery" else "")
        }

        // ask the provider to include usage numbers
        val bj = bodyJson
        if (bj != null && !native && p.contains("chat/completions")) {
            try {
                if (provider == "openrouter") {
                    bj.put("usage", JSONObject().put("include", true))
                }
                if (bj.optBoolean("stream", false)) {
                    val so = bj.optJSONObject("stream_options") ?: JSONObject()
                    so.put("include_usage", true)
                    bj.put("stream_options", so)
                }
                body = bj.toString().toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }

        val usage = UsageInfo()
        var statusCode = 0
        var usedKey: ApiKey? = null
        var preRead: ByteArray? = null
        var conn: HttpURLConnection? = null
        val t0 = System.currentTimeMillis()
        try {
            // try keys in order; move on when a key is rejected
            for ((idx, k) in order.withIndex()) {
                val real = Secrets.keyValue(k)
                if (real.isNullOrEmpty()) continue
                val isLast = idx == order.size - 1
                var c: HttpURLConnection? = null
                try {
                    c = openUpstream(url, method, headers, body, real, provider, native)
                    val code = c.responseCode
                    var fail = code in failoverCodes
                    var pre: ByteArray? = null
                    if (!fail && provider == "gemini" && code == 400) {
                        // Gemini answers a bad key with 400 "API key not valid"
                        pre = try {
                            c.errorStream?.readBytes()
                        } catch (_: Exception) {
                            null
                        }
                        val txt = if (pre != null) String(pre, Charsets.UTF_8) else ""
                        if (txt.contains("API key", ignoreCase = true) ||
                            txt.contains("API_KEY_INVALID")
                        ) {
                            fail = true
                        }
                    }
                    if (fail && !isLast) {
                        cooldownUntil[k.id] =
                            System.currentTimeMillis() + (if (code == 429) 60_000L else 600_000L)
                        lastFailCode[k.id] = code
                        if (method == "POST") {
                            logRow(k, provider, reqModel, null, code, p, System.currentTimeMillis() - t0)
                        }
                        c.disconnect()
                        continue
                    }
                    conn = c
                    usedKey = k
                    statusCode = code
                    preRead = pre
                    break
                } catch (e: Exception) {
                    try {
                        c?.disconnect()
                    } catch (_: Exception) {
                    }
                    if (isLast) throw e
                }
            }

            val cn = conn ?: throw Exception("No usable key")
            val key = usedKey
            if (key != null && statusCode < 400) {
                cooldownUntil.remove(key.id)
                lastFailCode.remove(key.id)
                activeKeys[provider] = key.id
            } else if (key != null && (statusCode in failoverCodes)) {
                cooldownUntil[key.id] =
                    System.currentTimeMillis() + (if (statusCode == 429) 60_000L else 600_000L)
                lastFailCode[key.id] = statusCode
            }

            val ctype = cn.contentType ?: "application/json"
            val pr = preRead
            val upstream: InputStream? = when {
                pr != null -> ByteArrayInputStream(pr)
                statusCode >= 400 -> cn.errorStream
                else -> cn.inputStream
            }
            val msg = cn.responseMessage?.takeIf { it.isNotBlank() }
                ?: (if (statusCode < 400) "OK" else "Error")

            val head = "HTTP/1.1 $statusCode $msg\r\n" +
                "Content-Type: $ctype\r\n" +
                "Cache-Control: no-cache\r\n" +
                CORS +
                "X-Accel-Buffering: no\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val isSse = ctype.contains("event-stream", ignoreCase = true)
            val cap = ByteArrayOutputStream()
            val line = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            if (upstream != null) {
                while (true) {
                    val n = upstream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                    if (isSse) {
                        for (i in 0 until n) {
                            val b = buf[i]
                            if (b == '\n'.code.toByte()) {
                                scanLine(line.toString("UTF-8"), usage)
                                line.reset()
                            } else if (line.size() < 1_000_000) {
                                line.write(b.toInt())
                            }
                        }
                    } else if (cap.size() < 4_000_000) {
                        cap.write(buf, 0, n)
                    }
                }
            }
            if (isSse && line.size() > 0) scanLine(line.toString("UTF-8"), usage)
            if (!isSse && cap.size() > 0) parseBuffered(cap.toString("UTF-8"), usage)
        } catch (e: Exception) {
            if (statusCode == 0) {
                statusCode = 502
                try {
                    sendSimple(out, 502, errJson("KeyHub could not reach $providerName: ${e.message}", 502))
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }

        if (method == "POST") {
            logRow(
                usedKey, provider, usage.model.ifEmpty { reqModel }, usage, statusCode, p,
                System.currentTimeMillis() - t0
            )
        }
    }
}
