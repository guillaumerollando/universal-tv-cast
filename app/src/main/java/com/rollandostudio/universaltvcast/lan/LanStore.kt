package com.rollandostudio.universaltvcast.lan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Appareils du réseau déjà ouverts : on les retrouve d'un appui, sans retaper l'identifiant. Quelques octets par
 * appareil (adresse, nom, identifiant) ; le mot de passe est chiffré par une clé que seul ce téléphone détient
 * (Android Keystore) : restauré sur un autre téléphone, il est illisible et l'appli redemande simplement le mot de passe.
 */
object LanStore {
    data class Saved(val kind: LanDevice.Kind, val name: String, val host: String, val port: Int,
        val user: String? = null, val domain: String? = null, val location: String? = null, val usedAt: Long = 0L,
        val share: String? = null) {
        val key: String get() = "${kind.name}:$host:$port"
    }

    /** Assez pour un foyer : au-delà, les plus anciens sont oubliés. */
    private const val MAX = 20
    private const val PREFS = "lan"
    private const val KEY_ALIAS = "tvcast-lan"

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun all(): List<Saved> = runCatching {
        val a = JSONArray(prefs.getString("devices", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Saved(LanDevice.Kind.valueOf(o.getString("kind")), o.getString("name"), o.getString("host"), o.optInt("port", -1),
                o.optString("user").ifEmpty { null }, o.optString("domain").ifEmpty { null },
                o.optString("location").ifEmpty { null }, o.optLong("usedAt"), o.optString("share").ifEmpty { null })
        }.sortedByDescending { it.usedAt }
    }.getOrDefault(emptyList())

    fun find(kind: LanDevice.Kind, host: String, port: Int): Saved? =
        all().firstOrNull { it.kind == kind && it.host.equals(host, true) && (it.port == port || (it.port <= 0 && port <= 0)) }

    @Synchronized
    fun save(s: Saved, password: String? = null) {
        val list = (listOf(s.copy(usedAt = System.currentTimeMillis())) + all().filter { it.key != s.key }).take(MAX)
        val dropped = all().map { it.key }.toSet() - list.map { it.key }.toSet()
        val a = JSONArray()
        list.forEach { d ->
            a.put(JSONObject().put("kind", d.kind.name).put("name", d.name).put("host", d.host).put("port", d.port)
                .put("user", d.user.orEmpty()).put("domain", d.domain.orEmpty()).put("location", d.location.orEmpty())
                .put("usedAt", d.usedAt).put("share", d.share.orEmpty()))
        }
        prefs.edit().apply {
            putString("devices", a.toString())
            dropped.forEach { remove("pw:$it") }
            if (password != null) putString("pw:${s.key}", encrypt(password)) else if (s.user == null) remove("pw:${s.key}")
        }.apply()
    }

    @Synchronized
    fun forget(key: String) {
        val a = JSONArray()
        all().filter { it.key != key }.forEach { d ->
            a.put(JSONObject().put("kind", d.kind.name).put("name", d.name).put("host", d.host).put("port", d.port)
                .put("user", d.user.orEmpty()).put("domain", d.domain.orEmpty()).put("location", d.location.orEmpty())
                .put("usedAt", d.usedAt).put("share", d.share.orEmpty()))
        }
        prefs.edit().putString("devices", a.toString()).remove("pw:$key").apply()
    }

    fun password(s: Saved): String? = prefs.getString("pw:${s.key}", null)?.let { runCatching { decrypt(it) }.getOrNull() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }
}
