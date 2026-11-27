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

    /** Tri des dossiers (« name », « recent », « year ») et « masquer les vus », retenus d'une fois sur l'autre. */
    var sort: String
        get() = prefs.getString("sort", "name") ?: "name"
        set(v) { prefs.edit().putString("sort", v).apply() }
    var hideWatched: Boolean
        get() = prefs.getBoolean("hide_watched", false)
        set(v) { prefs.edit().putBoolean("hide_watched", v).apply() }

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

    // ------------------------------------------------------------------ vu, commencé, et vidéos lancées

    /** Où l'on en est d'une vidéo du réseau (pour la mosaïque : barre d'avancement, « vu »). */
    data class Watch(val pos: Double, val dur: Double, val done: Boolean) {
        val finished: Boolean get() = done || (dur > 60 && pos >= dur - minOf(300.0, dur * 0.08))
        val progress: Float? get() = if (dur > 0 && pos >= 30 && !finished) (pos / dur).toFloat() else null
    }

    private const val MAX_WATCH = 800
    private val _watched = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Watch>>(emptyMap())
    @Volatile private var watchLoaded = false
    val watched: kotlinx.coroutines.flow.StateFlow<Map<String, Watch>> get() {
        if (!watchLoaded) {
            watchLoaded = true
            _watched.value = runCatching {
                val o = JSONObject(prefs.getString("watched", "{}")!!)
                o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { Watch(it.getDouble(0), it.getDouble(1), it.getBoolean(2)) } }
            }.getOrDefault(emptyMap())
        }
        return _watched
    }

    /** Vidéos lancées depuis le réseau : de quoi les rouvrir (« Reprendre », épisode suivant) sans le dossier. */
    private const val MAX_MEDIA = 300
    private val known = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    @Volatile private var knownLoaded = false

    private fun loadKnown() {
        if (knownLoaded) return
        knownLoaded = true
        runCatching { val o = JSONObject(prefs.getString("media", "{}")!!); o.keys().forEach { known[it] = o.getJSONObject(it) } }
    }

    /** Retient une vidéo du réseau (et la suivante du dossier, pour « Épisode suivant »). */
    @Synchronized
    fun remember(m: com.rollandostudio.universaltvcast.cast.Media, device: LanDevice? = null) {
        loadKnown()
        known[m.url] = JSONObject().put("title", m.title)
            .put("device", device?.name ?: known[m.url]?.optString("device").orEmpty())
            .put("host", device?.host ?: known[m.url]?.optString("host").orEmpty())
            .put("port", device?.port ?: known[m.url]?.optInt("port", -1) ?: -1)
            .put("smb", device?.kind == LanDevice.Kind.SHARE).put("mime", m.mime ?: "").put("file", m.fileName ?: "")
            .put("poster", m.posterUrl ?: "").put("backdrop", m.backdropUrl ?: "").put("logo", m.logoUrl ?: "")
            .put("season", m.season ?: -1).put("episode", m.episode ?: -1).put("next", m.nextUrl ?: "")
            .put("t", System.currentTimeMillis())
        val keep = known.entries.sortedByDescending { it.value.optLong("t") }.take(MAX_MEDIA)
        val o = JSONObject(); keep.forEach { (k, v) -> o.put(k, v) }
        known.keys.retainAll(keep.map { it.key }.toSet())
        prefs.edit().putString("media", o.toString()).apply()
    }

    fun isLan(url: String): Boolean { loadKnown(); return known.containsKey(url) }

    fun mediaFor(url: String): com.rollandostudio.universaltvcast.cast.Media? {
        loadKnown()
        val o = known[url] ?: return null
        fun s(k: String) = o.optString(k).ifEmpty { null }
        return com.rollandostudio.universaltvcast.cast.Media(
            url = url, title = o.optString("title"), mime = s("mime"), fileName = s("file"),
            posterUrl = s("poster"), backdropUrl = s("backdrop"), logoUrl = s("logo"),
            season = o.optInt("season", -1).takeIf { it >= 0 }, episode = o.optInt("episode", -1).takeIf { it >= 0 },
            nextUrl = s("next"),
            // Reprendre là où l'on s'était arrêté (quelques secondes avant).
            startSec = watched.value[url]?.takeIf { it.progress != null }?.let { it.pos - 5 } ?: 0.0,
        )
    }

    /** Nom de l'appareil d'où vient la vidéo (pour « … ne répond pas »). */
    fun deviceName(url: String): String = known[url]?.optString("device")?.ifEmpty { null } ?: android.net.Uri.parse(url).host.orEmpty()

    /**
     * La vidéo est-elle encore là (appareil allumé, fichier présent) ? Une vraie demande à l'appareil, pas seulement
     * « le port répond » (certains réseaux répondent à sa place). 6 s au plus ; à appeler hors du fil principal.
     */
    fun reachable(url: String): Boolean = runCatching {
        if (url.startsWith("content:")) Smb.open(android.net.Uri.parse(url)).exists()
        else {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000
                c.responseCode in 200..399 || c.responseCode == 405
            } finally { c.disconnect() }
        }
    }.getOrDefault(false)

    @Synchronized
    fun progress(url: String, pos: Double, dur: Double, done: Boolean = false) {
        if (!isLan(url)) return
        val cur = watched.value
        val w = Watch(pos, dur, done || cur[url]?.done == true && pos < 1)
        val next = (cur - url) + (url to w)
        _watched.value = if (next.size > MAX_WATCH) next.entries.drop(next.size - MAX_WATCH).associate { it.key to it.value } else next
        val o = JSONObject()
        _watched.value.forEach { (k, v) -> o.put(k, org.json.JSONArray().put(v.pos).put(v.dur).put(v.done)) }
        prefs.edit().putString("watched", o.toString()).apply()
    }

    fun finished(url: String) { val w = watched.value[url]; progress(url, w?.pos ?: 0.0, w?.dur ?: 0.0, done = true) }

    // ------------------------------------------------------------------ dossiers favoris

    /** Un dossier du réseau mis en favori (★) : il ressort sur l'accueil, ouvert d'un appui. */
    data class Folder(val kind: LanDevice.Kind, val host: String, val port: Int, val deviceName: String,
        val ref: String, val title: String, val share: String? = null, val location: String? = null) {
        val key: String get() = "${kind.name}:$host:$port:$ref"
        fun device() = LanDevice(kind, deviceName, host, port, location = location, share = share)
    }

    private const val MAX_FOLDERS = 30
    private val _folders = kotlinx.coroutines.flow.MutableStateFlow<List<Folder>>(emptyList())
    val folders: kotlinx.coroutines.flow.StateFlow<List<Folder>> get() {
        if (!loaded) { loaded = true; _folders.value = readFolders() }
        return _folders
    }
    @Volatile private var loaded = false

    private fun readFolders(): List<Folder> = runCatching {
        val a = JSONArray(prefs.getString("folders", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Folder(LanDevice.Kind.valueOf(o.getString("kind")), o.getString("host"), o.optInt("port", -1),
                o.getString("device"), o.getString("ref"), o.getString("title"),
                o.optString("share").ifEmpty { null }, o.optString("location").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    fun isFolder(d: LanDevice, ref: String) = folders.value.any { it.kind == d.kind && it.host == d.host && it.port == d.port && it.ref == ref }

    @Synchronized
    fun toggleFolder(d: LanDevice, ref: String, title: String) {
        val cur = folders.value
        val f = Folder(d.kind, d.host, d.port, d.name, ref, title, d.share, d.location)
        val next = if (cur.any { it.key == f.key }) cur.filter { it.key != f.key } else (cur + f).takeLast(MAX_FOLDERS)
        _folders.value = next
        val a = JSONArray()
        next.forEach { x ->
            a.put(JSONObject().put("kind", x.kind.name).put("host", x.host).put("port", x.port).put("device", x.deviceName)
                .put("ref", x.ref).put("title", x.title).put("share", x.share.orEmpty()).put("location", x.location.orEmpty()))
        }
        prefs.edit().putString("folders", a.toString()).apply()
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
