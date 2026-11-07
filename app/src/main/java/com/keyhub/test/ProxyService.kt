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
import org.json.JSONObject
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
 * Local OpenAI-compatible proxy on 127.0.0.1:8787.
 * Other apps use  http://127.0.0.1:8787/v1  +  the universal key.
 * We pick one of your OpenRouter keys, forward the request, stream the answer
 * back, and log token usage. If a key fails (invalid / out of credit / rate
 * limited) the next key is tried automatically.
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 8787
        private const val CHANNEL = "keyhub_proxy"
        private const val CORS =
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Expose-Headers: *\r\n"

        @Volatile
        var running = false

        @Volatile
        var lastError: String? = null

        /** id of the key that served the last successful request */
        @Volatile
        var activeKeyId: String? = null

        /** key id -> time (ms) until which the key is skipped */
        val cooldownUntil = ConcurrentHashMap<String, Long>()

        /** key id -> last failing HTTP status */
        val lastFailCode = ConcurrentHashMap<String, Int>()

        private val FAILOVER = setOf(401, 402, 403, 429)

        private val SKIP_REQ = setOf(
            "host", "authorization", "x-api-key", "content-length", "connection",
            "accept-encoding", "transfer-encoding", "expect", "keep-alive",
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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
        if (p == "[DONE]" || !p.contains("\"usage\"")) return
        try {
            applyJson(JSONObject(p), u)
        } catch (_: Exception) {
        }
    }

    private fun logRow(
        k: ApiKey?, model: String, u: UsageInfo?, status: Int, path: String, ms: Long
    ) {
        try {
            UsageDb.get(this).add(
                UsageRow(
                    System.currentTimeMillis(), model,
                    u?.prompt ?: 0, u?.completion ?: 0, u?.total ?: 0, u?.cost ?: 0.0,
                    status, path, k?.id ?: "", k?.label ?: "", ms
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun openUpstream(
        url: String, method: String, headers: Map<String, String>, body: ByteArray, real: String
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30000
        c.readTimeout = 300000
        c.instanceFollowRedirects = false
        for ((k, v) in headers) {
            if (k !in SKIP_REQ) c.setRequestProperty(k, v)
        }
        c.setRequestProperty("Authorization", "Bearer $real")
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

        // auth with universal key
        val a = headers["authorization"]
        val token = if (a != null && a.startsWith("bearer ", ignoreCase = true)) {
            a.substring(7).trim()
        } else {
            headers["x-api-key"] ?: ""
        }
        if (token != Secrets.universalKey(this)) {
            sendSimple(out, 401, errJson("Invalid KeyHub key", 401))
            return
        }

        val keys = Secrets.keys(this).filter { it.enabled }
        if (keys.isEmpty()) {
            sendSimple(out, 503, errJson("No enabled OpenRouter key in KeyHub", 503))
            return
        }
        val nowMs = System.currentTimeMillis()
        val ready = keys.filter { (cooldownUntil[it.id] ?: 0L) <= nowMs }
        val order = ready + keys.filter { k -> ready.none { it.id == k.id } }

        // build upstream path
        var p = target
        if (p.startsWith("/api/")) p = p.removePrefix("/api")
        if (!p.startsWith("/v1")) p = "/v1" + (if (p.startsWith("/")) p else "/$p")
        val url = "https://openrouter.ai/api$p"

        // read model + ask OpenRouter to include usage
        var reqModel = ""
        if (method == "POST" && body.isNotEmpty() &&
            (headers["content-type"] ?: "").contains("json", ignoreCase = true)
        ) {
            try {
                val o = JSONObject(String(body, Charsets.UTF_8))
                reqModel = o.optString("model", "")
                if (p.contains("chat/completions")) {
                    o.put("usage", JSONObject().put("include", true))
                    if (o.optBoolean("stream", false)) {
                        val so = o.optJSONObject("stream_options") ?: JSONObject()
                        so.put("include_usage", true)
                        o.put("stream_options", so)
                    }
                    body = o.toString().toByteArray(Charsets.UTF_8)
                }
            } catch (_: Exception) {
            }
        }

        val usage = UsageInfo()
        var statusCode = 0
        var usedKey: ApiKey? = null
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
                    c = openUpstream(url, method, headers, body, real)
                    val code = c.responseCode
                    if (code in FAILOVER && !isLast) {
                        cooldownUntil[k.id] =
                            System.currentTimeMillis() + (if (code == 429) 60_000L else 600_000L)
                        lastFailCode[k.id] = code
                        if (method == "POST") {
                            logRow(k, reqModel, null, code, p, System.currentTimeMillis() - t0)
                        }
                        c.disconnect()
                        continue
                    }
                    conn = c
                    usedKey = k
                    statusCode = code
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
                activeKeyId = key.id
            } else if (key != null && statusCode in FAILOVER) {
                cooldownUntil[key.id] =
                    System.currentTimeMillis() + (if (statusCode == 429) 60_000L else 600_000L)
                lastFailCode[key.id] = statusCode
            }

            val ctype = cn.contentType ?: "application/json"
            val upstream: InputStream? = if (statusCode >= 400) cn.errorStream else cn.inputStream
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
            if (!isSse && cap.size() > 0) {
                try {
                    applyJson(JSONObject(cap.toString("UTF-8")), usage)
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            if (statusCode == 0) {
                statusCode = 502
                try {
                    sendSimple(out, 502, errJson("KeyHub could not reach OpenRouter: ${e.message}", 502))
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
                usedKey, usage.model.ifEmpty { reqModel }, usage, statusCode, p,
                System.currentTimeMillis() - t0
            )
        }
    }
}
