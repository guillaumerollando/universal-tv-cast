package studio.rollando.castor.browser

import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import studio.rollando.castor.R
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.cast.Media
import studio.rollando.castor.data.Library
import studio.rollando.castor.ui.Nav
import studio.rollando.castor.ui.Tab
import java.io.ByteArrayInputStream

/** Le navigateur intégré, gardé en vie quand on change d'onglet. */
@SuppressLint("SetJavaScriptEnabled")
/** Ce qui empêche la page de s'afficher, dit clairement à la place d'un écran noir. */
data class WebProblem(val kind: Kind, val url: String, val code: Int = 0) {
    enum class Kind { OFFLINE, NOT_FOUND, TIMEOUT, SSL, OTHER, BLANK }
    val host: String get() = Uri.parse(url).host?.removePrefix("www.") ?: url
}

class BrowserController(context: Context) {
    val sniffer = VideoSniffer()

    /**
     * Met en pause les vidéos et sons de la page : quand la lecture part sur le téléphone ou la télé, le lecteur du
     * site ne doit pas continuer derrière (deux sons, ou notre lecteur qui perd le son du téléphone).
     */
    fun pauseMedia() = handler.post {
        runCatching {
            webView.evaluateJavascript(
                "(function(){function p(d){try{d.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}});" +
                    "d.querySelectorAll('iframe').forEach(function(f){try{p(f.contentDocument)}catch(e){}})}catch(e){}}p(document)})()", null)
        }
    }

    init { studio.rollando.castor.cast.Castor.pausePageMedia = ::pauseMedia }
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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
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
        addJavascriptInterface(sniffer.Bridge(), "CastorBridge")
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
                android.util.Log.i("CastorWeb", "erreur de page ${request.url.host} : ${error.errorCode} ${error.description}")
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
                blankCheck?.let { handler.removeCallbacks(it) }
                newPage(u)
                if (!u.startsWith("about:")) this@BrowserController.url = u
                sniffer.pageUrl = u
                refreshNav(view)
            }

            override fun onPageFinished(view: WebView, u: String) {
                android.util.Log.i("CastorWeb", "page chargée : ${Uri.parse(u).host} · vidéos repérées ${sniffer.found.value.size} · pubs bloquées $blockedCount")
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
                icon?.let { studio.rollando.castor.ui.Favicons.save(view.context, host, it) }
            }
            override fun onReceivedTitle(view: WebView, t: String?) {
                this@BrowserController.title = t.orEmpty()
                // Titre nettoyé (sans le nom du site ni « Regarder maintenant : ») pour la télé et la liste.
                sniffer.pageTitle = PageTitle.clean(t.orEmpty(), Uri.parse(view.url ?: "").host ?: "")
            }
            // Fenêtres surgissantes (souvent des pubs) : refusées.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
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
        blankCheck?.let { handler.removeCallbacks(it) }
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
                    android.util.Log.i("CastorWeb", "page vide : ${Uri.parse(u).host} (statut $mainStatus)")
                    report(WebProblem(WebProblem.Kind.BLANK, u, mainStatus))
                }
            }
        }
        blankCheck = r
        handler.postDelayed(r, delay)
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(b: BrowserController) {
    // En arrivant sur l'onglet : la barre d'adresse est là, et une page restée vide est signalée.
    LaunchedEffect(Unit) { b.barVisible = true; b.checkBlankNow() }
    // Les vidéos de moins d'une minute (pubs, aperçus) ne sont pas proposées : personne ne veut les caster.
    val allFound by b.sniffer.found.collectAsState()
    val found = allFound.filterNot { it.short }
    val bookmarks by Library.bookmarks.collectAsState()
    var showFound by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val focus = LocalFocusManager.current
    val c = MaterialTheme.colorScheme
    // Étoile pleine aussi sur un autre épisode d'une série suivie.
    val isBookmarked = remember(bookmarks, b.url) { Library.isBookmarked(b.url) }

    BackHandler(enabled = b.canGoBack) { b.webView.goBack() }
    // Clavier fermé (touche retour) : le champ d'adresse se libère, la barre peut de nouveau se cacher.
    // Toucher la barre sélectionne toute l'adresse : on le refait juste après le toucher, qui sinon place le
    // curseur au milieu (et le texte tapé s'insérait dans l'ancienne adresse).
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing) {
            kotlinx.coroutines.delay(60)
            input = input.copy(selection = TextRange(0, input.text.length))
        }
    }
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(imeVisible) { if (!imeVisible && editing) focus.clearFocus() }

    // La marge sous la barre d'état (donnée par l'écran) reste fixe ; seule la barre d'adresse glisse.
    val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntSize>(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    Column(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            // Toujours visible en cas de problème : sans elle, plus moyen de réessayer ni de changer d'adresse.
            visible = b.barVisible || (editing && imeVisible) || !b.started || b.problem != null,
            enter = androidx.compose.animation.expandVertically(slide, expandFrom = Alignment.Top) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.shrinkVertically(slide, shrinkTowards = Alignment.Top) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
        ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (b.started) IconButton(onClick = { b.goHome() }) {
                Icon(Icons.Rounded.Home, stringResource(R.string.cd_browser_home))
            } else IconButton(onClick = { b.webView.goBack() }, enabled = b.canGoBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
            }
            TextField(
                value = if (editing) input else TextFieldValue(Uri.parse(b.url).host?.removePrefix("www.") ?: b.url),
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.browser_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (editing && input.text.isNotEmpty()) IconButton(onClick = { input = TextFieldValue("") }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear))
                    }
                },
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { b.load(input.text); keyboard?.hide(); focus.clearFocus(force = true) }),
                modifier = Modifier.weight(1f).height(52.dp).onFocusChanged {
                    // Comme dans tout navigateur : toucher la barre sélectionne l'adresse, taper la remplace.
                    if (it.isFocused && !editing) input = TextFieldValue(b.url, TextRange(0, b.url.length))
                    editing = it.isFocused
                },
            )
            if (editing && b.started && b.url.isNotEmpty()) {
                // Barre touchée : copier ou partager l'adresse de la page (pour la finir ailleurs, l'envoyer…).
                val ctx = androidx.compose.ui.platform.LocalContext.current
                IconButton(onClick = {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newRawUri(b.title, Uri.parse(b.url)))
                    if (android.os.Build.VERSION.SDK_INT < 33)
                        android.widget.Toast.makeText(ctx, R.string.link_copied, android.widget.Toast.LENGTH_SHORT).show()
                    keyboard?.hide(); focus.clearFocus(force = true)
                }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.cd_copy_link)) }
                IconButton(onClick = {
                    keyboard?.hide(); focus.clearFocus(force = true)
                    runCatching {
                        ctx.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, b.url).putExtra(android.content.Intent.EXTRA_SUBJECT, b.title),
                            null))
                    }
                }) { Icon(Icons.Rounded.Share, stringResource(R.string.cd_share_link)) }
            } else if (b.started && b.url.isNotEmpty()) {
                IconButton(onClick = {
                    val clean = PageTitle.clean(b.title, Uri.parse(b.url).host ?: "")
                    // Épisode d'une série : le favori porte le nom de la série. Site ou film : le titre tel quel.
                    val name = if (Episodes.find(b.url) != null || PageTitle.episode(clean) != null) PageTitle.series(clean) else clean
                    Library.toggleBookmark(name, b.url, b.sniffer.pageImage)
                }) {
                    Icon(
                        if (isBookmarked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        stringResource(if (isBookmarked) R.string.cd_bookmark_remove else R.string.cd_bookmark_add),
                        tint = if (isBookmarked) c.primary else c.onSurfaceVariant,
                    )
                }
            }
            if (!editing && b.canGoForward) IconButton(onClick = { b.webView.goForward() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.cd_forward))
            }
            if (!editing || !b.started) IconButton(onClick = { b.webView.reload() }, enabled = b.started) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.cd_reload))
            }
        }
        }
        if (b.started && b.progress < 100) LinearProgressIndicator(progress = { b.progress / 100f }, modifier = Modifier.fillMaxWidth())

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (b.started) {
                AndroidView(
                    factory = { (b.webView.parent as? ViewGroup)?.removeView(b.webView); b.webView },
                    // Page en échec : la vue web (restée noire) est masquée, sinon elle recouvre la barre d'adresse.
                    update = { it.visibility = if (b.problem != null) android.view.View.INVISIBLE else android.view.View.VISIBLE },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
            } else {
                StartPage(onOpen = { b.load(it) })
            }
            b.problem?.takeIf { b.started }?.let { p -> ProblemPanel(p, b) }
            // Ce que l'appli fait toute seule avec les favoris, dit clairement (et pas de surprise).
            BookmarkNotice(Modifier.align(Alignment.TopCenter).padding(12.dp))
            // Série : bouton « Épisode 13 → » quand l'adresse contient un numéro d'épisode.
            val ep = remember(b.url) { Episodes.find(b.url) }
            androidx.compose.animation.AnimatedVisibility(
                visible = b.started && ep != null && (ep.explicit || found.isNotEmpty()),
                enter = scaleIn(), exit = scaleOut(),
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            ) {
                ep?.let { e ->
                    ExtendedFloatingActionButton(
                        onClick = { b.load(e.next) },
                        icon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
                        text = { Text(stringResource(R.string.browser_next_episode, e.number + 1)) },
                        containerColor = c.secondaryContainer, contentColor = c.onSecondaryContainer,
                    )
                }
            }
            // La vignette de la vidéo en cours se range au-dessus de ce bouton seulement quand il est là.
            val hasFab = found.isNotEmpty()
            androidx.compose.runtime.LaunchedEffect(hasFab) { studio.rollando.castor.ui.Nav.browserVideoButton.value = hasFab }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { studio.rollando.castor.ui.Nav.browserVideoButton.value = false } }
            androidx.compose.animation.AnimatedVisibility(
                visible = hasFab,
                enter = scaleIn(), exit = scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showFound = true },
                    icon = { Icon(Icons.Rounded.Cast, null) },
                    text = { Text(pluralStringResource(R.plurals.browser_found, found.size, found.size)) },
                    containerColor = c.primary, contentColor = c.onPrimary,
                )
            }
        }
    }

    if (showFound) {
        ModalBottomSheet(onDismissRequest = { showFound = false }) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.browser_sheet_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.browser_sheet_hint), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(found, key = { i, f -> "$i|${f.url}" }) { i, f ->
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                // La durée tout de suite visible : 25 min, c'est l'épisode ; 0:12, une pub.
                                durationText(f.duration)?.let { d ->
                                    Spacer(Modifier.width(8.dp))
                                    Text(d, style = MaterialTheme.typography.labelLarge, color = c.primary)
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    if (f.silent) stringResource(R.string.browser_silent) else null,
                                    if (f.qualities > 0) stringResource(R.string.browser_auto_quality, f.qualities) +
                                        (if (f.maxHeight > 0) " · ${f.maxHeight}p" else "")
                                    else if (f.hls) "HLS" else "MP4/WebM",
                                    f.host, if (i == 0) stringResource(R.string.browser_best) else null,
                                )
                                    .joinToString(" · "),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center) {
                                Icon(if (f.hls) Icons.Rounded.Stream else Icons.Rounded.Movie, null, tint = c.primary)
                            }
                        },
                        trailingContent = { Icon(Icons.Rounded.Cast, null) },
                        colors = ListItemDefaults.colors(containerColor = c.surfaceContainerLow),
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) {
                            showFound = false
                            // On retient la page (l'épisode) : l'accueil proposera d'y revenir.
                            Library.advanceBookmark(f.pageUrl, image = b.sniffer.pageImage)
                            Castor.open(
                                Media(
                                    url = f.url,
                                    title = f.pageTitle.takeIf { it.isNotBlank() }?.let { PageTitle.display(it, Uri.parse(f.pageUrl).host.orEmpty()) } ?: f.label,
                                    mime = if (f.hls) "application/vnd.apple.mpegurl" else null,
                                    headers = f.headers,
                                    pageUrl = f.pageUrl,
                                    nextUrl = b.sniffer.nextEpisode ?: Episodes.find(f.pageUrl)?.next?.takeIf { it.isNotEmpty() },
                                    audioUrl = f.audio,
                                    // Même épisode déjà commencé : on reprend là où on s'était arrêté.
                                    startSec = Library.resumeAt(f.pageUrl),
                                    // L'image de la page (celle du favori) : en-tête du lecteur, fond, « Reprendre », notification.
                                    posterUrl = (b.sniffer.pageImage ?: Library.bookmarkFor(f.pageUrl)?.image),
                                    backdropUrl = (b.sniffer.pageImage ?: Library.bookmarkFor(f.pageUrl)?.image),
                                )
                            )
                            Nav.tab.value = Tab.PLAYER
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StartPage(onOpen: (String) -> Unit) {
    val bookmarks by Library.bookmarks.collectAsState()
    val recents by Library.recents.collectAsState()
    val hidden by Library.hiddenSites.collectAsState()
    val sites = studio.rollando.castor.ui.siteItems(recents, bookmarks, hidden)
    val series = bookmarks.filter { it.isSeries }
    val c = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Mes sites d'abord (c'est l'accueil du navigateur), puis les séries suivies.
        if (sites.isNotEmpty()) {
            item {
                Text(stringResource(R.string.home_sites), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            }
            item {
                studio.rollando.castor.ui.SitesGrid(sites, Modifier.padding(horizontal = 16.dp), onOpen)
                Spacer(Modifier.height(16.dp))
            }
        }
        // Des sites gratuits et légaux à essayer (ceux pas encore dans « Mes sites »), tant qu'on en a peu.
        if (sites.size < 6) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                studio.rollando.castor.ui.StarterSites(onOpen, sites.map { it.host }.toSet())
            }
            Spacer(Modifier.height(16.dp))
        }
        if (series.isNotEmpty()) {
            item {
                Text(stringResource(R.string.home_bookmarks), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            }
            item {
                studio.rollando.castor.ui.FavoritesRow(series, onOpen)
                Spacer(Modifier.height(16.dp))
            }
        }
        item {
            Spacer(Modifier.height(if (sites.isEmpty() && series.isEmpty()) 24.dp else 8.dp))
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(c.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Search, null, Modifier.size(48.dp), tint = c.primary)
            }
            Spacer(Modifier.height(16.dp))
        }
        item { Text(stringResource(R.string.browser_start_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
        item {
            Text(stringResource(R.string.browser_start_body), style = MaterialTheme.typography.bodyLarge,
                color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}

/** « 25 min », « 1 h 32 », « 0:12 » ou « Direct » ; null si inconnue. */
@Composable
private fun durationText(d: Double?): String? = when {
    d == null -> null
    d == 0.0 -> stringResource(R.string.browser_live)
    d < 60 -> "0:%02d".format(d.toInt())
    d < 3600 -> "${(d / 60).toInt()} min"
    else -> "${(d / 3600).toInt()} h %02d".format(((d % 3600) / 60).toInt())
}

/** Petit message quand on suit une série ou que son marque-page avance tout seul (4 s, lu par TalkBack). */
@Composable
private fun BookmarkNotice(modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        Library.events.collect { e ->
            text = when (e) {
                is studio.rollando.castor.data.BookmarkEvent.Followed ->
                    ctx.getString(if (e.series) R.string.bookmark_followed_series else R.string.bookmark_added, e.title)
                is studio.rollando.castor.data.BookmarkEvent.Advanced ->
                    e.episode?.let { ctx.getString(R.string.bookmark_advanced, e.title, it) }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(text) { if (text != null) { kotlinx.coroutines.delay(4500); text = null } }
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null, modifier = modifier,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(),
    ) {
        val c = MaterialTheme.colorScheme
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp), color = c.secondaryContainer, contentColor = c.onSecondaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, null, tint = c.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** À la place d'un écran noir : ce qui se passe, en clair, et quoi faire. */
@Composable
private fun ProblemPanel(p: WebProblem, b: BrowserController) {
    val c = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val (title, body) = when (p.kind) {
        WebProblem.Kind.OFFLINE -> stringResource(R.string.web_err_offline) to stringResource(R.string.web_err_offline_body)
        WebProblem.Kind.NOT_FOUND -> stringResource(R.string.web_err_notfound) to stringResource(R.string.web_err_notfound_body, p.host)
        WebProblem.Kind.TIMEOUT -> stringResource(R.string.web_err_timeout) to p.host
        WebProblem.Kind.SSL -> stringResource(R.string.web_err_ssl) to
            stringResource(R.string.web_err_ssl_body, p.host) + " " + stringResource(R.string.web_err_ssl_hint)
        WebProblem.Kind.OTHER -> stringResource(R.string.web_err_generic) to p.host
        WebProblem.Kind.BLANK -> stringResource(R.string.web_blank) to stringResource(R.string.web_blank_body)
    }
    val adBlock by Library.adBlock.collectAsState()
    Column(
        Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 40.dp)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(c.error.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(
                when (p.kind) {
                    WebProblem.Kind.OFFLINE -> Icons.Rounded.WifiOff
                    WebProblem.Kind.SSL -> Icons.Rounded.GppBad
                    else -> Icons.Rounded.ErrorOutline
                }, null, Modifier.size(44.dp), tint = c.error,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        if (p.code >= 400) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.web_err_code, p.code), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = { b.retry() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.web_retry))
        }
        if (p.kind == WebProblem.Kind.BLANK && adBlock) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { b.retry(withoutAds = true) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.web_retry_noads), textAlign = TextAlign.Center)
            }
        }
        if (p.kind != WebProblem.Kind.OFFLINE) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(p.url))) }
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.web_open_outside), textAlign = TextAlign.Center)
            }
        }
        TextButton(onClick = { b.goHome() }) { Text(stringResource(R.string.web_back_home)) }
    }
}
