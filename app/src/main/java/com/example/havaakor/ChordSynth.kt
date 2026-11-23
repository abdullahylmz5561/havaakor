package com.example.havaakor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

// ---- Ayarlanabilir sabitler ----
private const val MAX_STRINGS = 6
private const val BUF_SIZE = 2048      // en düşük ~22 Hz'e kadar yeter
private const val STRUM_GAP_MS = 14    // teller arası gecikme (strum hızı)
private const val DEBOUNCE_MS = 60     // akor değişimi bu kadar sürmeli (titremeyi önler)
private const val SUSTAIN = 0.9985f    // 1'e yaklaştıkça tel daha uzun ötüyor
private const val MUTED = 0.90f        // susturunca tellerin hızlı sönmesi

/**
 * Bir ölçü = 8 adım (sekizlik notalar, 4/4).
 * Gitar kalıbı: D = aşağı vuruş, U = yukarı vuruş, . = boş, 0..5 = tek tel (arpej).
 * Davul kalıbı: [kick, snare, hi-hat], x = vuruş.
 */
object Rhythms {
    val strumNames = listOf("Pop", "Rock", "Sekizlik", "Arpej", "Serbest")
    val strums = listOf("D.DU.UDU", "D.D.DUD.", "DUDUDUDU", "01234543", "D.......")

    val drumNames = listOf("Kapalı", "Hi-hat", "Pop", "Rock", "Balad")
    val drums = listOf(
        listOf("........", "........", "........"),
        listOf("........", "........", "xxxxxxxx"),
        listOf("x...x...", "..x...x.", "x.x.x.x."),
        listOf("x...x.x.", "..x...x.", "xxxxxxxx"),
        listOf("x.......", "....x...", "x.x.x.x."),
    )
}

/** Hızlı, kendi içinde tutarlı rastgele sayı üreteci (-1..1). */
private class Rng {
    private var s = 1234567
    fun next(): Float {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return s / 2147483648f
    }
}

/**
 * Karplus-Strong tek tel: kısa bir gürültü patlaması, bir gecikme hattında
 * dolaşırken her turda hafifçe filtrelenir; sonuç tel titreşimi gibi söner.
 * Allpass filtre, perdenin tam oturması için kesirli gecikme sağlar.
 */
private class GuitarString(private val sampleRate: Int) {
    private val buf = FloatArray(BUF_SIZE)
    private var len = 2
    private var idx = 0
    private var c = 0f
    private var apIn = 0f
    private var apOut = 0f

    fun pluck(freq: Float, velocity: Float, noise: Rng) {
        val d = sampleRate / freq - 0.5f
        val n = floor(d).toInt().coerceIn(2, BUF_SIZE - 1)
        val frac = (d - n).coerceIn(0f, 0.999f)
        len = n
        idx = 0
        c = (1f - frac) / (1f + frac)
        apIn = 0f
        apOut = 0f
        var prev = 0f
        for (i in 0 until n) {
            prev = 0.5f * prev + 0.5f * noise.next()
            buf[i] = prev * velocity
        }
    }

    fun next(decay: Float): Float {
        val x = buf[idx]
        val following = buf[if (idx + 1 >= len) 0 else idx + 1]
        val avg = 0.5f * (x + following)
        val ap = c * avg + apIn - c * apOut
        apIn = avg
        apOut = ap
        buf[idx] = ap * decay
        idx = if (idx + 1 >= len) 0 else idx + 1
        return x
    }
}

/**
 * Gitar + davul motoru.
 * - Ritim kapalıyken: akor değişince teller bir kez strum edilir.
 * - Ritim açıkken: tempoya (BPM) bağlı bir saat çalışır; her adımda seçili vuruş kalıbı
 *   o anki akoru çalar, davul kalıbı da aynı saatten tetiklenir. Akor değişimi sonraki
 *   vuruşa oturur.
 */
class ChordSynth {
    private val sampleRate = 44100

    // Analiz thread'i yazar, ses thread'i okur
    @Volatile private var committed = FloatArray(0)
    @Volatile private var version = 0
    @Volatile private var volume = 0.6f
    @Volatile private var brightness = 0.5f
    @Volatile private var bpm = 90
    @Volatile private var strumIdx = 0
    @Volatile private var drumIdx = 0
    @Volatile private var drumVol = 0.7f
    @Volatile private var rhythmOn = false
    @Volatile private var running = false
    private var thread: Thread? = null

    // Titremeyi önleyen bekleme (yalnız setChord kullanır)
    private var pending = FloatArray(0)
    private var pendingSince = 0L

    // ---- yalnız ses thread'i kullanır ----
    private val strings = Array(MAX_STRINGS) { GuitarString(sampleRate) }
    private val noise = Rng()
    private val countdown = IntArray(MAX_STRINGS) { -1 }
    private val velocity = FloatArray(MAX_STRINGS)
    private var notes = FloatArray(0)
    private var muted = true
    private var seenVersion = 0
    private var wasRunning = false
    private var step = 0
    private var stepCountdown = 0
    private var gain = 0f
    private var lp = 0f
    private val gapSamples = sampleRate * STRUM_GAP_MS / 1000

    private var kickEnv = 0f
    private var kickFreq = 48f
    private var kickPhase = 0f
    private var snareEnv = 0f
    private var snarePhase = 0f
    private var hatEnv = 0f
    private var prevNoise = 0f
    private val kickDecay = exp(-8.0 / sampleRate).toFloat()
    private val kickFreqDecay = exp(-35.0 / sampleRate).toFloat()
    private val snareDecay = exp(-20.0 / sampleRate).toFloat()
    private val hatDecay = exp(-60.0 / sampleRate).toFloat()
    private val twoPi = (2.0 * PI).toFloat()

    /** Boş liste = sustur. En fazla 6 nota (alttan üste, tel sırasıyla). */
    fun setChord(f: List<Float>) {
        val arr = f.take(MAX_STRINGS).toFloatArray()
        val now = SystemClock.uptimeMillis()
        if (!arr.contentEquals(pending)) {
            pending = arr
            pendingSince = now
        }
        if (now - pendingSince >= DEBOUNCE_MS && !arr.contentEquals(committed)) {
            committed = arr
            version += 1
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    fun setBrightness(b: Float) {
        brightness = b.coerceIn(0f, 1f)
    }

    fun setBpm(v: Int) {
        bpm = v.coerceIn(40, 220)
    }

    fun setStrum(i: Int) {
        strumIdx = i.coerceIn(0, Rhythms.strums.size - 1)
    }

    fun setDrums(i: Int) {
        drumIdx = i.coerceIn(0, Rhythms.drums.size - 1)
    }

    fun setDrumVolume(v: Float) {
        drumVol = v.coerceIn(0f, 1f)
    }

    fun setRhythm(on: Boolean) {
        rhythmOn = on
    }

    private fun strum(down: Boolean, v: Float) {
        val n = notes.size
        for (i in 0 until n) {
            countdown[i] = (if (down) i else n - 1 - i) * gapSamples
            velocity[i] = v
        }
    }

    private fun pluckOne(k: Int, v: Float) {
        if (k < notes.size) {
            countdown[k] = 0
            velocity[k] = v
        }
    }

    private fun fireStep(s: Int) {
        if (notes.isNotEmpty() && !muted) {
            val c = Rhythms.strums[strumIdx][s]
            when {
                c == 'D' -> strum(true, if (s == 0) 1f else 0.85f)
                c == 'U' -> strum(false, 0.6f)
                c in '0'..'5' -> pluckOne(c - '0', 0.9f)
            }
        }
        val d = Rhythms.drums[drumIdx]
        if (d[0][s] == 'x') {
            kickEnv = 1f
            kickFreq = 140f
        }
        if (d[1][s] == 'x') snareEnv = 1f
        if (d[2][s] == 'x') hatEnv = if (s % 2 == 0) 1f else 0.6f
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 2048))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track.play()

            val buf = ShortArray(256)
            val gainCoef = 0.0012f

            while (running) {
                // Akor değişti mi?
                val ver = version
                if (ver != seenVersion) {
                    seenVersion = ver
                    notes = committed
                    if (notes.isEmpty()) {
                        muted = true
                        for (k in 0 until MAX_STRINGS) countdown[k] = -1
                    } else {
                        muted = false
                        if (!rhythmOn) strum(true, 1f)
                    }
                }

                val rOn = rhythmOn
                if (rOn && !wasRunning) {
                    step = 0
                    stepCountdown = 0
                }
                wasRunning = rOn
                val stepSamples = maxOf(1000, sampleRate * 60 / (bpm * 2))

                val decay = if (muted) MUTED else SUSTAIN
                val target = volume
                val cutoff = 1500f + brightness * brightness * 9000f
                val a = 1f - exp(-2f * PI.toFloat() * cutoff / sampleRate)
                val dv = drumVol

                for (i in buf.indices) {
                    if (rOn) {
                        if (stepCountdown <= 0) {
                            fireStep(step)
                            step = (step + 1) and 7
                            stepCountdown += stepSamples
                        }
                        stepCountdown--
                    }

                    gain += (target - gain) * gainCoef
                    var s = 0f
                    for (k in 0 until MAX_STRINGS) {
                        val cd = countdown[k]
                        if (cd == 0) {
                            if (k < notes.size) strings[k].pluck(notes[k], velocity[k], noise)
                            countdown[k] = -1
                        } else if (cd > 0) {
                            countdown[k] = cd - 1
                        }
                        s += strings[k].next(decay)
                    }
                    lp += a * (s * 0.25f - lp)
                    var out = lp * gain

                    // Davul (süzgeçten geçmez; hi-hat parlak kalsın)
                    var d = 0f
                    if (kickEnv > 0.001f) {
                        kickFreq = 48f + (kickFreq - 48f) * kickFreqDecay
                        kickPhase += twoPi * kickFreq / sampleRate
                        if (kickPhase > twoPi) kickPhase -= twoPi
                        d += sin(kickPhase) * kickEnv
                        kickEnv *= kickDecay
                    }
                    if (snareEnv > 0.001f) {
                        snarePhase += twoPi * 185f / sampleRate
                        if (snarePhase > twoPi) snarePhase -= twoPi
                        d += (noise.next() * 0.6f + sin(snarePhase) * 0.35f) * snareEnv
                        snareEnv *= snareDecay
                    }
                    if (hatEnv > 0.001f) {
                        val n = noise.next()
                        d += (n - prevNoise) * 0.35f * hatEnv
                        prevNoise = n
                        hatEnv *= hatDecay
                    }
                    out += d * dv * 0.8f

                    val soft = out / (1f + abs(out)) // yumuşak kırpma
                    buf[i] = (soft * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                track.write(buf, 0, buf.size)
            }
            track.stop()
            track.release()
        }.also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
