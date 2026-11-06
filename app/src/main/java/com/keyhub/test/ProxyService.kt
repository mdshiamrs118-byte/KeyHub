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
 * We swap in the real OpenRouter key, forward the request, stream the answer
 * back, and log token usage.
 */
class ProxyService : Service() {

    companion object {
        const val PORT = 8787
        private const val CHANNEL = "keyhub_proxy"

        @Volatile
        var running = false

        @Volatile
        var lastError: String? = null

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
            sendSimple(out, 204, "")
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
        val real = Secrets.realKey(this)
        if (real.isNullOrEmpty()) {
            sendSimple(out, 503, errJson("No OpenRouter key saved in KeyHub", 503))
            return
        }

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
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 30000
            conn.readTimeout = 300000
            conn.instanceFollowRedirects = false
            for ((k, v) in headers) {
                if (k !in SKIP_REQ) conn.setRequestProperty(k, v)
            }
            conn.setRequestProperty("Authorization", "Bearer $real")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }

            statusCode = conn.responseCode
            val ctype = conn.contentType ?: "application/json"
            val upstream: InputStream? = if (statusCode >= 400) conn.errorStream else conn.inputStream
            val msg = conn.responseMessage?.takeIf { it.isNotBlank() }
                ?: (if (statusCode < 400) "OK" else "Error")

            val head = "HTTP/1.1 $statusCode $msg\r\n" +
                "Content-Type: $ctype\r\n" +
                "Cache-Control: no-cache\r\n" +
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
            UsageDb.get(this).add(
                UsageRow(
                    System.currentTimeMillis(),
                    usage.model.ifEmpty { reqModel },
                    usage.prompt, usage.completion, usage.total, usage.cost,
                    statusCode, p
                )
            )
        }
    }
}
