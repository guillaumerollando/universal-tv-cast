package com.rollandostudio.universaltvcast.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Le style de l'appli et de l'écran d'attente de la télé (pack soutien) : couleur d'accent, fond noir OLED,
 * écran d'attente (classique, photo, horloge), message, écran « vivant », icône de l'appli.
 * Gratuit pour tous : l'ambre, le fond nuit, l'écran d'attente classique, l'icône d'origine.
 */
object Look {
    /** Couleur d'accent : (nom de clé, couleur ARGB). La première est celle d'origine, gratuite. */
    enum class Accent(val argb: Long) {
        AMBER(0xFFFFB86B), CORAL(0xFFFF8F7A), MINT(0xFF7FE0B8), AZURE(0xFF7EB8FF), LAVENDER(0xFFC4A8FF), ROSE(0xFFFF8AC6),
        // Plus vives, toujours lisibles sur le fond sombre.
        FLAME(0xFFFF6E4A), TURQUOISE(0xFF3FD6E0), GOLD(0xFFFFD23F)
    }

    enum class Idle { CLASSIC, PHOTO, CLOCK }

    /** Icônes de l'appli (alias du lanceur) : la première est celle d'origine. */
    enum class Icon(val alias: String) {
        NIGHT(".LauncherNight"), OCEAN(".LauncherOcean"), PLUM(".LauncherPlum"), FOREST(".LauncherForest"), BLACK(".LauncherBlack")
    }

    private const val PREFS = "look"
    /** Photos de l'écran d'attente et de l'ambiance (« Mes photos »). */
    const val MAX_PHOTOS = 30
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _accent = MutableStateFlow(Accent.AMBER)
    val accent: StateFlow<Accent> = _accent
    private val _oled = MutableStateFlow(false)
    val oled: StateFlow<Boolean> = _oled
    private val _idle = MutableStateFlow(Idle.CLASSIC)
    val idle: StateFlow<Idle> = _idle
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live
    private val _icon = MutableStateFlow(Icon.NIGHT)
    val icon: StateFlow<Icon> = _icon
    private val _photos = MutableStateFlow<List<File>>(emptyList())
    val photos: StateFlow<List<File>> = _photos

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = prefs
        _accent.value = runCatching { Accent.valueOf(p.getString("accent", "AMBER")!!) }.getOrDefault(Accent.AMBER)
        _oled.value = p.getBoolean("oled", false)
        _idle.value = runCatching { Idle.valueOf(p.getString("idle", "CLASSIC")!!) }.getOrDefault(Idle.CLASSIC)
        _message.value = p.getString("message", "").orEmpty()
        _live.value = p.getBoolean("live", false)
        _icon.value = runCatching { Icon.valueOf(p.getString("icon", "NIGHT")!!) }.getOrDefault(Icon.NIGHT)
        _photos.value = photoDir().listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.name }.orEmpty()
    }

    /** Les réglages du pack ne valent que pour qui l'a (sinon : le style d'origine, sans rien effacer). */
    private fun pack() = Supporter.unlocked.value

    /** Ce qui s'applique vraiment (le choix enregistré, ou l'origine sans le pack). */
    fun accentNow() = if (pack()) accent.value else Accent.AMBER
    fun oledNow() = pack() && oled.value
    fun idleNow() = if (pack()) idle.value else Idle.CLASSIC
    fun messageNow() = if (pack()) message.value.trim() else ""
    fun liveNow() = pack() && live.value && idleNow() != Idle.CLASSIC

    fun setAccent(a: Accent) { _accent.value = a; prefs.edit().putString("accent", a.name).apply() }
    fun setOled(on: Boolean) { _oled.value = on; prefs.edit().putBoolean("oled", on).apply() }
    fun setIdle(i: Idle) { _idle.value = i; prefs.edit().putString("idle", i.name).apply() }
    fun setMessage(m: String) { _message.value = m.take(60); prefs.edit().putString("message", m.take(60)).apply() }
    fun setLive(on: Boolean) { _live.value = on; prefs.edit().putBoolean("live", on).apply() }

    /**
     * Choisit l'icône de l'écran d'accueil (un alias du lanceur). Elle n'est posée qu'en quittant l'appli ([applyIcon]) :
     * éteindre l'alias qui a lancé l'appli ferme sa tâche, ce qui la ferait disparaître sous les doigts.
     */
    fun setIcon(i: Icon) { _icon.value = i; prefs.edit().putString("icon", i.name).apply() }

    /** Pose l'icône choisie (rien si elle l'est déjà). Sans le pack : l'icône d'origine. */
    fun applyIcon() {
        if (!::app.isInitialized) return
        val want = if (pack()) icon.value else Icon.NIGHT
        val pm = app.packageManager
        fun cn(i: Icon) = android.content.ComponentName(app.packageName, app.packageName + i.alias)
        val on = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val off = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val current = runCatching { pm.getComponentEnabledSetting(cn(want)) }.getOrNull()
        // NIGHT est allumée par défaut dans le manifeste ; les autres éteintes par défaut.
        val alreadyOn = current == on || (current == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && want == Icon.NIGHT)
        if (alreadyOn && Icon.entries.none { it != want && runCatching { pm.getComponentEnabledSetting(cn(it)) }.getOrNull() == on }) return
        runCatching { pm.setComponentEnabledSetting(cn(want), on, android.content.pm.PackageManager.DONT_KILL_APP) }
        Icon.entries.filter { it != want }.forEach {
            runCatching { pm.setComponentEnabledSetting(cn(it), off, android.content.pm.PackageManager.DONT_KILL_APP) }
        }
    }

    // ------------------------------------------------------------------ photos de l'écran d'attente

    private fun photoDir() = File(app.filesDir, "idle").apply { mkdirs() }

    /**
     * Garde une copie réduite (1920 px, JPEG) des photos choisies : elles restent même si l'original bouge, et ne
     * pèsent que quelques centaines de Ko chacune. 12 au plus ; les nouvelles remplacent les anciennes.
     */
    fun setPhotos(uris: List<Uri>) {
        val dir = photoDir()
        dir.listFiles()?.forEach { it.delete() }
        val out = uris.take(MAX_PHOTOS).mapIndexedNotNull { i, u ->
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                var k = 1
                while (maxOf(o.outWidth, o.outHeight) / (k * 2) >= 1920) k *= 2
                val bmp = app.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = k }) }
                    ?: return@runCatching null
                val f = File(dir, "%02d.jpg".format(i))
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                bmp.recycle()
                f
            }.getOrNull()
        }
        _photos.value = out
    }
}
