package com.example.havaakor

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

/**
 * fingers: 0 = sustur (yumruk / sol el yok), 1..5 = gam derecesi
 * minor: elin yana yatışıyla belirlenir
 * volume / brightness: sağ elden gelir (0..1)
 */
data class ChordState(
    val fingers: Int,
    val minor: Boolean,
    val volume: Float,
    val brightness: Float,
)

/** Landmark'lardan jest okur. */
object HandReader {

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    /** Açık parmak sayısı (0..5). Elin yönünden bağımsız çalışır. */
    fun fingers(h: Hand): Int {
        val p = h.pts
        var n = 0
        for ((tip, pip) in listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)) {
            if (dist(p[tip], p[0]) > dist(p[pip], p[0]) * 1.15f) n++
        }
        // Başparmak: ucu, kendi tabanından (2) belirgin şekilde uzaktaysa açık
        if (dist(p[4], p[17]) > dist(p[2], p[17]) * 1.2f) n++
        return n
    }

    /** El ekranda sola 20 dereceden fazla yatıksa minör. (Ekran aynalı görüntü.) */
    fun isMinor(h: Hand): Boolean {
        val dx = -(h.pts[9].x - h.pts[0].x) // aynalı x
        val dy = h.pts[0].y - h.pts[9].y
        val deg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble()))
        return deg < -20.0
    }

    /**
     * Ekranın solundaki el akor eli, sağdaki efekt eli.
     * [swapHands] solaklar için rolleri değiştirir.
     */
    fun read(hands: List<Hand>, swapHands: Boolean): ChordState {
        if (hands.isEmpty()) return ChordState(0, false, 0.6f, 0.5f)

        // Aynalı x'e göre sırala (küçük = ekranda sol)
        val sorted = hands.sortedBy { 1f - it.pts[0].x }
        var chordHand: Hand?
        var fxHand: Hand?
        if (sorted.size >= 2) {
            chordHand = sorted[0]
            fxHand = sorted[1]
        } else {
            val h = sorted[0]
            if (1f - h.pts[0].x < 0.5f) {
                chordHand = h; fxHand = null
            } else {
                chordHand = null; fxHand = h
            }
        }
        if (swapHands) {
            val t = chordHand; chordHand = fxHand; fxHand = t
        }

        val fingers = chordHand?.let { fingers(it) } ?: 0
        val minor = chordHand?.let { isMinor(it) } ?: false
        val volume = fxHand?.let { 0.15f + 0.85f * (1f - it.pts[0].y).coerceIn(0f, 1f) } ?: 0.6f
        val brightness = fxHand?.let { (1f - it.pts[0].x).coerceIn(0f, 1f) } ?: 0.5f
        return ChordState(fingers, minor, volume, brightness)
    }
}

object ChordLogic {
    val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** 1..5 parmak -> majör gamın I, II, III, IV, V derecesi (yarım ton olarak). */
    private val scale = intArrayOf(0, 2, 4, 5, 7)

    fun name(st: ChordState, key: Int): String {
        if (st.fingers !in 1..5) return "—"
        val root = (key + scale[st.fingers - 1]) % 12
        return noteNames[root] + if (st.minor) "m" else ""
    }

    /** 6 telli gitara benzeyen dizilim (alttan üste). Boş liste = sessizlik. */
    fun frequencies(st: ChordState, key: Int): List<Float> {
        if (st.fingers !in 1..5) return emptyList()
        val root = 60 + key + scale[st.fingers - 1]
        val third = root + if (st.minor) 3 else 4
        return listOf(root - 12, root - 5, root, third, root + 7, root + 12).map { midiToHz(it) }
    }

    private fun midiToHz(m: Int): Float = (440.0 * 2.0.pow((m - 69) / 12.0)).toFloat()
}
