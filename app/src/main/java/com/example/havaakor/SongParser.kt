package com.example.havaakor

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** Satırdaki bir akor: pos = sözdeki sütun, event = akor akışındaki sıra. */
class ChordMark(val pos: Int, val chord: String) {
    var event: Int = 0
}

class SongLine(val text: String, val marks: List<ChordMark>)

class Song(
    val title: String,
    val lines: List<SongLine>,
    /** Benzersiz akorlar, ilk geçiş sırasıyla. */
    val chords: List<String>,
    /** Art arda tekrarlar birleştirilmiş akor akışı (takip için). */
    val events: List<String>,
    /** Her akor olayının ilk geçtiği satır. */
    val eventLine: IntArray,
    /** Sayfada bulunan tempo; yoksa 0. */
    val bpm: Int,
)

object SongParser {
    private const val MAX_BYTES = 2_000_000
    private val BRACKET_RE = Regex("\\[([^\\]\\s]{1,14})\\]")
    private val TOKEN_RE = Regex("\\S+")
    private val IGNORE_RE = Regex("^(?:[xX]\\d+|\\d+[xX]|[-–—/.~]+)$")

    private class Cls(val marks: List<ChordMark>, val text: String, val chordOnly: Boolean)

    // ---------------- İndirme ----------------

    fun fetch(urlText: String): String {
        var u = urlText.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
        )
        conn.setRequestProperty("Accept-Language", "tr,en;q=0.8")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("sunucu $code döndürdü")
            val ctype = conn.contentType ?: ""
            val bytes = conn.inputStream.use { readLimited(it) }
            return String(bytes, charsetFrom(ctype, bytes))
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) break
        }
        return out.toByteArray()
    }

    private fun charsetFrom(contentType: String, bytes: ByteArray): Charset {
        val m1 = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val m2 = Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)
        val name = m1?.groupValues?.get(1) ?: m2?.groupValues?.get(1) ?: "UTF-8"
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    // ---------------- HTML -> düz metin ----------------

    private fun codePointToString(cp: Int): String? =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        var r = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { cp -> codePointToString(cp) } ?: m.value
        }
        r = Regex("&#[xX]([0-9a-fA-F]+);").replace(r) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp -> codePointToString(cp) } ?: m.value
        }
        return r.replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    fun htmlToText(html: String): String {
        var s = html
        s = Regex("(?s)<!--.*?-->").replace(s, " ")
        s = Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>").replace(s, " ")
        // class'ında chord/akor geçen etiketleri [Akor] biçimine çevir
        s = Regex(
            "(?is)<(span|b|strong|a|i|em|u|font)[^>]*class=[\"'][^\"']*(?:chord|akor)[^\"']*[\"'][^>]*>(.*?)</\\1>",
        ).replace(s) { m -> "[" + m.groupValues[2] + "]" }
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</?(p|div|li|tr|h[1-6]|pre|table|ul|ol|blockquote)[^>]*>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        return decodeEntities(s)
    }

    // ---------------- Metin -> Şarkı ----------------

    fun parseHtml(html: String): Song? {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?.groupValues?.get(1)
            ?.let { decodeEntities(it).trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: "Şarkı"
        return parseText(htmlToText(html), title)
    }

    private fun classify(line: String): Cls {
        if (line.isBlank()) return Cls(emptyList(), "", false)
        if (line.length > 220) return Cls(emptyList(), line, false)

        // 1) Satır içi köşeli parantezli akorlar: [Am]söz
        if (line.contains('[')) {
            val marks = ArrayList<ChordMark>()
            val sb = StringBuilder()
            var last = 0
            for (m in BRACKET_RE.findAll(line)) {
                if (parseChord(m.groupValues[1]) == null) continue
                sb.append(line, last, m.range.first)
                marks.add(ChordMark(sb.length, m.groupValues[1].trim()))
                last = m.range.last + 1
            }
            if (marks.isNotEmpty()) {
                sb.append(line, last, line.length)
                val txt = sb.toString()
                return Cls(marks, txt, txt.isBlank())
            }
        }

        // 2) Yalnızca akorlardan oluşan satır (sözlerin üstündeki akor satırı)
        val marks = ArrayList<ChordMark>()
        var ok = true
        for (m in TOKEN_RE.findAll(line)) {
            val raw = m.value
            val tok = raw.trim('(', ')', '[', ']', '|', ',', ';', ':')
            if (tok.isEmpty() || IGNORE_RE.matches(tok)) continue
            if (parseChord(tok) == null) {
                ok = false
                break
            }
            marks.add(ChordMark(m.range.first + raw.indexOf(tok), tok))
        }
        if (ok && marks.isNotEmpty() && marks.size <= 16) return Cls(marks, "", true)
        return Cls(emptyList(), line, false)
    }

    fun parseText(text: String, fallbackTitle: String): Song? {
        val rawLines = text.replace("\r", "")
            .replace(' ', ' ')
            .replace("\t", "    ")
            .split("\n")
            .map { it.trimEnd() }
        val cls = rawLines.map { classify(it) }
        val chordIdx = cls.indices.filter { cls[it].marks.isNotEmpty() }
        if (chordIdx.isEmpty()) return null

        // Akor satırlarını kümele; en kalabalık kümeyi şarkı say (menü/yorum gürültüsünü ayıklar)
        var bestStart = chordIdx[0]
        var bestEnd = chordIdx[0]
        var bestCount = 0
        var cs = 0
        for (k in chordIdx.indices) {
            val isEnd = k == chordIdx.size - 1 || chordIdx[k + 1] - chordIdx[k] > 20
            if (isEnd) {
                val count = k - cs + 1
                if (count > bestCount) {
                    bestCount = count
                    bestStart = chordIdx[cs]
                    bestEnd = chordIdx[k]
                }
                cs = k + 1
            }
        }

        val lines = ArrayList<SongLine>()
        val end = minOf(bestEnd + 1, rawLines.size - 1)
        var i = bestStart
        while (i <= end) {
            val c = cls[i]
            if (c.marks.isNotEmpty()) {
                if (c.chordOnly && i + 1 <= end && cls[i + 1].marks.isEmpty() && cls[i + 1].text.isNotBlank()) {
                    lines.add(SongLine(rawLines[i + 1], c.marks))
                    i += 2
                } else {
                    lines.add(SongLine(c.text, c.marks))
                    i += 1
                }
            } else {
                if (c.text.isBlank()) {
                    val prev = lines.lastOrNull()
                    if (prev != null && !(prev.marks.isEmpty() && prev.text.isBlank())) {
                        lines.add(SongLine("", emptyList()))
                    }
                } else {
                    lines.add(SongLine(c.text, emptyList()))
                }
                i += 1
            }
        }

        val chords = ArrayList<String>()
        val events = ArrayList<String>()
        val eventLine = ArrayList<Int>()
        var prevChord = ""
        for ((li, line) in lines.withIndex()) {
            for (m in line.marks) {
                if (m.chord !in chords) chords.add(m.chord)
                if (m.chord != prevChord) {
                    events.add(m.chord)
                    eventLine.add(li)
                    prevChord = m.chord
                }
                m.event = events.size - 1
            }
        }
        if (chords.isEmpty()) return null

        val bpm = Regex("(?i)(?:bpm|tempo)\\s*[:=]?\\s*(\\d{2,3})")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 40..220 } ?: 0

        val title = fallbackTitle.trim().take(80)
        return Song(title, lines, chords, events, eventLine.toIntArray(), bpm)
    }
}
