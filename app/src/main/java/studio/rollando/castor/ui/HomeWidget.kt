package studio.rollando.castor.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import studio.rollando.castor.MainActivity
import studio.rollando.castor.R
import studio.rollando.castor.data.Library
import studio.rollando.castor.net.Http
import java.util.concurrent.Executors

/**
 * Widget de l'écran d'accueil du téléphone : la vidéo à reprendre (affiche, titre, épisode, avancement) et les
 * quatre dernières chaînes. Un appui relance directement la lecture, sans passer par le site.
 */
class HomeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        const val ACTION_RESUME = "studio.rollando.castor.WIDGET_RESUME"
        const val ACTION_CHANNEL = "studio.rollando.castor.WIDGET_CHANNEL"
        const val EXTRA_KEY = "key"

        private val worker = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        /** Affiches et logos déjà chargés (le widget est redessiné souvent : avancement de la lecture). */
        private val images = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        private var pending: Runnable? = null

        /** Redessine le widget (regroupé : au plus une fois toutes les deux secondes). */
        fun refresh(context: Context) {
            val app = context.applicationContext
            pending?.let(main::removeCallbacks)
            val r = Runnable { worker.execute { runCatching { draw(app) } } }
            pending = r
            main.postDelayed(r, 2000)
        }

        private fun draw(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, HomeWidget::class.java))
            if (ids.isEmpty()) return
            Library.init(ctx)
            val recents = Library.recents.value
            val resume = resumeItems(recents, Library.bookmarks.value).firstOrNull()
            val channels = recents.filter { it.live }.take(4)
            val v = RemoteViews(ctx.packageName, R.layout.widget_home)

            if (resume != null) {
                v.setTextViewText(R.id.resume_label, ctx.getString(R.string.home_resume))
                v.setTextViewText(R.id.resume_title, resume.title)
                val ep = when {
                    resume.season != null && resume.episode != null -> ctx.getString(R.string.resume_season_episode, resume.season, resume.episode)
                    resume.episode != null -> ctx.getString(R.string.bookmark_episode, resume.episode)
                    else -> null
                }
                v.setTextViewText(R.id.resume_sub, listOfNotNull(ep, resume.host.takeIf { it.isNotEmpty() }).joinToString(" · "))
                val p = resume.progress
                v.setViewVisibility(R.id.resume_progress, if (p != null) View.VISIBLE else View.GONE)
                if (p != null) v.setProgressBar(R.id.resume_progress, 1000, (p * 1000).toInt(), false)
                val poster = resume.image?.let { image(it, 360) }
                if (poster != null) v.setImageViewBitmap(R.id.poster, poster) else v.setImageViewResource(R.id.poster, R.drawable.ic_logo)
                v.setViewVisibility(R.id.resume_play, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.resume, intent(ctx, ACTION_RESUME, resume.key, 1))
            } else {
                // Rien à reprendre : une invitation, et l'appui ouvre l'appli.
                v.setTextViewText(R.id.resume_title, ctx.getString(R.string.app_name))
                v.setTextViewText(R.id.resume_sub, ctx.getString(R.string.widget_empty))
                v.setViewVisibility(R.id.resume_progress, View.GONE)
                v.setViewVisibility(R.id.resume_play, View.GONE)
                v.setImageViewResource(R.id.poster, R.drawable.ic_logo)
                v.setOnClickPendingIntent(R.id.resume, intent(ctx, Intent.ACTION_MAIN, null, 1))
            }

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
                v.setTextViewText(name, c.channelName)
                v.setContentDescription(cell, c.channelName)
                val bmp = c.poster?.let { image(it, 200) }
                v.setViewVisibility(logo, if (bmp != null) View.VISIBLE else View.GONE)
                v.setViewVisibility(name, if (bmp != null) View.INVISIBLE else View.VISIBLE)
                if (bmp != null) v.setImageViewBitmap(logo, bmp)
                v.setOnClickPendingIntent(cell, intent(ctx, ACTION_CHANNEL, c.key, 10 + i))
            }
            mgr.updateAppWidget(ids, v)
        }

        private fun intent(ctx: Context, action: String, key: String?, code: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (key != null) i.putExtra(EXTRA_KEY, key)
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Image réduite (côté le plus long ≈ [max] px), gardée en mémoire ; null si elle ne se charge pas. */
        private fun image(url: String, max: Int): Bitmap? {
            images.get(url)?.let { return it }
            if (!url.startsWith("http")) return null
            val bytes = runCatching { Http.fetch(url, emptyMap(), connectTimeout = 5000).bytes }.getOrNull() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scale = max.toFloat() / maxOf(raw.width, raw.height)
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
            images.put(url, bmp)
            return bmp
        }
    }
}
