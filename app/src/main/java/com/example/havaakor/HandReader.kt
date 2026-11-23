package com.example.havaakor

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * gesture: 0 = sessizlik (yumruk / akor eli yok), 1..7 = jest (bkz. GESTURE_SHAPES)
 * tilted: akor eli ekranda sola yatıksa true (ikinci akor seti)
 * volume / brightness: efekt elinden (0..1)
 */
data class HandState(
    val gesture: Int,
    val tilted: Boolean,
    val volume: Float,
    val brightness: Float,
)

object HandReader {
    private var tilted = false
    private var lastGesture = 0

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    /** [başparmak, işaret, orta, yüzük, serçe] açık mı? Elin yönünden bağımsız. */
    fun fingerStates(h: Hand): BooleanArray {
        val out = BooleanArray(5)
        val p = h.pts
        if (p.size < 21) return out
        val tips = intArrayOf(8, 12, 16, 20)
        val pips = intArrayOf(6, 10, 14, 18)
        for (k in 0 until 4) {
            out[k + 1] = dist(p[tips[k]], p[0]) > dist(p[pips[k]], p[0]) * 1.15f
        }
        out[0] = dist(p[4], p[17]) > dist(p[2], p[17]) * 1.2f
        return out
    }

    /** 0..7 jest numarası; tanınmayan şekil için -1 (önceki jest korunur). */
    fun classify(f: BooleanArray): Int {
        val t = f[0]
        val i = f[1]
        val m = f[2]
        val r = f[3]
        val p = f[4]
        return when {
            !i && !m && !r && !p -> 0 // yumruk (başparmak açık da olsa)
            !i && !m && !r && p -> if (t) 7 else -1
            i && !m && !r && p -> 6
            i && m && r && p -> if (t) 5 else 4
            i && m && r && !p -> 3
            i && m && !r && !p -> 2
            i && !m && !r && !p -> 1
            else -> -1
        }
    }

    private fun updateTilt(h: Hand): Boolean {
        val dx = -(h.pts[9].x - h.pts[0].x) // aynalı x
        val dy = h.pts[0].y - h.pts[9].y
        val deg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble()))
        tilted = if (tilted) deg < -10.0 else deg < -25.0
        return tilted
    }

    /** (akor eli, efekt eli). Ekranın solundaki el akor eli; solaklar için ters. */
    private fun pick(hands: List<Hand>, swapHands: Boolean): Pair<Hand?, Hand?> {
        val sorted = hands.sortedBy { 1f - it.pts[0].x }
        var chordHand: Hand?
        var fxHand: Hand?
        if (sorted.size >= 2) {
            chordHand = sorted[0]
            fxHand = sorted[1]
        } else {
            val h = sorted[0]
            if (1f - h.pts[0].x < 0.5f) {
                chordHand = h
                fxHand = null
            } else {
                chordHand = null
                fxHand = h
            }
        }
        if (swapHands) {
            val t = chordHand
            chordHand = fxHand
            fxHand = t
        }
        return Pair(chordHand, fxHand)
    }

    fun read(hands: List<Hand>, swapHands: Boolean): HandState {
        val valid = hands.filter { it.pts.size >= 21 }
        if (valid.isEmpty()) {
            lastGesture = 0
            tilted = false
            return HandState(0, false, 0.6f, 0.5f)
        }
        val (chordHand, fxHand) = pick(valid, swapHands)

        var gesture = 0
        var tiltNow = false
        if (chordHand != null) {
            val g = classify(fingerStates(chordHand))
            gesture = if (g >= 0) g else lastGesture
            tiltNow = updateTilt(chordHand)
        } else {
            tilted = false
        }
        lastGesture = gesture

        val volume = if (fxHand != null) 0.15f + 0.85f * (1f - fxHand.pts[0].y).coerceIn(0f, 1f) else 0.6f
        val brightness = if (fxHand != null) (1f - fxHand.pts[0].x).coerceIn(0f, 1f) else 0.5f
        return HandState(gesture, tiltNow, volume, brightness)
    }
}
