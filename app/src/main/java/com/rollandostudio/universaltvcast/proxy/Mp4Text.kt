package com.rollandostudio.universaltvcast.proxy

import java.nio.ByteBuffer

/**
 * Sous-titres rangés dans du MP4 fragmenté (DASH, CMAF) : WebVTT (`wvtt`, répliques `vttc`/`payl`) ou TTML (`stpp`,
 * un document par échantillon). On lit l'horloge de chaque fragment (`tfdt`, `trun`) et on en tire un SRT.
 */
object Mp4Text {
    private class Box(val type: String, val start: Int, val end: Int, val body: Int)

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = ByteBuffer.wrap(b, p, 4).int.toLong() and 0xFFFFFFFFL
            var h = 8
            if (size == 1L && p + 16 <= to) { size = ByteBuffer.wrap(b, p + 8, 8).long; h = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < h || p + size > to) break
            out += Box(String(b, p + 4, 4, Charsets.ISO_8859_1), p, (p + size).toInt(), p + h)
            p += size.toInt()
        }
        return out
    }
    private fun u32(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).int.toLong() and 0xFFFFFFFFL
    private fun find(b: ByteArray, parent: Box, vararg path: String): Box? {
        var cur: Box? = parent
        for (t in path) cur = cur?.let { c -> boxes(b, c.body, c.end).firstOrNull { it.type == t } }
        return cur
    }

    private fun time(ms: Long) = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    /** [data] : en-tête puis fragments, à la suite (un fichier entier, ou des morceaux recollés). Null si rien de lisible. */
    fun toSrt(data: ByteArray, defaultTimescale: Long = 1000): ByteArray? {
        val top = boxes(data, 0, data.size)
        val moov = top.firstOrNull { it.type == "moov" }
        val mdhd = moov?.let { m -> boxes(data, m.body, m.end).firstOrNull { it.type == "trak" }?.let { find(data, it, "mdia", "mdhd") } }
        val timescale = mdhd?.let { u32(data, it.body + 4 + if (data[it.body].toInt() == 1) 16 else 8) } ?: defaultTimescale
        val trex = moov?.let { find(data, it, "mvex", "trex") }
        val trexDur = trex?.let { u32(data, it.body + 12) } ?: 0L
        val trexSize = trex?.let { u32(data, it.body + 16) } ?: 0L
        val isTtml = String(data, 0, minOf(data.size, 4096), Charsets.ISO_8859_1).contains("stpp")

        val srt = StringBuilder()
        // Répliques WebVTT (début, fin, texte) : une même réplique coupée par les fragments est recollée.
        val cues = mutableListOf<Triple<Long, Long, String>>()
        for ((i, moof) in top.withIndex()) {
            if (moof.type != "moof") continue
            val mdat = top.drop(i + 1).firstOrNull { it.type == "mdat" } ?: continue
            val traf = boxes(data, moof.body, moof.end).firstOrNull { it.type == "traf" } ?: continue
            val kids = boxes(data, traf.body, traf.end)
            val tfhd = kids.firstOrNull { it.type == "tfhd" } ?: continue
            val tf = u32(data, tfhd.body).toInt() and 0xFFFFFF
            var q = tfhd.body + 8
            val baseOffset = if (tf and 1 != 0) ByteBuffer.wrap(data, q, 8).long.also { q += 8 } else moof.start.toLong()
            if (tf and 2 != 0) q += 4
            val defDur = if (tf and 8 != 0) u32(data, q).also { q += 4 } else trexDur
            val defSize = if (tf and 0x10 != 0) u32(data, q).also { q += 4 } else trexSize
            var t = kids.firstOrNull { it.type == "tfdt" }?.let { if (data[it.body].toInt() == 1) ByteBuffer.wrap(data, it.body + 4, 8).long else u32(data, it.body + 4) } ?: 0L
            for (trun in kids.filter { it.type == "trun" }) {
                val f = u32(data, trun.body).toInt() and 0xFFFFFF
                val count = u32(data, trun.body + 4).toInt()
                var r = trun.body + 8
                var pos = if (f and 1 != 0) baseOffset + ByteBuffer.wrap(data, r, 4).int.also { r += 4 } else mdat.body.toLong()
                if (f and 4 != 0) r += 4
                repeat(count) {
                    val dur = if (f and 0x100 != 0) u32(data, r).also { r += 4 } else defDur
                    val size = if (f and 0x200 != 0) u32(data, r).also { r += 4 } else defSize
                    if (f and 0x400 != 0) r += 4
                    if (f and 0x800 != 0) r += 4
                    val a = pos.toInt(); val z = (pos + size).toInt().coerceAtMost(data.size)
                    if (a in 0 until z) {
                        if (isTtml) {
                            // Document TTML complet : converti tel quel (ses heures sont celles de la vidéo).
                            val srtPart = String(Subtitles.toSrt(data.copyOfRange(a, z)), Charsets.UTF_8).removePrefix("﻿").trim()
                            if (srtPart.contains("-->")) srt.append(srtPart).append("\n\n")
                        } else {
                            val text = boxes(data, a, z).filter { it.type == "vttc" }.mapNotNull { c ->
                                boxes(data, c.body, c.end).firstOrNull { it.type == "payl" }?.let { String(data, it.body, it.end - it.body, Charsets.UTF_8).trim() }
                            }.joinToString("\n").trim()
                            if (text.isNotEmpty()) {
                                val a0 = t * 1000 / timescale; val b0 = (t + dur) * 1000 / timescale
                                val last = cues.lastOrNull()
                                if (last != null && last.third == text && a0 - last.second <= 40) cues[cues.lastIndex] = Triple(last.first, b0, text)
                                else cues += Triple(a0, b0, text)
                            }
                        }
                    }
                    t += dur; pos += size
                }
            }
        }
        cues.forEachIndexed { i, (a0, b0, text) ->
            srt.append(i + 1).append('\n').append(time(a0)).append(" --> ").append(time(b0)).append('\n').append(text).append("\n\n")
        }
        return if (srt.isEmpty()) null else ("﻿" + srt.toString().trim() + "\n").toByteArray(Charsets.UTF_8)
    }
}
