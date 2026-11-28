package com.rollandostudio.universaltvcast.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sauvegarde des données de l'appli dans un seul fichier (ZIP), pour changer de téléphone sans compte ni serveur
 * (téléphones sans Google compris) : historique et « Reprendre », favoris, appareils du réseau et dossiers suivis,
 * vu / avancement, listes de chaînes, personnalisation et photos de l'écran d'attente, réglages.
 *
 * Pas dans le fichier : les caches (ils se refont seuls), l'achat du pack (Google le revérifie), et les mots de
 * passe du réseau, chiffrés par une clé propre à ce téléphone : on les ressaisit sur le nouveau.
 */
object Backup {
    private const val FORMAT = 1
    /** Fichiers de réglages sauvegardés (les autres sont des caches ou propres à ce téléphone). */
    private val PREFS = listOf("library", "lan", "look", "iptv", "review", "ambiance")
    /** Dossiers sauvegardés, sous filesDir. */
    private val DIRS = listOf("iptv", "idle")

    class BadFile : Exception()

    /** Écrit la sauvegarde dans [uri] (choisi par l'utilisateur). */
    fun export(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("universal-tv-cast.json"))
                zip.write(JSONObject().put("app", ctx.packageName).put("format", FORMAT)
                    .put("date", System.currentTimeMillis()).toString().toByteArray())
                zip.closeEntry()
                for (name in PREFS) {
                    val all = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    val o = JSONObject()
                    for ((k, v) in all) {
                        if (name == "lan" && k.startsWith("pw:")) continue // mot de passe chiffré par ce téléphone
                        o.put(k, typed(v ?: continue))
                    }
                    zip.putNextEntry(ZipEntry("prefs/$name.json"))
                    zip.write(o.toString().toByteArray())
                    zip.closeEntry()
                }
                for (d in DIRS) {
                    File(ctx.filesDir, d).walkTopDown().filter { it.isFile }.forEach { f ->
                        zip.putNextEntry(ZipEntry("files/" + f.relativeTo(ctx.filesDir).path))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } ?: throw java.io.IOException("fichier introuvable")
    }

    /**
     * Remplace les données de l'appli par celles de la sauvegarde [uri]. Vérifie d'abord que c'est bien une
     * sauvegarde de Universal TV Cast ([BadFile] sinon), puis écrit tout. L'appli doit redémarrer ensuite.
     */
    fun import(ctx: Context, uri: Uri) {
        // Première passe : lecture et vérification, sans rien toucher.
        val prefs = mutableMapOf<String, JSONObject>()
        val files = mutableMapOf<String, ByteArray>()
        var ok = false
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > 200L * 1024 * 1024) throw BadFile() // fichier anormalement gros
                    val n = e.name
                    when {
                        n == "universal-tv-cast.json" -> ok = runCatching { JSONObject(String(bytes)).optInt("format") in 1..FORMAT }.getOrDefault(false)
                        n.startsWith("prefs/") && n.endsWith(".json") -> {
                            val name = n.removePrefix("prefs/").removeSuffix(".json")
                            if (name in PREFS) prefs[name] = JSONObject(String(bytes))
                        }
                        n.startsWith("files/") && !n.contains("..") && DIRS.any { n.startsWith("files/$it/") } -> files[n.removePrefix("files/")] = bytes
                    }
                }
            }
        } ?: throw java.io.IOException("fichier introuvable")
        if (!ok) throw BadFile()
        // Seconde passe : on remplace.
        for ((name, o) in prefs) {
            val p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            // Les mots de passe déjà présents sur ce téléphone restent (ils ne sont jamais dans la sauvegarde).
            val keep = if (name == "lan") p.all.filterKeys { it.startsWith("pw:") } else emptyMap()
            val ed = p.edit().clear()
            o.keys().forEach { k -> put(ed, k, o.getJSONObject(k)) }
            keep.forEach { (k, v) -> if (v is String) ed.putString(k, v) }
            ed.commit()
        }
        for (d in DIRS) if (files.keys.any { it.startsWith("$d/") }) File(ctx.filesDir, d).deleteRecursively()
        for ((rel, bytes) in files) File(ctx.filesDir, rel).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    private fun typed(v: Any): JSONObject = when (v) {
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.filterIsInstance<String>()))
        else -> JSONObject().put("t", "s").put("v", v.toString())
    }

    private fun put(ed: android.content.SharedPreferences.Editor, k: String, o: JSONObject) {
        when (o.optString("t")) {
            "b" -> ed.putBoolean(k, o.getBoolean("v"))
            "i" -> ed.putInt(k, o.getInt("v"))
            "l" -> ed.putLong(k, o.getLong("v"))
            "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
            "set" -> o.getJSONArray("v").let { a -> ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            else -> ed.putString(k, o.optString("v"))
        }
    }

    /** Relance l'appli (après une restauration : tout est relu depuis les fichiers). */
    fun restart(activity: android.app.Activity) {
        val i = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(i)
        Runtime.getRuntime().exit(0)
    }
}
