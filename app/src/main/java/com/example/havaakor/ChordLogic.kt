package com.example.havaakor

import kotlin.math.pow

val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Bir sette 7 jest var; el yatıkken ikinci set (8.–14. akorlar) devreye girer. */
const val SLOTS = 7
const val MAX_CHORDS = 14

val DEFAULT_CHORDS = listOf("C", "G", "Am", "F", "Dm", "Em", "G7")

/**
 * Jest şekilleri: [başparmak, işaret, orta, yüzük, serçe].
 * 1–5 parmak, 6 = rock işareti, 7 = shaka. Rehberde çizilir.
 */
val GESTURE_SHAPES: List<BooleanArray> = listOf(
    booleanArrayOf(false, true, false, false, false),
    booleanArrayOf(false, true, true, false, false),
    booleanArrayOf(false, true, true, true, false),
    booleanArrayOf(false, true, true, true, true),
    booleanArrayOf(true, true, true, true, true),
    booleanArrayOf(false, true, false, false, true),
    booleanArrayOf(true, false, false, false, true),
)

private val SUFFIX_RE = Regex("^(?:maj|min|dim|aug|sus|add|m|M|°|ø|\\+|-|#|b|[0-9])*$")

/** s[i] konumundaki nota: (perde sınıfı 0..11, harcanan karakter sayısı) */
private fun noteAt(s: String, i: Int): Pair<Int, Int>? {
    if (i >= s.length) return null
    val base = when (s[i]) {
        'C' -> 0
        'D' -> 2
        'E' -> 4
        'F' -> 5
        'G' -> 7
        'A' -> 9
        'B' -> 11
        else -> return null
    }
    var pc = base
    var len = 1
    if (i + 1 < s.length) {
        val c = s[i + 1]
        if (c == '#' || c == '♯') {
            pc += 1
            len = 2
        } else if (c == 'b' || c == '♭') {
            pc -= 1
            len = 2
        }
    }
    return Pair(((pc % 12) + 12) % 12, len)
}

/** "Am", "F#m7", "Bsus4", "C/G" gibi bir akoru çözer. Akor değilse null. */
fun parseChord(raw: String): ChordSpec? {
    val s = raw.trim()
    if (s.isEmpty() || s.length > 14) return null
    val root = noteAt(s, 0) ?: return null
    val start = root.second
    val slash = s.indexOf('/', start)
    val suffixEnd = if (slash >= 0) slash else s.length
    val suffix = s.substring(start, suffixEnd)
    if (!SUFFIX_RE.matches(suffix)) return null
    var bass = -1
    if (slash >= 0) {
        val b = noteAt(s, slash + 1) ?: return null
        if (slash + 1 + b.second != s.length) return null
        bass = b.first
    }
    return ChordSpec(s, root.first, bass, suffix)
}

/** Boşluk, virgül ya da noktalı virgülle ayrılmış akor listesi; geçersizler atılır. */
fun parseChordList(text: String): List<String> =
    text.split(Regex("[\\s,;]+"))
        .filter { it.isNotEmpty() && parseChord(it) != null }
        .distinct()
        .take(MAX_CHORDS)

private fun midiToHz(m: Int): Float = (440.0 * 2.0.pow((m - 69) / 12.0)).toFloat()

class ChordSpec(
    val original: String,
    val rootPc: Int,
    val bassPc: Int,
    val suffix: String,
) {
    /** Ton kaydırma uygulanmış adı. */
    fun display(semis: Int): String {
        if (semis == 0) return original
        val r = NOTE_NAMES[((rootPc + semis) % 12 + 12) % 12]
        val b = if (bassPc >= 0) "/" + NOTE_NAMES[((bassPc + semis) % 12 + 12) % 12] else ""
        return r + suffix + b
    }

    /** Alttan üste 6 nota (MIDI): bas, bas+beşli, kök, üçlü, beşli, 7'li/6'lı/oktav. */
    fun midiNotes(semis: Int): List<Int> {
        val suf = suffix
        val dim = suf.contains("dim") || suf.contains("°")
        val half = suf.contains("ø")
        val aug = suf.contains("aug") || suf.startsWith("+")
        val sus2 = suf.contains("sus2")
        val sus4 = suf.contains("sus4") || (suf.contains("sus") && !sus2)
        val power = suf == "5"
        val minor = !dim && (
            suf.startsWith("min") || suf.startsWith("-") ||
                (suf.startsWith("m") && !suf.startsWith("maj"))
            )
        val hasMaj = suf.contains("maj") || suf.contains("M")
        val add9 = suf.contains("add9")
        val rest = suf.replace("add9", "")
        val has7 = rest.contains("7")
        val ext = rest.contains("9") || rest.contains("11") || rest.contains("13")
        val sixth = rest.contains("6") && !has7 && !ext

        val third = when {
            power -> 12
            sus2 -> 2
            sus4 -> 5
            minor || dim || half -> 3
            else -> 4
        }
        val fifth = when {
            dim || half || suf.contains("b5") -> 6
            aug || suf.contains("#5") -> 8
            else -> 7
        }
        val seventh = when {
            dim && has7 -> 9
            hasMaj && (has7 || ext) -> 11
            half -> 10
            has7 || ext -> 10
            else -> -1
        }

        val r = 55 + ((rootPc + semis - 7) % 12 + 12) % 12
        val d = if (bassPc >= 0) ((bassPc - rootPc) % 12 + 12) % 12 else 0
        val bassMidi = r - 12 + d
        val extra = when {
            seventh >= 0 -> r + seventh
            sixth -> r + 9
            add9 -> r + 14
            else -> r + 12
        }
        return listOf(bassMidi, r - 12 + fifth, r, r + third, r + fifth, extra)
    }

    fun frequencies(semis: Int): List<Float> = midiNotes(semis).map { midiToHz(it) }
}
