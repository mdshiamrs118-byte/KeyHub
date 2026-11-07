package com.keyhub.test

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ApiKey(val id: String, val label: String, val enc: String, val enabled: Boolean)

/** Encrypted storage for the OpenRouter keys + the universal key. */
object Secrets {
    private const val ALIAS = "keyhub_aes"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("keyhub", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String? {
        return try {
            val all = Base64.decode(s, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- keys list (order = priority) ----------

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun saveKeys(c: Context, list: List<ApiKey>) {
        val a = JSONArray()
        for (k in list) {
            a.put(
                JSONObject()
                    .put("id", k.id)
                    .put("label", k.label)
                    .put("enc", k.enc)
                    .put("enabled", k.enabled)
            )
        }
        prefs(c).edit().putString("keys_json", a.toString()).apply()
    }

    private fun loadKeys(c: Context): MutableList<ApiKey> {
        val p = prefs(c)
        val raw = p.getString("keys_json", null)
        val out = ArrayList<ApiKey>()
        if (raw != null) {
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    out.add(
                        ApiKey(
                            o.getString("id"),
                            o.optString("label", "Key"),
                            o.getString("enc"),
                            o.optBoolean("enabled", true)
                        )
                    )
                }
            } catch (_: Exception) {
            }
            return out
        }
        // migrate the old single-key storage from version 0.1 / 0.2
        val old = p.getString("openrouter_key", null)
        if (old != null) out.add(ApiKey(newId(), "Key 1", old, true))
        saveKeys(c, out)
        p.edit().remove("openrouter_key").apply()
        return out
    }

    @Synchronized
    fun keys(c: Context): List<ApiKey> = loadKeys(c)

    @Synchronized
    fun addKey(c: Context, label: String, key: String) {
        val l = loadKeys(c)
        val name = if (label.isBlank()) "Key ${l.size + 1}" else label.trim()
        l.add(ApiKey(newId(), name, encrypt(key), true))
        saveKeys(c, l)
    }

    @Synchronized
    fun removeKey(c: Context, id: String) {
        saveKeys(c, loadKeys(c).filter { it.id != id })
    }

    @Synchronized
    fun setEnabled(c: Context, id: String, enabled: Boolean) {
        saveKeys(c, loadKeys(c).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun rename(c: Context, id: String, label: String) {
        if (label.isBlank()) return
        saveKeys(c, loadKeys(c).map { if (it.id == id) it.copy(label = label.trim()) else it })
    }

    @Synchronized
    fun move(c: Context, id: String, delta: Int) {
        val l = loadKeys(c)
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i >= 0 && j >= 0 && j < l.size) {
            val t = l[i]
            l[i] = l[j]
            l[j] = t
            saveKeys(c, l)
        }
    }

    fun keyValue(k: ApiKey): String? = decrypt(k.enc)

    // ---------- universal key ----------

    private fun newUniversal(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return "sk-keyhub-" + b.joinToString("") { "%02x".format(it) }
    }

    fun universalKey(c: Context): String {
        val p = prefs(c)
        p.getString("universal_key", null)?.let { return it }
        val k = newUniversal()
        p.edit().putString("universal_key", k).apply()
        return k
    }

    fun regenerateUniversal(c: Context): String {
        val k = newUniversal()
        prefs(c).edit().putString("universal_key", k).apply()
        return k
    }
}

data class UsageRow(
    val ts: Long,
    val model: String,
    val prompt: Int,
    val completion: Int,
    val total: Int,
    val cost: Double,
    val status: Int,
    val path: String,
    val keyId: String,
    val keyLabel: String,
    val latency: Long
)

data class Totals(val ok: Int, val failed: Int, val tokens: Long, val cost: Double)

class UsageDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "usage.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, model TEXT, " +
                "prompt INTEGER, completion INTEGER, total INTEGER, cost REAL, status INTEGER, " +
                "path TEXT, key_id TEXT, key_label TEXT, latency INTEGER DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE log ADD COLUMN key_id TEXT")
            db.execSQL("ALTER TABLE log ADD COLUMN key_label TEXT")
            db.execSQL("ALTER TABLE log ADD COLUMN latency INTEGER DEFAULT 0")
        }
    }

    fun add(r: UsageRow) {
        val v = ContentValues()
        v.put("ts", r.ts)
        v.put("model", r.model)
        v.put("prompt", r.prompt)
        v.put("completion", r.completion)
        v.put("total", r.total)
        v.put("cost", r.cost)
        v.put("status", r.status)
        v.put("path", r.path)
        v.put("key_id", r.keyId)
        v.put("key_label", r.keyLabel)
        v.put("latency", r.latency)
        writableDatabase.insert("log", null, v)
    }

    fun recent(n: Int, since: Long, errorsOnly: Boolean): List<UsageRow> {
        val out = ArrayList<UsageRow>()
        val where = StringBuilder("ts >= $since")
        if (errorsOnly) where.append(" AND status >= 400")
        readableDatabase.rawQuery(
            "SELECT ts,model,prompt,completion,total,cost,status,path,key_id,key_label,latency " +
                "FROM log WHERE $where ORDER BY id DESC LIMIT $n",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    UsageRow(
                        c.getLong(0), c.getString(1) ?: "", c.getInt(2), c.getInt(3),
                        c.getInt(4), c.getDouble(5), c.getInt(6), c.getString(7) ?: "",
                        c.getString(8) ?: "", c.getString(9) ?: "", c.getLong(10)
                    )
                )
            }
        }
        return out
    }

    fun totals(since: Long): Totals {
        readableDatabase.rawQuery(
            "SELECT SUM(CASE WHEN status < 400 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END), " +
                "COALESCE(SUM(total),0), COALESCE(SUM(cost),0) FROM log WHERE ts >= $since",
            null
        ).use { c ->
            if (c.moveToFirst()) return Totals(c.getInt(0), c.getInt(1), c.getLong(2), c.getDouble(3))
        }
        return Totals(0, 0, 0L, 0.0)
    }

    fun perKey(): Map<String, Totals> {
        val m = HashMap<String, Totals>()
        readableDatabase.rawQuery(
            "SELECT key_id, SUM(CASE WHEN status < 400 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END), " +
                "COALESCE(SUM(total),0), COALESCE(SUM(cost),0) FROM log " +
                "WHERE key_id IS NOT NULL AND key_id != '' GROUP BY key_id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                m[c.getString(0)] = Totals(c.getInt(1), c.getInt(2), c.getLong(3), c.getDouble(4))
            }
        }
        return m
    }

    fun clear() {
        writableDatabase.delete("log", null, null)
    }

    companion object {
        @Volatile
        private var inst: UsageDb? = null

        fun get(c: Context): UsageDb =
            inst ?: synchronized(this) {
                inst ?: UsageDb(c.applicationContext).also { inst = it }
            }
    }
}
