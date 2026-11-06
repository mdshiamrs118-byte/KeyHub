package com.keyhub.test

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted storage for the real OpenRouter key + the universal key. */
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

    fun saveRealKey(c: Context, key: String) {
        prefs(c).edit().putString("openrouter_key", encrypt(key)).apply()
    }

    fun realKey(c: Context): String? {
        val s = prefs(c).getString("openrouter_key", null) ?: return null
        return decrypt(s)
    }

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
    val path: String
)

class UsageDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "usage.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, model TEXT, " +
                "prompt INTEGER, completion INTEGER, total INTEGER, cost REAL, status INTEGER, path TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
        writableDatabase.insert("log", null, v)
    }

    fun recent(n: Int): List<UsageRow> {
        val out = ArrayList<UsageRow>()
        readableDatabase.rawQuery(
            "SELECT ts,model,prompt,completion,total,cost,status,path FROM log ORDER BY id DESC LIMIT $n",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    UsageRow(
                        c.getLong(0), c.getString(1) ?: "", c.getInt(2), c.getInt(3),
                        c.getInt(4), c.getDouble(5), c.getInt(6), c.getString(7) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** count, total tokens, total cost */
    fun totals(): Triple<Int, Long, Double> {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(total),0), COALESCE(SUM(cost),0) FROM log", null
        ).use { c ->
            if (c.moveToFirst()) return Triple(c.getInt(0), c.getLong(1), c.getDouble(2))
        }
        return Triple(0, 0L, 0.0)
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
