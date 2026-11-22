package com.example.havaakor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

// ---- Ayarlanabilir sabitler ----
private const val MAX_STRINGS = 6
private const val BUF_SIZE = 2048      // en düşük ~22 Hz'e kadar yeter
private const val STRUM_GAP_MS = 22    // teller arası gecikme (strum hızı)
private const val RESTRUM_MS = 800     // akor tutulurken yeniden çalma aralığı
private const val DEBOUNCE_MS = 60     // akor değişimi bu kadar sürmeli (titremeyi önler)
private const val SUSTAIN = 0.9985f    // 1'e yaklaştıkça tel daha uzun ötüyor
private const val MUTED = 0.90f        // susturunca tellerin hızlı sönmesi

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
        val d = sampleRate / freq - 0.5f // ortalama filtresi 0.5 örnek gecikme ekler
        val n = floor(d).toInt().coerceIn(2, BUF_SIZE - 1)
        val frac = (d - n).coerceIn(0f, 0.999f)
        len = n
        idx = 0
        c = (1f - frac) / (1f + frac)
        apIn = 0f
        apOut = 0f
        var prev = 0f
        for (i in 0 until n) {
            prev = 0.5f * prev + 0.5f * noise.next() // yumuşatılmış "pena" vuruşu
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
 * Gitar benzeri synth. Akor değişince teller sırayla çalınır (strum);
 * akor tutulurken aşağı/yukarı değişen vuruşlarla yeniden çalınır.
 * Akor boş gelirse teller hızla susturulur.
 */
class ChordSynth {
    private val sampleRate = 44100

    // Analiz thread'i yazar, ses thread'i okur
    @Volatile private var committed = FloatArray(0)
    @Volatile private var version = 0
    @Volatile private var volume = 0.6f
    @Volatile private var brightness = 0.5f
    @Volatile private var running = false
    private var thread: Thread? = null

    // Titremeyi önleyen bekleme (yalnız setChord kullanır)
    private var pending = FloatArray(0)
    private var pendingSince = 0L

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

            val strings = Array(MAX_STRINGS) { GuitarString(sampleRate) }
            val noise = Rng()
            val countdown = IntArray(MAX_STRINGS) { -1 } // tel çalınmadan önceki bekleme (örnek)
            val velocity = FloatArray(MAX_STRINGS)
            var notes = FloatArray(0)
            var muted = true
            var seenVersion = 0
            var sinceStrum = 0
            var nextIsDown = true

            val gapSamples = sampleRate * STRUM_GAP_MS / 1000
            val restrumSamples = sampleRate * RESTRUM_MS / 1000

            fun strum(downward: Boolean, v: Float) {
                val n = notes.size
                for (i in 0 until n) {
                    countdown[i] = (if (downward) i else n - 1 - i) * gapSamples
                    velocity[i] = v
                }
            }

            val buf = ShortArray(256)
            var gain = 0f
            var lp = 0f
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
                        strum(true, 1f)
                        nextIsDown = false
                        sinceStrum = 0
                    }
                } else if (!muted && sinceStrum >= restrumSamples) {
                    strum(nextIsDown, if (nextIsDown) 0.9f else 0.7f)
                    nextIsDown = !nextIsDown
                    sinceStrum = 0
                }
                if (!muted) sinceStrum += buf.size

                val decay = if (muted) MUTED else SUSTAIN
                val target = volume
                val cutoff = 1500f + brightness * brightness * 9000f
                val a = 1f - exp(-2f * PI.toFloat() * cutoff / sampleRate)

                for (i in buf.indices) {
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
                    val out = lp * gain
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
