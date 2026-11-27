package com.rollandostudio.universaltvcast.proxy

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Convertit des sous-titres WebVTT, ASS/SSA ou SRT en SRT UTF-8 (le format que les télés lisent le mieux). */
object Subtitles {
    fun toSrt(bytes: ByteArray): ByteArray {
        var text = decode(bytes).removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val head = text.trimStart()
        text = when {
            head.startsWith("WEBVTT") -> fromVtt(text)
            head.startsWith("[Script Info]") || text.contains("\n[Events]") -> fromAss(text)
            else -> text
        }
        return ("\uFEFF" + text.trim() + "\n").toByteArray(Charsets.UTF_8)
    }

    private val SRT_TIME = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

    /**
     * Décale tous les sous-titres d'un SRT (déjà converti par [toSrt]) de [ms] millisecondes :
     * positif = ils apparaissent plus tard. Ceux qui tomberaient avant le début sont retirés.
     */
    fun shift(srt: ByteArray, ms: Long): ByteArray {
        if (ms == 0L) return srt
        val text = String(srt, Charsets.UTF_8).removePrefix("\uFEFF")
        val out = StringBuilder()
        var n = 1
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.trim('\n').lines()
            val t = lines.indexOfFirst { SRT_TIME.containsMatchIn(it) }
            if (t < 0) continue
            val g = SRT_TIME.find(lines[t])!!.groupValues
            fun at(o: Int) = ((g[o].toLong() * 60 + g[o + 1].toLong()) * 60 + g[o + 2].toLong()) * 1000 + g[o + 3].toLong()
            val a = at(1) + ms
            val b = at(5) + ms
            if (b <= 0) continue
            out.append(n++).append('\n').append(srtTime(a.coerceAtLeast(0))).append(" --> ").append(srtTime(b)).append('\n')
                .append(lines.drop(t + 1).joinToString("\n")).append("\n\n")
        }
        return ("\uFEFF" + out.toString().trim() + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun srtTime(ms: Long): String =
        String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    /** SRT (déjà converti par [toSrt]) → WebVTT, le format des sous-titres de Google Cast. */
    fun srtToVtt(srt: ByteArray): ByteArray {
        val text = String(srt, Charsets.UTF_8).removePrefix("\uFEFF")
        val body = text.lines().joinToString("\n") { line ->
            if (line.contains("-->")) line.replace(',', '.') else line
        }
        return ("WEBVTT\n\n" + body.trim() + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun decode(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: CharacterCodingException) {
        String(b, Charset.forName("windows-1252"))
    }

    private val TAG = Regex("<(?!/?[ibu]>)[^>]*>")

    private fun fromVtt(text: String): String {
        val out = StringBuilder()
        var n = 1
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val t = lines.indexOfFirst { it.contains("-->") }
            if (t < 0) continue
            val (a, b) = lines[t].split("-->").map { it.trim().substringBefore(' ') }
            val body = lines.drop(t + 1).joinToString("\n") { TAG.replace(it, "") }.trim()
            if (body.isEmpty()) continue
            out.append(n++).append('\n').append(vttTime(a)).append(" --> ").append(vttTime(b)).append('\n')
                .append(body).append("\n\n")
        }
        return out.toString()
    }

    private fun vttTime(t: String): String {
        val p = t.split(':').toMutableList()
        if (p.size == 2) p.add(0, "00")
        val sec = p[2].replace('.', ',')
        return "${p[0].padStart(2, '0')}:${p[1].padStart(2, '0')}:${if (sec.contains(',')) sec.padStart(6, '0') else "$sec,000"}"
    }

    private fun fromAss(text: String): String {
        val lines = text.lines()
        var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        var inEvents = false
        val cues = mutableListOf<Triple<String, String, String>>()
        for (l in lines) {
            val line = l.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (line.startsWith("Format:")) {
                fields = line.substringAfter(':').split(',').map { it.trim() }
            } else if (line.startsWith("Dialogue:")) {
                val parts = line.substringAfter(':').split(',', limit = fields.size).map { it.trim() }
                if (parts.size < fields.size) continue
                val start = parts[fields.indexOf("Start")]
                val end = parts[fields.indexOf("End")]
                val body = parts[fields.indexOf("Text")]
                    .replace(Regex("\\{[^}]*\\}"), "")
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
                if (body.isNotEmpty()) cues += Triple(assTime(start), assTime(end), body)
            }
        }
        return cues.sortedBy { it.first }.withIndex().joinToString("") { (i, c) ->
            "${i + 1}\n${c.first} --> ${c.second}\n${c.third}\n\n"
        }
    }

    private fun assTime(t: String): String {
        val p = t.split(':')
        if (p.size != 3) return "00:00:00,000"
        val sec = p[2].split('.')
        val cs = (sec.getOrNull(1) ?: "0").padEnd(3, '0').take(3)
        return "${p[0].padStart(2, '0')}:${p[1].padStart(2, '0')}:${sec[0].padStart(2, '0')},$cs"
    }
}
