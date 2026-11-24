package com.example.havaakor

/** Adım türleri */
const val KIND_NONE = 0   // hiçbir şey çalma (ses sönmeye devam eder)
const val KIND_DOWN = 1   // gitar aşağı vuruş: alçaktan tize sırayla
const val KIND_UP = 2     // gitar yukarı vuruş: tizden alçağa
const val KIND_BLOCK = 3  // notalar neredeyse aynı anda (piyano akoru, tek nota/arpej)
const val KIND_DAMP = 4   // kapatma: sesi kes

/**
 * Bir ölçünün tek adımı (bir sekizlik nota).
 * slots: akordaki nota sırası (0 = bas ... 5 = en tiz), vels: her notanın şiddeti.
 */
class Step(val kind: Int, val slots: IntArray, val vels: FloatArray)

/** length = adım sayısı: 8 → 4/4, 6 → 6/8 (ya da 3/4). */
class Pattern(val name: String, val code: String, val steps: List<Step>) {
    val length: Int get() = steps.size
}

object Patterns {
    private val ALL = intArrayOf(0, 1, 2, 3, 4, 5)
    private val NONE_STEP = Step(KIND_NONE, IntArray(0), FloatArray(0))
    private val DAMP_STEP = Step(KIND_DAMP, IntArray(0), FloatArray(0))

    /** Ritim kapalıyken akor değişince çalınanlar. */
    val guitarStrumOnce = Step(KIND_DOWN, ALL, FloatArray(6) { 1f })
    val pianoBlock = Step(
        KIND_BLOCK,
        ALL,
        floatArrayOf(0.95f, 0.7f, 0.85f, 0.85f, 0.85f, 0.85f),
    )

    // ---------------- ayrıştırıcılar ----------------

    /** Güçlü vuruşlar daha gür: 1. vuruş en güçlü, 6/8'de 4. adım ikinci güçlü. */
    private fun accent(i: Int, len: Int): Float = when {
        i == 0 -> 1f
        len == 6 && i == 3 -> 0.92f
        i % 2 == 0 -> 0.88f
        else -> 0.78f
    }

    private fun strumStep(kind: Int, vel: Float): Step = Step(kind, ALL, FloatArray(6) { vel })

    private fun single(slot: Int, vel: Float): Step =
        Step(KIND_BLOCK, intArrayOf(slot), floatArrayOf(vel))

    /** Gitar ritmi: A aşağı, Y yukarı, - kapatma, . boş (D/U de kabul). 2–16 adım. */
    fun parseStrum(text: String): List<Step>? {
        val chars = text.filter { it in "AaDdYyUu-." }
        if (chars.length < 2 || chars.length > 16) return null
        val out = ArrayList<Step>()
        for ((i, ch) in chars.withIndex()) {
            val v = accent(i, chars.length)
            out.add(
                when (ch) {
                    'A', 'a', 'D', 'd' -> strumStep(KIND_DOWN, v)
                    'Y', 'y', 'U', 'u' -> strumStep(KIND_UP, v * 0.7f)
                    '-' -> DAMP_STEP
                    else -> NONE_STEP
                }
            )
        }
        return out
    }

    /** Gitar arpeji: p başparmak (bas), i işaret, m orta, a yüzük, . boş. - ve boşluk ayraç. */
    fun parseArpej(text: String): List<Step>? {
        val chars = text.lowercase().filter { it in "pima." }
        if (chars.length < 2 || chars.length > 16) return null
        val out = ArrayList<Step>()
        for (ch in chars) {
            out.add(
                when (ch) {
                    'p' -> single(0, 1f)
                    'i' -> single(3, 0.78f)
                    'm' -> single(4, 0.78f)
                    'a' -> single(5, 0.72f)
                    else -> NONE_STEP
                }
            )
        }
        return out
    }

    /** Piyano arpeji: rakamlar akordaki nota sırası (0 bas ... 5 tiz), . boş. */
    private fun parseSlots(text: String): List<Step> =
        text.filter { it in "012345." }.map { ch ->
            if (ch == '.') NONE_STEP else single(ch - '0', if (ch == '0') 0.95f else 0.7f)
        }

    /** Piyano ritmi: bas satırı ve akor satırı. x güçlü, o hafif, . yok. */
    private fun layered(bas: String, chord: String): List<Step> =
        List(bas.length) { i ->
            val slots = ArrayList<Int>()
            val vels = ArrayList<Float>()
            when (bas[i]) {
                'x' -> {
                    slots.add(0)
                    vels.add(0.95f)
                    slots.add(1)
                    vels.add(0.7f)
                }
                'o' -> {
                    slots.add(0)
                    vels.add(0.6f)
                }
            }
            when (chord[i]) {
                'x' -> for (k in 2..5) {
                    slots.add(k)
                    vels.add(0.85f)
                }
                'o' -> for (k in 2..5) {
                    slots.add(k)
                    vels.add(0.55f)
                }
            }
            if (slots.isEmpty()) NONE_STEP else Step(KIND_BLOCK, slots.toIntArray(), vels.toFloatArray())
        }

    // ---------------- hazır kalıplar ----------------

    private fun rhythm(name: String, code: String) = Pattern(name, code, parseStrum(code)!!)
    private fun arp(name: String, code: String) = Pattern(name, code, parseArpej(code)!!)
    private fun pianoRh(name: String, bas: String, akor: String) =
        Pattern(name, "bas   $bas\nakor  $akor", layered(bas, akor))
    private fun pianoArp(name: String, code: String) = Pattern(name, code, parseSlots(code))

    val guitarRhythm: List<Pattern> = listOf(
        rhythm("Pop", "A.AY.YAY"),
        rhythm("Rock", "A.A.AYA."),
        rhythm("Sekizlik", "AYAYAYAY"),
        rhythm("Balad", "A...A.Y."),
        rhythm("Kesik", "A-AY-YAY"),
        rhythm("Serbest", "A......."),
    )

    val guitarArpej: List<Pattern> = listOf(
        arp("Klasik 8'li", "p-i-m-i-a-i-m-i"),
        arp("Yükselen 8'li", "p-i-m-a-m-i-m-i"),
        arp("Süzülen 6'lı", "p-i-m-a-m-i"),
        arp("Vals 6'lı", "p-i-m-i-m-i"),
    )

    val pianoRhythm: List<Pattern> = listOf(
        pianoRh("Uzun akor", "x.......", "x...o..."),
        pianoRh("Bas + akor", "x...o...", "..x...x."),
        pianoRh("Pop piyano", "x...o...", "x..x.xo."),
        pianoRh("Enerjik", "x...x...", "xoxoxoxo"),
        pianoRh("Vals (3/4)", "x.....", "..x.x."),
        pianoRh("Serbest", "x.......", "x......."),
    )

    val pianoArpej: List<Pattern> = listOf(
        pianoArp("Yükselen 8'li", "02345432"),
        pianoArp("Alberti", "04340434"),
        pianoArp("Süzülen 6'lı", "023532"),
    )

    /** instrument: 0 gitar, 1 piyano; style: 0 ritim, 1 arpej. */
    fun presets(instrument: Int, style: Int): List<Pattern> = when (instrument * 2 + style) {
        0 -> guitarRhythm
        1 -> guitarArpej
        2 -> pianoRhythm
        else -> pianoArpej
    }
}

/** Davul kalıpları: [kick, snare, hi-hat]. 8 adım (4/4) ve 6 adım (6/8) sürümleri var. */
object DrumKits {
    val names = listOf("Kapalı", "Hi-hat", "Pop", "Rock", "Balad")

    private val eight = listOf(
        listOf("........", "........", "........"),
        listOf("........", "........", "xxxxxxxx"),
        listOf("x...x...", "..x...x.", "x.x.x.x."),
        listOf("x...x.x.", "..x...x.", "xxxxxxxx"),
        listOf("x.......", "....x...", "x.x.x.x."),
    )

    private val six = listOf(
        listOf("......", "......", "......"),
        listOf("......", "......", "xxxxxx"),
        listOf("x.....", "...x..", "x.x.x."),
        listOf("x...x.", "...x..", "xxxxxx"),
        listOf("x.....", "...x..", "x..x.."),
    )

    /** Uzunluğu len olan bir ölçü için [kick, snare, hat] satırları. */
    fun rows(idx: Int, len: Int): List<String> {
        val i = idx.coerceIn(0, names.size - 1)
        if (len == 8) return eight[i]
        if (len == 6) return six[i]
        val none = ".".repeat(len)
        return if (i == 0) listOf(none, none, none) else listOf(none, none, "x".repeat(len))
    }
}
