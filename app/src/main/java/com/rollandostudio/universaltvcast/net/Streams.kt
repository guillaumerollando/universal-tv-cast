package com.rollandostudio.universaltvcast.net

/**
 * Adresses de flux qui ne sont pas du HTTP, trouvées dans des listes de chaînes (IPTV) :
 * - UDP / RTP multicast (box des opérateurs) : le téléphone les reçoit et les relaie en HTTP, à la télé comme à
 *   son propre lecteur ;
 * - RTSP / RTMP : le lecteur du téléphone les lit, mais pas les télés DLNA (il faudrait réencapsuler le flux).
 */
object Streams {
    private fun scheme(url: String) = url.substringBefore("://", "").lowercase()

    fun multicast(url: String) = scheme(url) in setOf("udp", "rtp")
    fun phoneOnly(url: String) = scheme(url) in setOf("rtsp", "rtsps", "rtmp", "rtmps", "rtmpt", "rtmpe")
    /** Adresse de flux acceptée dans une liste de chaînes ou envoyée par une autre appli. */
    fun supported(url: String) = scheme(url) in setOf("http", "https") || multicast(url) || phoneOnly(url)

    /** « udp://@239.1.2.3:1234 », « rtp://239.1.2.3:5000?… » → (groupe, port). */
    fun group(url: String): Pair<String, Int>? {
        val hostPort = url.substringAfter("://").substringBefore('?').substringBefore('/').removePrefix("@").substringAfter('@')
        val port = hostPort.substringAfterLast(':', "").toIntOrNull() ?: return null
        return hostPort.substringBeforeLast(':').trim('[', ']') to port
    }
}
