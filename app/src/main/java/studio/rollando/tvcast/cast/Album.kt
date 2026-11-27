package studio.rollando.tvcast.cast

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.rollando.tvcast.R
import studio.rollando.tvcast.data.Library
import studio.rollando.tvcast.dlna.Dlna
import studio.rollando.tvcast.dlna.Renderer
import studio.rollando.tvcast.dlna.TvError
import studio.rollando.tvcast.net.Mime
import studio.rollando.tvcast.net.Net
import java.net.SocketTimeoutException

/** L'album : photos et vidéos du téléphone affichées sur la télé, une par une ou en diaporama. */
internal object Album {

    var slideJob: Job? = null
    private var photosSince = 0L
    var pendingPhotos: Boolean // comme pendingCast : valable 2 minutes
        get() = photosSince > 0 && System.currentTimeMillis() - photosSince < 120_000
        set(v) { photosSince = if (v) System.currentTimeMillis() else 0L }
    @Volatile var shownAt = 0L
    /** Photos déjà préparées (ou en préparation) pour la télé : la suivante s'affiche sans attendre. */
    private val jpegs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<android.graphics.Bitmap>>()

    private fun jpegAsync(uri: String) = jpegs.getOrPut(uri) {
        TvCast.scope.async(Dispatchers.Default) { Photos.decode(TvCast.app, Uri.parse(uri)) }
    }

    /** Diaporama en flux vidéo continu (pas d'écran noir entre les photos). */
    @Volatile private var stream: SlideStream? = null
    @Volatile private var streamOnTv = false
    /** La télé a refusé le flux pour cet album : on revient aux photos une par une. */
    @Volatile var streamFailed = false

    fun stopStream() {
        stream?.stop()
        stream = null
        TvCast.proxy.slides = null
        streamOnTv = false
    }

    /** Ouvre le flux du diaporama sur la télé (une seule fois par album). */
    private suspend fun sendStreamToTv(chosen: Renderer, st: SlideStream): Renderer {
        val base = TvCast.proxy.baseUrl()
        val title = TvCast.app.getString(R.string.home_photos)
        if (chosen.cast) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { st.awaitSegments(3, 8000) }
            try {
                CastControl.load(chosen, "$base/s/live.m3u8", "application/x-mpegurl", title, null, null, 0.0, live = true)
                // Le lecteur Cast peut refuser le flux un peu plus tard : on revient alors aux photos.
                TvCast.scope.launch {
                    delay(7000)
                    if (stream === st && streamOnTv && CastControl.lastError() != null) {
                        Log.i("TvCastSlides", "flux refusé par le lecteur Cast : photos une par une")
                        streamFailed = true
                        stopStream()
                        TvCast._state.value.slides?.let { sl -> TvCast.exec { showItem(sl.index) } }
                    }
                }
                return chosen
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fb = chosen.fallback ?: throw e
                CastControl.disconnect()
                return sendStreamToTv(fb, st)
            }
        }
        kotlinx.coroutines.withContext(Dispatchers.IO) { st.awaitSegments(1, 5000) }
        val url = "$base/s/live.ts"
        TvSteps.replaceMedia(chosen, url, Dlna.didl(title, url, "video/mpeg", null, false))
        Dlna.play(chosen)
        return chosen
    }

    /** Prépare les photos voisines pendant que celle-ci est affichée, et oublie les lointaines. */
    private fun prefetchAround(sl: Slides, idx: Int) {
        val n = sl.uris.size
        val near = listOf(1, -1, 2).map { (idx + it).mod(n) }.filter { it != idx && it !in sl.videos }
        near.forEach { jpegAsync(sl.uris[it]) }
        val keep = (near + idx).map { sl.uris[it] }.toSet()
        jpegs.keys.filter { it !in keep }.forEach { jpegs.remove(it) }
    }

    /** Affiche sur la télé les photos et vidéos choisies, une par une (diaporama possible). */
    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        TvCast.pollJob?.cancel(); TvCast.seekJob?.cancel(); slideJob?.cancel()
        jpegs.clear()
        stopStream()
        streamFailed = false
        val videos = uris.indices.filter { i ->
            val t = runCatching { TvCast.app.contentResolver.getType(uris[i]) }.getOrNull().orEmpty()
            t.startsWith("video/") || (t.isEmpty() && Mime.isMedia(Mime.guess(uris[i].toString())))
        }.toSet()
        TvCast._state.update {
            it.copy(media = null, slides = Slides(uris.map(Uri::toString), videos = videos), phase = Phase.LOADING, notice = null,
                isHls = false, position = 0.0, duration = 0.0, idle = false, subDelay = 0.0)
        }
        TvCast.exec { showItem(0) }
    }

    suspend fun showItem(i: Int) {
        val sl = TvCast._state.value.slides ?: return
        val idx = i.mod(sl.uris.size)
        if (idx in sl.videos) playAlbumVideo(idx) else showPhoto(idx)
    }

    /** Pas de télé choisie : on lance la recherche, l'album s'affichera dès qu'elle est trouvée. */
    private fun needTv(): Boolean {
        if (TvCast._state.value.renderer != null) return false
        pendingPhotos = true
        TvCast._state.update { it.copy(phase = Phase.IDLE, notice = Notice(R.string.notice_pick_tv)) }
        TvCast.discover()
        return true
    }

    private suspend fun showPhoto(idx: Int) {
        val sl = TvCast._state.value.slides ?: return
        if (needTv()) return
        val chosen = TvCast._state.value.renderer ?: return
        Net.localIp() ?: throw NoWifi()
        TvCast.pollJob?.cancel()
        TvCast._state.update {
            it.copy(slides = it.slides?.copy(index = idx), media = null, phase = Phase.SENDING, idle = false,
                position = 0.0, duration = 0.0)
        }
        val uri = Uri.parse(sl.uris[idx])
        val bmp = try {
            jpegAsync(sl.uris[idx]).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            jpegs.remove(sl.uris[idx]); throw e
        }
        prefetchAround(sl, idx)

        // Diaporama en flux vidéo : la télé garde le même lecteur ouvert, la photo change par un fondu.
        if (!streamFailed && Library.smoothSlides.value) {
            val st = stream?.takeIf { it.alive } ?: SlideStream.start()?.also {
                stopStream(); stream = it; TvCast.proxy.slides = it
            }
            if (st != null) {
                st.show(bmp)
                if (!streamOnTv) {
                    try {
                        val used = sendStreamToTv(chosen, st)
                        TvCast.via = if (used !== chosen) used else null
                        streamOnTv = true
                        Log.i("TvCastSlides", "diaporama en flux sur ${used.name} (${if (used.cast) "HLS Cast" else "MPEG-TS DLNA"})")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.i("TvCastSlides", "flux refusé (${e.message}) : photos une par une")
                        streamFailed = true
                        stopStream()
                    }
                }
                if (streamOnTv) {
                    shownAt = System.currentTimeMillis()
                    CastService.start(TvCast.app)
                    TvCast._state.update { it.copy(phase = Phase.PLAYING) }
                    return
                }
            } else streamFailed = true
        }

        val jpeg = kotlinx.coroutines.withContext(Dispatchers.Default) { Photos.jpeg(bmp) }
        val imgId = TvCast.proxy.registerImage(jpeg)
        val url = "${TvCast.proxy.baseUrl()}/img/$imgId/photo.jpg"
        val name = Intake.nameOf(TvCast.app, uri)
        var r = chosen
        if (chosen.cast) {
            try {
                CastControl.loadPhoto(chosen, url, name)
                r = chosen
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                r = chosen.fallback ?: throw e
                CastControl.disconnect()
            }
        }
        if (!r.cast) {
            showImageDlna(r, url, imgId, Dlna.didl(name, url, "image/jpeg", null, false))
        }
        TvCast.via = if (r !== chosen) r else null
        shownAt = System.currentTimeMillis()
        CastService.start(TvCast.app)
        TvCast._state.update { it.copy(phase = Phase.PLAYING) }
    }

    /**
     * Affiche une photo sur une télé DLNA et vérifie qu'elle la télécharge vraiment. Après une vidéo, certaines
     * télés (Philips Titan OS) ferment leur lecteur : la première commande peut se perdre pendant qu'il rouvre.
     * Sans téléchargement au bout de 4 s, on renvoie la photo une fois.
     */
    private suspend fun showImageDlna(r: Renderer, url: String, imgId: String, meta: String) {
        for (attempt in 0..1) {
            try {
                // Changement direct (une seule commande) ; sinon pause préalable, comme pour une vidéo.
                try {
                    Dlna.setUriQuick(r, url, meta)
                } catch (e: TvError) {
                    TvSteps.replaceMedia(r, url, meta)
                }
                runCatching { Dlna.playQuick(r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                Log.i("TvCast", "la télé ne répond pas pour la photo (essai ${attempt + 1})")
                if (attempt == 1) throw e
                delay(800)
                continue
            }
            val end = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < end && !TvCast.proxy.imageFetched(imgId)) delay(150)
            if (TvCast.proxy.imageFetched(imgId)) return
            Log.i("TvCast", "photo non téléchargée par la télé : nouvel envoi")
            delay(500)
        }
    }

    /** Vidéo de l'album : lue en entier, puis l'album passe à l'élément suivant. */
    private suspend fun playAlbumVideo(idx: Int) {
        val sl = TvCast._state.value.slides ?: return
        if (needTv()) return
        TvCast.pollJob?.cancel()
        val uri = Uri.parse(sl.uris[idx])
        stopStream()
        TvCast.proxy.reset()
        TvCast.forgetSource()
        val m = Media(url = uri.toString(), title = Intake.nameOf(TvCast.app, uri), mime = TvCast.app.contentResolver.getType(uri))
        TvCast._state.update {
            it.copy(slides = it.slides?.copy(index = idx), media = m, isHls = false, splitAudio = false, subIndex = -1,
                position = 0.0, duration = 0.0, subDelay = 0.0, idle = false)
        }
        prefetchAround(sl, idx)
        TvCast.castAt(0.0)
    }

    /** Élément demandé (le dernier appui gagne) et vrai si son affichage attend déjà son tour. */
    @Volatile private var wantedIndex = 0
    @Volatile private var showQueued = false

    /**
     * Les appuis rapprochés sont regroupés : si la télé est lente, on n'empile pas les photos, seule la
     * dernière demandée est envoyée. Le téléphone affiche la nouvelle photo tout de suite.
     */
    fun photoAt(i: Int) {
        val sl = TvCast._state.value.slides ?: return
        wantedIndex = i.mod(sl.uris.size)
        TvCast._state.update { it.copy(slides = it.slides?.copy(index = wantedIndex)) }
        shownAt = System.currentTimeMillis()
        if (showQueued) return
        showQueued = true
        TvCast.exec {
            showQueued = false
            showItem(wantedIndex)
        }
    }

    fun nextPhoto(delta: Int) {
        val sl = TvCast._state.value.slides ?: return
        photoAt((if (showQueued) wantedIndex else sl.index) + delta)
    }

    /** Diaporama : une photo toutes les 6 secondes ; une vidéo est lue jusqu'au bout. */
    fun toggleSlideshow() {
        val sl = TvCast._state.value.slides ?: return
        val on = !sl.auto
        TvCast._state.update { it.copy(slides = it.slides?.copy(auto = on)) }
        slideJob?.cancel()
        if (!on) return
        shownAt = System.currentTimeMillis()
        if (TvCast._state.value.idle || TvCast._state.value.phase == Phase.STOPPED) TvCast.exec { showItem(sl.index + 1) }
        slideJob = TvCast.scope.launch {
            while (isActive) {
                delay(500)
                val cur = TvCast._state.value.slides ?: break
                if (!cur.auto) break
                if (cur.isVideo || TvCast.busy || TvCast._state.value.phase != Phase.PLAYING) continue
                if (System.currentTimeMillis() - shownAt < 6000) continue
                shownAt = System.currentTimeMillis()
                kotlinx.coroutines.withContext(TvCast.cmd) { runCatching { showItem(cur.index + 1) } }
            }
        }
    }
}
