package com.rollandostudio.universaltvcast.lan

import android.net.Uri
import android.util.Log
import com.rollandostudio.universaltvcast.BuildConfig
import com.rollandostudio.universaltvcast.net.Net
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Dossiers partagés du réseau (SMB) : un ordinateur Windows ou Mac, un NAS, une box avec un disque.
 * La télé ne sait pas les lire : le téléphone ouvre le fichier et le relaie (voir [SmbProvider]).
 */
object Smb {
    /** Le partage demande un identifiant (ou celui donné est refusé). */
    class NeedLogin : Exception()

    private const val TAG = "TvCastLan"

    private val config by lazy {
        PropertyConfiguration(Properties().apply {
            // SMB 1 pour les vieux NAS et box, jusqu’à SMB 3.0.2 (Windows 10 et 11 l’acceptent ; la 3.1.1 de jcifs-ng
            // échoue à vérifier la signature avec certains serveurs).
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB302")
            // On parle à une adresse IP : pas de recherche de nom (WINS, diffusion) qui ferait attendre.
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.connTimeout", "6000")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "60000")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
        })
    }

    /** Contextes ouverts (un par appareil et identifiant) : la connexion est réutilisée d'un dossier à l'autre. */
    private val contexts = ConcurrentHashMap<String, CIFSContext>()

    private fun context(host: String, port: Int, user: String?, password: String?, domain: String?): CIFSContext {
        val key = "$host:$port|${user.orEmpty()}|${domain.orEmpty()}|${password?.hashCode() ?: 0}"
        return contexts.getOrPut(key) {
            val base = BaseContext(config)
            when {
                user.isNullOrEmpty() -> base.withGuestCrendentials()
                else -> base.withCredentials(NtlmPasswordAuthenticator(domain.orEmpty(), user, password.orEmpty()))
            }
        }
    }

    /** Ferme les connexions ouvertes (appli quittée, appareil oublié) : rien ne reste en mémoire. */
    fun closeAll() {
        contexts.values.forEach { runCatching { it.close() } }
        contexts.clear()
    }

    private fun root(host: String, port: Int) = if (port > 0 && port != 445) "smb://$host:$port/" else "smb://$host/"

    /** Contexte à utiliser pour un appareil : l'identifiant enregistré, sinon invité. */
    private fun contextFor(host: String, port: Int): CIFSContext {
        val s = LanStore.find(LanDevice.Kind.SHARE, host, port)
        return context(host, port, s?.user, s?.let { LanStore.password(it) }, s?.domain)
    }

    /** Fichier ou dossier [path] (« Films/Dune.mkv ») du partage [share]. */
    private fun file(ctx: CIFSContext, host: String, port: Int, share: String, path: List<String>, dir: Boolean): SmbFile {
        var f = SmbFile(SmbFile(root(host, port), ctx), "$share/")
        path.forEachIndexed { i, seg -> f = SmbFile(f, if (dir || i < path.size - 1) "$seg/" else seg) }
        return f
    }

    /**
     * Vérifie l'accès et enregistre l'appareil. [user] vide : accès invité.
     * @throws NeedLogin si l'appareil demande un identifiant (ou refuse celui-ci).
     */
    fun login(d: LanDevice, user: String?, password: String?, domain: String? = null, remember: Boolean = true): List<LanEntry> {
        val ctx = context(d.host, d.port, user, password, domain)
        val shares = try {
            listShares(ctx, d)
        } catch (e: SmbAuthException) {
            throw NeedLogin().apply { initCause(e) }
        } catch (e: jcifs.smb.SmbException) {
            if (!unreachable(e)) throw NeedLogin().apply { initCause(e) }
            throw e
        }
        if (remember) LanStore.save(LanStore.Saved(LanDevice.Kind.SHARE, d.name, d.host, d.port, user?.ifEmpty { null }, domain,
            share = d.share),
            password?.takeIf { !user.isNullOrEmpty() })
        return shares
    }

    /** Partages de l'appareil, avec l'identifiant enregistré. */
    fun shares(d: LanDevice): List<LanEntry> = try {
        listShares(contextFor(d.host, d.port), d)
    } catch (e: SmbAuthException) {
        throw NeedLogin()
    } catch (e: jcifs.smb.SmbException) {
        // Accès invité refusé sans réponse claire (certains serveurs coupent la connexion) : on demande l'identifiant.
        if (LanStore.find(LanDevice.Kind.SHARE, d.host, d.port)?.user == null && !unreachable(e)) throw NeedLogin()
        throw e
    }

    /** L'appareil ne répond pas du tout (éteint, mauvaise adresse), par opposition à un accès refusé. */
    private fun unreachable(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.UnknownHostException ||
            it is java.net.NoRouteToHostException
    }

    private fun listShares(ctx: CIFSContext, d: LanDevice): List<LanEntry> {
        // Partage donné à la main : on vérifie seulement qu'il s'ouvre.
        d.share?.let { s ->
            file(ctx, d.host, d.port, s, emptyList(), dir = true).listFiles()
            return listOf(LanEntry(name = s, folder = true, ref = s, device = d))
        }
        return SmbFile(root(d.host, d.port), ctx).listFiles()
            .filter { it.type == SmbFile.TYPE_SHARE }
            .map { it.name.trimEnd('/') }
            // C$, ADMIN$, IPC$ : partages cachés du système, sans intérêt ici.
            .filter { !it.endsWith("$") && !it.equals("print", true) }
            .map { LanEntry(name = it, folder = true, ref = it, device = d) }
            .sortedWith(Lan.ORDER)
    }

    /** Contenu d'un dossier ([ref] : « partage/dossier/sous-dossier »). Seuls les dossiers et les médias sont gardés. */
    fun list(d: LanDevice, ref: String): List<LanEntry> = try {
        val parts = ref.split('/').filter { it.isNotEmpty() }
        val dir = file(contextFor(d.host, d.port), d.host, d.port, parts.first(), parts.drop(1), dir = true)
        dir.listFiles().mapNotNull { f ->
            val name = f.name.trimEnd('/')
            if (name.startsWith(".") || runCatching { f.isHidden }.getOrDefault(false)) return@mapNotNull null
            val date = runCatching { f.lastModified() }.getOrNull()?.takeIf { it > 0 }
            if (f.isDirectory) LanEntry(name = name, folder = true, ref = "$ref/$name", device = d, date = date)
            else {
                val kind = Lan.kindOf(null, name).takeIf { it != LanEntry.Kind.OTHER } ?: return@mapNotNull null
                LanEntry(name = name, folder = false, ref = "$ref/$name", kind = kind, mime = Lan.mimeOf(name),
                    size = runCatching { f.length() }.getOrNull(), device = d, date = date)
            }
        }.sortedWith(Lan.ORDER)
    } catch (e: SmbAuthException) {
        throw NeedLogin()
    }

    // ------------------------------------------------------------------ lecture d'un fichier

    /** Adresse « content:// » d'un fichier partagé : le reste de l'appli le lit comme un fichier du téléphone. */
    fun contentUri(d: LanDevice, ref: String): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
        .appendPath(d.address).apply { ref.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }.build()

    val AUTHORITY = "${BuildConfig.APPLICATION_ID}.smb"

    /** Ouvre le fichier désigné par une adresse de [contentUri] (pour [SmbProvider]). */
    internal fun open(uri: Uri): SmbFile {
        val seg = uri.pathSegments
        require(seg.size >= 3) { "adresse incomplète" }
        val (host, port) = Lan.parseAddress(seg[0]) ?: error("adresse")
        return file(contextFor(host, port), host, port, seg[1], seg.drop(2), dir = false)
    }

    // ------------------------------------------------------------------ recherche sur le réseau

    /**
     * Cherche les appareils qui partagent des dossiers (port 445 ouvert) sur le réseau du téléphone, et leur nom
     * (NetBIOS pour Windows et les NAS, sinon le nom donné par la box).
     */
    fun discover(onFound: (LanDevice) -> Unit) {
        val me = Net.localIp() ?: return
        val prefix = me.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            val ip = "$prefix.$i"
            if (ip == me) continue
            pool.execute {
                if (!Lan.portOpen(ip, 445, 400)) return@execute
                val name = netbiosName(ip) ?: reverseName(ip) ?: ip
                onFound(LanDevice(LanDevice.Kind.SHARE, name, ip))
            }
        }
        pool.shutdown()
        pool.awaitTermination(15, TimeUnit.SECONDS)
        pool.shutdownNow()
    }

    private fun reverseName(ip: String): String? = runCatching {
        InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }?.substringBefore('.')
    }.getOrNull()

    /** Demande son nom à l'appareil (« état du nœud » NetBIOS, port 137). */
    internal fun netbiosName(ip: String): String? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = 700
            val q = ByteArray(50)
            q[0] = 0x13; q[1] = 0x37 // identifiant de la requête
            q[5] = 1 // une question
            q[12] = 0x20
            // Nom « * » complété de zéros, codé en demi-octets (« CK » puis « AA »…).
            val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
            for (i in 0 until 16) {
                q[13 + 2 * i] = ('A'.code + (raw[i].toInt() shr 4 and 0xF)).toByte()
                q[14 + 2 * i] = ('A'.code + (raw[i].toInt() and 0xF)).toByte()
            }
            q[45] = 0; q[46] = 0; q[47] = 0x21; q[48] = 0; q[49] = 1
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            parseNodeStatus(buf.copyOf(p.length))
        }
    }.onFailure { Log.d(TAG, "pas de nom NetBIOS pour $ip") }.getOrNull()

    internal fun parseNodeStatus(b: ByteArray): String? {
        if (b.size < 57) return null
        val count = b[56].toInt() and 0xFF
        for (i in 0 until count) {
            val o = 57 + i * 18
            if (o + 18 > b.size) break
            val suffix = b[o + 15].toInt() and 0xFF
            val group = (b[o + 16].toInt() and 0x80) != 0
            if (!group && (suffix == 0x00 || suffix == 0x20)) {
                return String(b, o, 15, Charsets.ISO_8859_1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
