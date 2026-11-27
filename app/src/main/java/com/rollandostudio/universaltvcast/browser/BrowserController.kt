package com.rollandostudio.universaltvcast.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rollandostudio.universaltvcast.cast.TvCast
import com.rollandostudio.universaltvcast.cast.Media
import com.rollandostudio.universaltvcast.data.Library
import java.io.ByteArrayInputStream

/** Le navigateur intégré, gardé en vie quand on change d'onglet. */
@SuppressLint("SetJavaScriptEnabled")
/** Ce qui empêche la page de s'afficher, dit clairement à la place d'un écran noir. */
data class WebProblem(val kind: Kind, val url: String, val code: Int = 0) {
    enum class Kind { OFFLINE, NOT_FOUND, TIMEOUT, SSL, OTHER, BLANK }
    val host: String get() = Uri.parse(url).host?.removePrefix("www.") ?: url
}

/**
 * Lance la lecture dans la page, son coupé : vidéos déjà là, sinon le bouton de lecture du lecteur (vidéo.js, JW Player,
 * Plyr, boutons « Lecture / Play »). Dans la page et dans les cadres du même site.
 */
private const val NUDGE_PLAY = """(function(){function go(d){try{
var vs=d.querySelectorAll('video');vs.forEach(function(v){try{v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){})}catch(e){}});
var b=d.querySelector('.vjs-big-play-button,.jw-display-icon-container,.plyr__control--overlaid,[data-plyr="play"],button[aria-label*="play" i],button[aria-label*="lecture" i],button[title*="play" i],button[title*="lecture" i],.play-button,.playButton,.btn-play');
if(b&&!vs.length)b.click();
d.querySelectorAll('iframe').forEach(function(f){try{go(f.contentDocument)}catch(e){}});
}catch(e){}}go(document)})()"""

class BrowserController(context: Context) {
    val sniffer = VideoSniffer()

    /**
     * Met en pause les vidéos et sons de la page : quand la lecture part sur le téléphone ou la télé, le lecteur du
     * site ne doit pas continuer derrière (deux sons, ou notre lecteur qui perd le son du téléphone).
     */
    fun pauseMedia() = mainHandler.post {
        runCatching {
            webView.evaluateJavascript(
                "(function(){function p(d){try{d.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}});" +
                    "d.querySelectorAll('iframe').forEach(function(f){try{p(f.contentDocument)}catch(e){}})}catch(e){}}p(document)})()", null)
        }
    }

    init {
        com.rollandostudio.universaltvcast.cast.TvCast.pausePageMedia = ::pauseMedia
        sniffer.onUserPlay = { mainHandler.post { onUserPlay() } }
        sniffer.onVideoPress = { mainHandler.post { longPress = LongPress(null, video = true) } }
        // Requêtes faites par le « service worker » d'un site (il charge parfois la vidéo à la place de la page) :
        // inspectées et filtrées comme les autres, sinon ces vidéos et ces pubs passeraient inaperçues.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) runCatching {
            androidx.webkit.ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : androidx.webkit.ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    if (Library.adBlock.value && adsAllowedHost == null && AdBlock.isAd(request.url)) {
                        blockedCount++
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    sniffer.inspect(request)
                    return null
                }
            })
        }
    }

    /** Envoie une vidéo trouvée sur la page au lecteur de l'appli (télé, ou téléphone). */
    fun castFound(f: Found) {
        // On retient la page (l'épisode) : l'accueil proposera d'y revenir.
        Library.advanceBookmark(f.pageUrl, image = sniffer.pageImage)
        TvCast.open(
            Media(
                url = f.url,
                title = f.pageTitle.takeIf { it.isNotBlank() }?.let { PageTitle.display(it, Uri.parse(f.pageUrl).host.orEmpty()) } ?: f.label,
                mime = if (f.hls) "application/vnd.apple.mpegurl" else null,
                headers = f.headers,
                pageUrl = f.pageUrl,
                nextUrl = sniffer.nextEpisode ?: Episodes.find(f.pageUrl)?.next?.takeIf { it.isNotEmpty() },
                audioUrl = f.audio,
                // Sous-titres que le lecteur du site propose (choisis ensuite dans « Sous-titres »).
                subs = sniffer.tracks,
                // Même épisode déjà commencé : on reprend là où on s'était arrêté.
                startSec = Library.resumeAt(f.pageUrl),
                // L'image de la page (celle du favori) : en-tête du lecteur, fond, « Reprendre », notification.
                posterUrl = (sniffer.pageImage ?: Library.bookmarkFor(f.pageUrl)?.image),
                backdropUrl = (sniffer.pageImage ?: Library.bookmarkFor(f.pageUrl)?.image),
            )
        )
    }

    /**
     * Passage automatique au lecteur de l'appli. Revenu sur une page dont la vidéo est encore dans le lecteur,
     * l'utilisateur peut l'y regarder sans être renvoyé au lecteur (pas de boucle).
     */
    private val autoOpened = HashSet<String>()
    /** Attente en cours : jusqu'à quand, en arrière-plan (épisode suivant) ou non, et la page d'avant. */
    private var autoUntil = 0L
    private var autoBackground = false
    private var autoFrom: String? = null
    private var autoSeenAt = 0L
    /** Appelé quand la vidéo part toute seule : l'écran de lecture s'affiche (sauf en arrière-plan, où il l'est déjà). */
    var onAutoCast: (() -> Unit)? = null
    /** Épisode suivant introuvable en arrière-plan : on montre la page pour que l'utilisateur lance la vidéo. */
    var onAutoGiveUp: (() -> Unit)? = null

    private fun pageKey(u: String) = u.substringBefore('#')

    /** Une vidéo de la page vient d'être lancée par l'utilisateur (lecteur du site) : direction le lecteur de l'appli. */
    private fun onUserPlay() {
        if (System.currentTimeMillis() < autoUntil) return
        // Déjà envoyée et toujours dans le lecteur : on laisse regarder dans la page (pas de boucle). Si autre chose
        // a été lancé depuis (une chaîne…), la relancer depuis la page la renvoie au lecteur comme la première fois.
        val playing = TvCast.state.value.media?.pageUrl?.let(::pageKey)
        if (pageKey(url) in autoOpened && playing == pageKey(url)) return
        arm(background = false, waitMs = 15_000)
    }

    /** Épisode suivant, chargé sans quitter l'écran de lecture : sa vidéo partira dès qu'elle sera repérée. */
    fun playNextInBackground(next: String) {
        // Même page que celle affichée (reprise de l'épisode ouvert) : elle est rechargée, sa vidéo compte.
        autoFrom = url.takeIf { pageKey(it) != pageKey(next) }
        load(next)
        arm(background = true, waitMs = 25_000)
        // Beaucoup de lecteurs ne chargent la vidéo qu'à la lecture : on la lance dans la page (son coupé).
        for (d in listOf(3000L, 6000L, 10_000L, 15_000L)) mainHandler.postDelayed({ nudgePlayback() }, d)
    }

    private fun arm(background: Boolean, waitMs: Long) {
        autoSeenAt = 0L
        autoBackground = background
        autoUntil = System.currentTimeMillis() + waitMs
        mainHandler.removeCallbacks(autoCheck)
        mainHandler.post(autoCheck)
    }

    private val autoCheck: Runnable = object : Runnable {
        override fun run() {
            if (autoUntil == 0L) return
            val page = pageKey(url)
            val ready = !autoBackground || (url.isNotEmpty() && page != autoFrom?.let(::pageKey))
            val best = if (ready) sniffer.found.value.firstOrNull { !it.short && !it.silent } else null
            // Une seconde de patience après la première vidéo vue : la liste complète (toutes les qualités) arrive
            // souvent juste après un fichier en petite qualité.
            if (best != null && autoSeenAt == 0L) autoSeenAt = System.currentTimeMillis()
            val settled = best != null && System.currentTimeMillis() - autoSeenAt > 1000
            when {
                best != null && settled -> {
                    manualSearch = false
                    autoSeenAt = 0L
                    autoUntil = 0L
                    autoOpened += page
                    android.util.Log.i("TvCastWeb", "vidéo lancée : passage automatique au lecteur (${if (autoBackground) "épisode suivant" else "lecture dans la page"})")
                    restoreGesture()
                    castFound(best)
                    onAutoCast?.invoke()
                }
                System.currentTimeMillis() > autoUntil -> {
                    autoUntil = 0L
                    restoreGesture()
                    // Vidéo lancée (ou cherchée) par l'utilisateur et rien de repéré : on le lui dit, avec une issue.
                    if (manualSearch || !autoBackground) {
                        android.util.Log.i("TvCastWeb", "vidéo lancée mais pas repérée${if (sniffer.drm) " (DRM)" else ""}")
                        manualSearch = false
                        notFound = true
                    }
                    if (autoBackground) { android.util.Log.i("TvCastWeb", "épisode suivant : pas de vidéo repérée, on montre la page"); onAutoGiveUp?.invoke() }
                }
                else -> mainHandler.postDelayed(this, 400)
            }
        }
    }

    /** Lance la lecture dans la page (son coupé), pour que le site charge la vidéo et que l'appli la repère. */
    private fun nudgePlayback() {
        if (autoUntil == 0L || !autoBackground || sniffer.found.value.any { !it.short }) return
        runCatching {
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.evaluateJavascript(NUDGE_PLAY, null)
        }
    }

    /**
     * Appui long dans la page : de quoi envoyer la vidéo même quand l'appli ne l'a pas repérée seule. [link] : le
     * lien touché (épisode, miniature), s'il y en a un.
     */
    data class LongPress(val link: String?, val video: Boolean = false)
    var longPress by mutableStateOf<LongPress?>(null)
    /** Vidéo lancée ou cherchée, rien de repéré : l'écran le dit (avec « Chercher encore », « Signaler ce site »). */
    var notFound by mutableStateOf(false)
    private var manualSearch = false

    /** La vidéo de la page est protégée (DRM) : rien à chercher. */
    val drm: Boolean get() = sniffer.drm

    /** « Lancer cette vidéo » (appui long sur la vidéo) : celle déjà repérée part tout de suite, sinon on la cherche. */
    fun playVideoNow() {
        val best = sniffer.found.value.firstOrNull { !it.short && !it.silent }
        if (best != null) { castFound(best); onAutoCast?.invoke() } else findVideoNow()
    }

    /** « Trouver la vidéo de cette page » : la vidéo déjà repérée part ; sinon on démarre le lecteur du site (son coupé). */
    fun findVideoNow() {
        notFound = false
        manualSearch = true
        runCatching { webView.settings.mediaPlaybackRequiresUserGesture = false; webView.evaluateJavascript(NUDGE_PLAY, null) }
        arm(background = false, waitMs = 12_000)
        for (d in listOf(2500L, 5000L)) mainHandler.postDelayed({ if (autoUntil != 0L) runCatching { webView.evaluateJavascript(NUDGE_PLAY, null) } }, d)
    }

    private fun restoreGesture() { runCatching { webView.settings.mediaPlaybackRequiresUserGesture = true } }
    /** Problème de la page en cours (erreur de chargement, page restée vide), ou null. */
    var problem by mutableStateOf<WebProblem?>(null)
    /** Signale un problème ; la barre montre l'adresse demandée (sinon elle resterait vide). */
    private fun report(p: WebProblem) {
        problem = p
        if (url.isEmpty() || url.startsWith("about:")) url = p.url
    }
    /** Site pour lequel l'utilisateur a demandé de réessayer sans bloqueur de pubs. */
    private var adsAllowedHost: String? = null
    private var mainStatus = 0
    /**
     * File du fil principal de l'appli. Pas « handler » : dans le bloc de la vue web, ce nom désignerait
     * View.getHandler(), vide quand la page n'est pas à l'écran (épisode suivant chargé depuis la lecture → plantage).
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var blankCheck: Runnable? = null
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var started by mutableStateOf(false)
    /** Barre d'adresse visible (cachée quand on descend dans la page, réaffichée en remontant). */
    var barVisible by mutableStateOf(true)
    /** Nombre de pubs bloquées depuis le lancement (pour le journal). */
    @Volatile var blockedCount = 0
    private var lastPage: String? = null

    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        sniffer.userAgent = settings.userAgentString
        // Appui long sur un lien (épisode, miniature), quand la page n'a encore aucune vidéo repérée :
        // « Lancer la vidéo de ce lien ». Partout ailleurs, l'appui long reste celui du téléphone (sélection, copier).
        setOnLongClickListener { v ->
            val hit = (v as WebView).hitTestResult
            val onLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            if (!onLink || sniffer.found.value.any { !it.short }) return@setOnLongClickListener false
            // Lien sous une image : l'adresse du lien est demandée à la page (l'extra est celle de l'image).
            val link = if (hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val msg = mainHandler.obtainMessage()
                v.requestFocusNodeHref(msg)
                msg.data.getString("url")?.takeIf { it.startsWith("http") } ?: hit.extra?.takeIf { it.startsWith("http") }
            } else hit.extra?.takeIf { it.startsWith("http") }
            if (link == null) return@setOnLongClickListener false
            longPress = LongPress(link)
            true
        }
        setOnScrollChangeListener { _, _, y, _, oldY ->
            val dy = y - oldY
            val show = when {
                y < 60 -> true
                dy > 14 -> false
                dy < -14 -> true
                else -> barVisible
            }
            if (show != barVisible) barVisible = show
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(sniffer.Bridge(), "TvCastBridge")
        // Le script de repérage tourne aussi dans les cadres (lecteurs intégrés venant d'un autre site).
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, VideoSniffer.SCRIPT, setOf("*")) }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (Library.adBlock.value && adsAllowedHost == null && AdBlock.isAd(request.url)) {
                    blockedCount++
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                sniffer.inspect(request)
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (scheme != "http" && scheme != "https") return true // intent://, market://… : on reste ici.
                if (Library.adBlock.value && adsAllowedHost == null && AdBlock.blocked(request.url)) return true
                return false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (!request.isForMainFrame) return
                val kind = when (error.errorCode) {
                    ERROR_HOST_LOOKUP -> if (isOffline(view.context)) WebProblem.Kind.OFFLINE else WebProblem.Kind.NOT_FOUND
                    ERROR_CONNECT -> if (isOffline(view.context)) WebProblem.Kind.OFFLINE else WebProblem.Kind.NOT_FOUND
                    ERROR_TIMEOUT -> WebProblem.Kind.TIMEOUT
                    ERROR_FAILED_SSL_HANDSHAKE -> WebProblem.Kind.SSL
                    else -> if (isOffline(view.context)) WebProblem.Kind.OFFLINE else WebProblem.Kind.OTHER
                }
                android.util.Log.i("TvCastWeb", "erreur de page ${request.url.host} : ${error.errorCode} ${error.description}")
                report(WebProblem(kind, request.url.toString()))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) mainStatus = response.statusCode
            }

            @android.annotation.SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel() // jamais de passe-droit sur un certificat invalide
                if (error.url == view.url || problem == null && Uri.parse(error.url).host == Uri.parse(view.url ?: "").host)
                    report(WebProblem(WebProblem.Kind.SSL, error.url))
            }

            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                barVisible = true
                if (!u.startsWith("about:")) {
                    if (problem?.url != u) problem = null
                    mainStatus = 0
                    if (adsAllowedHost != null && Uri.parse(u).host != adsAllowedHost) adsAllowedHost = null
                }
                blankCheck?.let { mainHandler.removeCallbacks(it) }
                newPage(u)
                if (!u.startsWith("about:")) this@BrowserController.url = u
                sniffer.pageUrl = u
                refreshNav(view)
            }

            override fun onPageFinished(view: WebView, u: String) {
                android.util.Log.i("TvCastWeb", "page chargée : ${Uri.parse(u).host} · vidéos repérées ${sniffer.found.value.size} · pubs bloquées $blockedCount")
                view.evaluateJavascript(VideoSniffer.SCRIPT, null)
                refreshNav(view)
                if (!u.startsWith("about:") && problem == null) scheduleBlankCheck(view, u)
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                // Sites « une seule page » : l'adresse change sans rechargement ; la liste doit suivre.
                newPage(u)
                if (!u.startsWith("about:")) this@BrowserController.url = u
                sniffer.pageUrl = u
                refreshNav(view)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@BrowserController.progress = newProgress }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                val host = Uri.parse(view.url ?: "").host?.removePrefix("www.") ?: return
                icon?.let { com.rollandostudio.universaltvcast.ui.Favicons.save(view.context, host, it) }
            }
            override fun onReceivedTitle(view: WebView, t: String?) {
                this@BrowserController.title = t.orEmpty()
                // Titre nettoyé (sans le nom du site ni « Regarder maintenant : ») pour la télé et la liste.
                sniffer.pageTitle = PageTitle.clean(t.orEmpty(), Uri.parse(view.url ?: "").host ?: "")
            }
            /**
             * Lien « nouvel onglet » (target=_blank) touché par l'utilisateur : la page s'ouvre ici, dans le même onglet
             * (sans ça, le lien ne faisait rien : résultats de Sepia Search, catalogues…). Les fenêtres qui s'ouvrent
             * toutes seules (pubs) restent refusées. On récupère l'adresse par une vue jetable, puis on la charge ici.
             */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (!isUserGesture || isDialog || resultMsg == null) return false
                var done = false
                val temp = WebView(view.context)
                fun take(v: WebView, url: String?) {
                    if (done || url.isNullOrBlank() || url == "about:blank") return
                    done = true
                    v.stopLoading()
                    view.post { v.destroy() }
                    val u = Uri.parse(url)
                    if (u.scheme != "http" && u.scheme != "https") return
                    if (Library.adBlock.value && adsAllowedHost == null && AdBlock.blocked(u)) return
                    android.util.Log.i("TvCastWeb", "lien « nouvel onglet » ouvert ici : ${u.host}")
                    view.loadUrl(url)
                }
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean { take(v, request.url.toString()); return true }
                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) = take(v, url)
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: run { temp.destroy(); return false }
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun isOffline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Page chargée mais rien à l'écran (ni texte, ni image, ni lecteur) au bout de quelques secondes : le plus
     * souvent un script bloqué ou un site qui refuse les navigateurs intégrés. On le dit, avec des solutions.
     */
    private fun scheduleBlankCheck(view: WebView, u: String, delay: Long = 4000) {
        blankCheck?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (view.url != u || problem != null || !started) return@Runnable
            view.evaluateJavascript(
                // Texte et médias de la page… et ce que contiennent ses composants web (contenu « isolé », que ni
                // innerText ni querySelectorAll ne voient : Internet Archive est fait ainsi).
                "(function(){var b=document.body;if(!b)return 0;" +
                    "var n=(b.innerText||'').trim().length+document.querySelectorAll('img,video,iframe,canvas,svg,embed,object').length;" +
                    "if(n)return n;var a=document.querySelectorAll('*');for(var i=0;i<a.length;i++){if(a[i].shadowRoot)return 1;}" +
                    "return 0;})()"
            ) { v ->
                if (v?.trim('"')?.toIntOrNull() == 0 && view.url == u && problem == null) {
                    android.util.Log.i("TvCastWeb", "page vide : ${Uri.parse(u).host} (statut $mainStatus)")
                    report(WebProblem(WebProblem.Kind.BLANK, u, mainStatus))
                }
            }
        }
        blankCheck = r
        mainHandler.postDelayed(r, delay)
    }

    /** Revérifie tout de suite que la page affichée n'est pas vide (retour sur l'onglet). */
    fun checkBlankNow() {
        val u = webView.url ?: return
        if (!started || problem != null || u.startsWith("about:") || progress < 100) return
        scheduleBlankCheck(webView, u, delay = 600)
    }

    fun retry(withoutAds: Boolean = false) {
        val p = problem ?: return
        if (withoutAds) adsAllowedHost = Uri.parse(p.url).host
        problem = null
        if (webView.url == p.url) webView.reload() else webView.loadUrl(p.url)
    }

    /** Nouvelle page (adresse différente, hors #ancre) : on oublie les vidéos de la précédente. */
    private fun newPage(u: String) {
        // On ignore l'ancre et les paramètres de position (?t=…) que certains lecteurs ajoutent en cours de lecture.
        val page = u.substringBefore('#').replace(Regex("([?&])(t|time|start|pos|position)=[^&]*&?"), "$1").trimEnd('?', '&')
        if (page != lastPage) {
            sniffer.reset()
            // Série suivie : ouvrir un épisode plus loin fait avancer le marque-page.
            Library.advanceBookmark(u, onlyForward = true)
        }
        lastPage = page
    }

    private fun refreshNav(v: WebView) {
        canGoBack = v.canGoBack()
        canGoForward = v.canGoForward()
    }

    /** Retour à la page d'accueil du navigateur (favoris, séries en cours). */
    fun goHome() {
        problem = null
        started = false
        url = ""
        title = ""
        sniffer.reset()
        webView.loadUrl("about:blank")
    }

    fun load(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        started = true
        problem = null
        barVisible = true
        webView.loadUrl(target)
    }
}
