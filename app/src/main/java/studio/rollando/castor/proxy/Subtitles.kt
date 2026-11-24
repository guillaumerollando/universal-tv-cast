package studio.rollando.castor.proxy

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Convertit des sous-titres WebVTT, ASS/SSA ou SRT en SRT UTF-8 (le format que les télés lisent le mieux). */
object Subtitles {
    fun toSrt(bytes: ByteArray): ByteArray {
        var text = decode(bytes).removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val head = text.trimStart()
        text = when {
            head.startsWith("WEBVTT") -> fromVtt(text)
            head.startsWith("[Script Info]") || text.contains("\n[Events]") -> fromAss(text)
            else -> text
        }
        return ("﻿" + text.trim() + "\n").toByteArray(Charsets.UTF_8)
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
