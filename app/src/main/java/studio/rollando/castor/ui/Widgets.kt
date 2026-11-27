package studio.rollando.castor.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import studio.rollando.castor.MainActivity
import studio.rollando.castor.R
import studio.rollando.castor.data.Library
import studio.rollando.castor.data.Recent
import studio.rollando.castor.net.Http
import java.util.concurrent.Executors

/*
 * Les widgets de l'écran d'accueil du téléphone. Chacun répond à un usage :
 *  - HomeWidget          « Reprendre + chaînes »   (4×2)
 *  - ResumeSitesWidget   « Reprendre + sites »     (4×2) : pour qui regarde surtout des séries
 *  - StackWidget         « Mes vidéos en cours »   (4×2) : toutes les reprises, en petites affiches (grille)
 *  - ChannelsWidget      « Mes chaînes »           (4×1, s'agrandit) : la télé en direct seulement
 *  - ResumeWidget        « Reprendre »             (2×2) : la dernière vidéo, affiche en grand
 *  - PhotosWidget        « Photos et vidéos »      (4×1) : ouvre tout de suite le choix des photos ou d'une vidéo
 * Un appui relance directement la lecture (MainActivity.widget), sans passer par le site.
 */

class HomeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetKit.refresh(context, now = true)
}

class ResumeSitesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetKit.refresh(context, now = true)
}

class StackWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetKit.refresh(context, now = true)
}

class ChannelsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetKit.refresh(context, now = true)
}

class PhotosWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetKit.refresh(context, now = true)
}

class ResumeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetKit.refresh(context, now = true)
}

internal object WidgetKit {
    const val ACTION_RESUME = "studio.rollando.castor.WIDGET_RESUME"
    const val ACTION_CHANNEL = "studio.rollando.castor.WIDGET_CHANNEL"
    const val ACTION_SITE = "studio.rollando.castor.WIDGET_SITE"
    const val ACTION_PICK = "studio.rollando.castor.WIDGET_PICK"
    const val EXTRA_WHAT = "what"
    const val EXTRA_KEY = "key"
    const val EXTRA_URL = "url"

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Affiches et logos déjà chargés (les widgets sont redessinés souvent : avancement de la lecture). */
    private val images = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var pending: Runnable? = null

    /** Ce que montrent les widgets. */
    class Data(val resume: List<ResumeItem>, val channels: List<Recent>, val sites: List<SiteItem>)

    fun data(ctx: Context): Data {
        Library.init(ctx)
        val recents = Library.recents.value
        val bookmarks = Library.bookmarks.value
        return Data(resumeItems(recents, bookmarks), recents.filter { it.live }, siteItems(recents, bookmarks, Library.hiddenSites.value))
    }

    /** Redessine tous les widgets (regroupé : au plus une fois toutes les deux secondes, sauf [now]). */
    fun refresh(context: Context, now: Boolean = false) {
        val app = context.applicationContext
        pending?.let(main::removeCallbacks)
        val r = Runnable { worker.execute { runCatching { drawAll(app) }.onFailure { android.util.Log.w("CastorWidget", "dessin", it) } } }
        pending = r
        main.postDelayed(r, if (now) 0 else 2000)
    }

    private fun ids(ctx: Context, mgr: AppWidgetManager, c: Class<*>) = mgr.getAppWidgetIds(ComponentName(ctx, c))

    private fun drawAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val home = ids(ctx, mgr, HomeWidget::class.java)
        val sites = ids(ctx, mgr, ResumeSitesWidget::class.java)
        val stack = ids(ctx, mgr, StackWidget::class.java)
        val channels = ids(ctx, mgr, ChannelsWidget::class.java)
        val small = ids(ctx, mgr, ResumeWidget::class.java)
        val photos = ids(ctx, mgr, PhotosWidget::class.java)
        // Photos et vidéos : rien qui dépende de l'historique, seulement les deux boutons.
        if (photos.isNotEmpty()) mgr.updateAppWidget(photos, RemoteViews(ctx.packageName, R.layout.widget_photos).apply {
            setOnClickPendingIntent(R.id.photos, pick(ctx, "photos", 30))
            setOnClickPendingIntent(R.id.videos, pick(ctx, "videos", 31))
        })
        if (home.isEmpty() && sites.isEmpty() && stack.isEmpty() && channels.isEmpty() && small.isEmpty()) return
        val d = data(ctx)
        if (home.isNotEmpty()) mgr.updateAppWidget(home, wide(ctx, d, withSites = false))
        if (sites.isNotEmpty()) mgr.updateAppWidget(sites, wide(ctx, d, withSites = true))
        if (small.isNotEmpty()) mgr.updateAppWidget(small, small(ctx, d))
        if (stack.isNotEmpty()) {
            mgr.updateAppWidget(stack, collection(ctx, R.layout.widget_stack, R.id.videos, "stack"))
            mgr.notifyAppWidgetViewDataChanged(stack, R.id.videos)
        }
        if (channels.isNotEmpty()) {
            mgr.updateAppWidget(channels, collection(ctx, R.layout.widget_channels, R.id.grid, "channels"))
            mgr.notifyAppWidgetViewDataChanged(channels, R.id.grid)
        }
    }

    /** « Épisode 3 · arte.tv » sous le titre. */
    fun subtitle(ctx: Context, r: ResumeItem): String {
        val ep = when {
            r.season != null && r.episode != null -> ctx.getString(R.string.resume_season_episode, r.season, r.episode)
            r.episode != null -> ctx.getString(R.string.bookmark_episode, r.episode)
            else -> null
        }
        return listOfNotNull(ep, r.host.takeIf { it.isNotEmpty() }).joinToString(" · ")
    }

    private fun progress(v: RemoteViews, r: ResumeItem) {
        val p = r.progress
        v.setViewVisibility(R.id.resume_progress, if (p != null) View.VISIBLE else View.GONE)
        if (p != null) v.setProgressBar(R.id.resume_progress, 1000, (p * 1000).toInt(), false)
    }

    /** Remplit une carte à affiche en grand (petit « Reprendre », cartes défilantes). */
    fun fillPosterCard(ctx: Context, v: RemoteViews, r: ResumeItem) {
        v.setTextViewText(R.id.resume_title, r.title)
        v.setTextViewText(R.id.resume_sub, subtitle(ctx, r).ifEmpty { ctx.getString(R.string.home_resume) })
        progress(v, r)
        val poster = r.image?.let { image(it, 480) }
        if (poster != null) v.setImageViewBitmap(R.id.poster, poster) else v.setImageViewResource(R.id.poster, R.drawable.widget_poster_empty)
        v.setViewVisibility(R.id.resume_play, View.VISIBLE)
    }

    /** Grand widget : la vidéo à reprendre, puis une rangée de chaînes ou de sites. */
    private fun wide(ctx: Context, d: Data, withSites: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, if (withSites) R.layout.widget_resume_sites else R.layout.widget_home)
        val resume = d.resume.firstOrNull()
        if (resume != null) {
            v.setTextViewText(R.id.resume_label, ctx.getString(R.string.home_resume))
            v.setTextViewText(R.id.resume_title, resume.title)
            v.setTextViewText(R.id.resume_sub, subtitle(ctx, resume))
            progress(v, resume)
            val poster = resume.image?.let { image(it, 360) }
            if (poster != null) v.setImageViewBitmap(R.id.poster, poster) else v.setImageViewResource(R.id.poster, R.drawable.widget_poster_empty)
            v.setViewVisibility(R.id.resume_play, View.VISIBLE)
            v.setOnClickPendingIntent(R.id.resume, activity(ctx, ACTION_RESUME, resume.key, null, 1))
        } else {
            // Rien à reprendre : une invitation, et l'appui ouvre l'appli.
            v.setTextViewText(R.id.resume_title, ctx.getString(R.string.app_name))
            v.setTextViewText(R.id.resume_sub, ctx.getString(R.string.widget_empty))
            v.setViewVisibility(R.id.resume_progress, View.GONE)
            v.setViewVisibility(R.id.resume_play, View.GONE)
            v.setImageViewResource(R.id.poster, R.drawable.widget_poster_empty)
            v.setOnClickPendingIntent(R.id.resume, activity(ctx, Intent.ACTION_MAIN, null, null, 1))
        }
        if (withSites) sitesRow(ctx, v, d.sites.take(4)) else channelsRow(ctx, v, d.channels.take(4))
        return v
    }

    private fun channelsRow(ctx: Context, v: RemoteViews, channels: List<Recent>) {
        v.setViewVisibility(R.id.channels, if (channels.isEmpty()) View.GONE else View.VISIBLE)
        val cells = listOf(
            Triple(R.id.ch0, R.id.ch0_name, R.id.ch0_logo), Triple(R.id.ch1, R.id.ch1_name, R.id.ch1_logo),
            Triple(R.id.ch2, R.id.ch2_name, R.id.ch2_logo), Triple(R.id.ch3, R.id.ch3_name, R.id.ch3_logo),
        )
        cells.forEachIndexed { i, (cell, name, logo) ->
            val c = channels.getOrNull(i)
            // Case vide gardée (invisible) : les autres gardent leur largeur.
            v.setViewVisibility(cell, if (c == null) View.INVISIBLE else View.VISIBLE)
            if (c == null) return@forEachIndexed
            fillChannel(v, c, name, logo, cell)
            v.setContentDescription(cell, c.channelName)
            v.setOnClickPendingIntent(cell, activity(ctx, ACTION_CHANNEL, c.key, null, 10 + i))
        }
    }

    private fun sitesRow(ctx: Context, v: RemoteViews, sites: List<SiteItem>) {
        v.setViewVisibility(R.id.sites, if (sites.isEmpty()) View.GONE else View.VISIBLE)
        val cells = listOf(
            Triple(R.id.site0, R.id.site0_name, R.id.site0_icon), Triple(R.id.site1, R.id.site1_name, R.id.site1_icon),
            Triple(R.id.site2, R.id.site2_name, R.id.site2_icon), Triple(R.id.site3, R.id.site3_name, R.id.site3_icon),
        )
        cells.forEachIndexed { i, (cell, name, icon) ->
            val s = sites.getOrNull(i)
            v.setViewVisibility(cell, if (s == null) View.INVISIBLE else View.VISIBLE)
            if (s == null) return@forEachIndexed
            v.setTextViewText(name, s.title)
            v.setContentDescription(cell, s.title)
            val bmp = favicon(ctx, s.host)
            v.setViewVisibility(icon, if (bmp != null) View.VISIBLE else View.GONE)
            if (bmp != null) v.setImageViewBitmap(icon, bmp)
            v.setOnClickPendingIntent(cell, activity(ctx, ACTION_SITE, null, s.url, 20 + i))
        }
    }

    /** Une chaîne : son logo s'il se charge, sinon son nom. Logo en partie noir : tuile claire, sinon il disparaît. */
    fun fillChannel(v: RemoteViews, c: Recent, name: Int, logo: Int, cell: Int) {
        v.setTextViewText(name, c.channelName)
        val bmp = c.poster?.let { image(it, 200) }
        val light = bmp != null && bmp.hasAlpha() && Logos.darkShare(bmp) > 0.18f
        v.setInt(cell, "setBackgroundResource", if (light) R.drawable.widget_tile_light else R.drawable.widget_tile)
        v.setViewVisibility(logo, if (bmp != null) View.VISIBLE else View.GONE)
        v.setViewVisibility(name, if (bmp != null) View.INVISIBLE else View.VISIBLE)
        if (bmp != null) v.setImageViewBitmap(logo, bmp)
    }

    /** Petit « Reprendre » : la dernière vidéo, affiche en grand. */
    private fun small(ctx: Context, d: Data): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_resume)
        val r = d.resume.firstOrNull()
        if (r != null) {
            fillPosterCard(ctx, v, r)
            v.setContentDescription(R.id.root, ctx.getString(R.string.home_resume) + ", " + r.title)
            v.setOnClickPendingIntent(R.id.root, activity(ctx, ACTION_RESUME, r.key, null, 2))
        } else {
            v.setTextViewText(R.id.resume_title, ctx.getString(R.string.app_name))
            v.setTextViewText(R.id.resume_sub, ctx.getString(R.string.widget_empty))
            v.setViewVisibility(R.id.resume_progress, View.GONE)
            v.setViewVisibility(R.id.resume_play, View.GONE)
            v.setImageViewResource(R.id.poster, R.drawable.widget_poster_empty)
            v.setOnClickPendingIntent(R.id.root, activity(ctx, Intent.ACTION_MAIN, null, null, 2))
        }
        return v
    }

    /** Widget à liste (cartes défilantes, grille de chaînes) : rempli par [WidgetListService]. */
    private fun collection(ctx: Context, layout: Int, list: Int, kind: String): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        val svc = Intent(ctx, WidgetListService::class.java).putExtra("kind", kind).setData(Uri.parse("castor://widget/$kind"))
        @Suppress("DEPRECATION")
        v.setRemoteAdapter(list, svc)
        v.setEmptyView(list, R.id.empty)
        // Chaque élément complète cette demande (action, clé) : voir WidgetListService.
        val template = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        v.setPendingIntentTemplate(list, PendingIntent.getActivity(ctx, if (kind == "stack") 100 else 101, template, flags))
        return v
    }

    private fun pick(ctx: Context, what: String, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).setAction(ACTION_PICK).putExtra(EXTRA_WHAT, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun activity(ctx: Context, action: String, key: String?, url: String?, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (key != null) i.putExtra(EXTRA_KEY, key)
        if (url != null) i.putExtra(EXTRA_URL, url)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Icône du site, déjà enregistrée par le navigateur. */
    private fun favicon(ctx: Context, host: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(Favicons.file(ctx, host).path) }.getOrNull()

    /** Image réduite (côté le plus long ≈ [max] px), gardée en mémoire ; null si elle ne se charge pas. */
    fun image(url: String, max: Int): Bitmap? {
        images.get("$max|$url")?.let { return it }
        if (!url.startsWith("http")) return null
        val bytes = runCatching { Http.fetch(url, emptyMap(), connectTimeout = 5000).bytes }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = max.toFloat() / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
        images.put("$max|$url", bmp)
        return bmp
    }
}

/** Remplit les widgets à liste : grilles « Mes vidéos en cours » et « Mes chaînes ». */
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext, intent.getStringExtra("kind") ?: "stack")

    private class Factory(val ctx: Context, val kind: String) : RemoteViewsFactory {
        private var resume: List<ResumeItem> = emptyList()
        private var channels: List<Recent> = emptyList()

        override fun onCreate() {}
        override fun onDataSetChanged() {
            val d = WidgetKit.data(ctx)
            resume = d.resume.take(12)
            channels = d.channels.take(16)
        }
        override fun onDestroy() {}
        override fun getCount() = if (kind == "stack") resume.size else channels.size
        override fun getViewAt(position: Int): RemoteViews {
            if (kind == "stack") {
                val v = RemoteViews(ctx.packageName, R.layout.widget_video_item)
                val r = resume.getOrNull(position) ?: return v
                v.setTextViewText(R.id.resume_title, r.title)
                val p = r.progress
                v.setViewVisibility(R.id.resume_progress, if (p != null) View.VISIBLE else View.GONE)
                if (p != null) v.setProgressBar(R.id.resume_progress, 1000, (p * 1000).toInt(), false)
                val poster = r.image?.let { WidgetKit.image(it, 320) }
                if (poster != null) v.setImageViewBitmap(R.id.poster, poster) else v.setImageViewResource(R.id.poster, R.drawable.widget_poster_empty)
                v.setContentDescription(R.id.item, r.title)
                v.setOnClickFillInIntent(R.id.item, Intent().setAction(WidgetKit.ACTION_RESUME).putExtra(WidgetKit.EXTRA_KEY, r.key))
                return v
            }
            val v = RemoteViews(ctx.packageName, R.layout.widget_channel_item)
            val c = channels.getOrNull(position) ?: return v
            WidgetKit.fillChannel(v, c, R.id.name, R.id.logo, R.id.item)
            v.setContentDescription(R.id.item, c.channelName)
            v.setOnClickFillInIntent(R.id.item, Intent().setAction(WidgetKit.ACTION_CHANNEL).putExtra(WidgetKit.EXTRA_KEY, c.key))
            return v
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
