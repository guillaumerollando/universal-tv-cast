package studio.rollando.tvcast.cast

import android.content.Context
import studio.rollando.tvcast.dlna.Renderer
import studio.rollando.tvcast.dlna.TvError

/**
 * Version libre (F-Droid) : sans la bibliothèque Google Cast, qui n'est pas libre. Aucun appareil Cast n'est
 * découvert, la télé passe par le DLNA. Même interface que la version Play pour que le reste du code ne change pas.
 */
object CastControl {
    var onDevices: ((List<Renderer>) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    data class Sub(val url: String, val name: String, val lang: String?)

    fun init(app: Context) {}
    fun scan(active: Boolean) {}

    private fun absent(): Nothing = throw TvError("Google Cast absent de la version libre")

    suspend fun load(r: Renderer, url: String, mime: String, title: String, poster: String?, sub: Sub?, startSec: Double, live: Boolean, autoplay: Boolean = true): Unit = absent()
    suspend fun loadPhoto(r: Renderer, url: String, title: String): Unit = absent()
    suspend fun play(r: Renderer): Unit = absent()
    suspend fun pause(r: Renderer): Unit = absent()
    suspend fun stop(r: Renderer) {}
    suspend fun seek(r: Renderer, sec: Double): Unit = absent()
    suspend fun snapshot(): Triple<Double, Double, String?> = Triple(0.0, 0.0, "STOPPED")
    suspend fun lastError(): Int? = null
    suspend fun volume(): Int = 0
    suspend fun setVolume(v: Int) {}
    fun disconnect() {}
}
