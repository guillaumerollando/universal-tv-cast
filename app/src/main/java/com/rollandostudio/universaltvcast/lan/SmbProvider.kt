package com.rollandostudio.universaltvcast.lan

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import jcifs.smb.SmbRandomAccessFile
import java.io.FileNotFoundException

/**
 * Donne accès aux fichiers des dossiers partagés comme à un fichier du téléphone (« content:// ») : le relais vers
 * la télé, le lecteur du téléphone et la recherche de sous-titres les lisent sans rien savoir du réseau.
 * Rien n'est copié : chaque lecture va chercher les octets demandés sur l'ordinateur ou le NAS.
 * Privé à l'appli (non exporté).
 */
class SmbProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        LanStore.init(context!!)
        Catalog.init(context!!)
        com.rollandostudio.universaltvcast.data.ReviewPrompt.init(context!!)
        return true
    }

    override fun getType(uri: Uri): String = Lan.mimeOf(uri.lastPathSegment.orEmpty())

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        val size = runCatching { Smb.open(uri).length() }.getOrNull()
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment
                OpenableColumns.SIZE -> size
                else -> null
            }
        })
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("lecture seule")
        val file = try {
            Smb.open(uri)
        } catch (e: Exception) {
            throw FileNotFoundException(e.message)
        }
        val raf = try {
            SmbRandomAccessFile(file, "r")
        } catch (e: Exception) {
            Log.i(TAG, "fichier partagé illisible : ${e.message}")
            throw FileNotFoundException(e.message)
        }
        val size = raf.length()
        // Un fil par fichier ouvert : la télé et le téléphone peuvent lire deux fichiers sans s'attendre.
        val thread = HandlerThread("tvcast-smb").apply { start() }
        val sm = context!!.getSystemService(StorageManager::class.java)
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = size

            override fun onRead(offset: Long, n: Int, data: ByteArray): Int {
                try {
                    if (offset >= size) return 0
                    // Jamais au-delà de la fin du fichier : certains serveurs coupent la connexion plutôt que de répondre « fin ».
                    val want = minOf(n.toLong(), size - offset).toInt()
                    raf.seek(offset)
                    var done = 0
                    while (done < want) {
                        val k = raf.read(data, done, want - done)
                        if (k <= 0) break
                        done += k
                    }
                    return done
                } catch (e: Exception) {
                    Log.i(TAG, "lecture interrompue : ${e.message}")
                    throw ErrnoException("read", OsConstants.EIO)
                }
            }

            override fun onRelease() {
                runCatching { raf.close() }
                thread.quitSafely()
            }
        }, Handler(thread.looper))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    private companion object { const val TAG = "TvCastLan" }
}
