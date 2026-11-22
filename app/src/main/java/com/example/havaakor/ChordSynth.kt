package com.example.havaakor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Basit pad synth: her nota için iki hafif detune testere + bir sinüs,
 * tek kutuplu low-pass filtre ve yumuşatılmış ses seviyesi.
 * AudioTrack'e ayrı bir thread'den akış yazar.
 */
class ChordSynth {
    private val sampleRate = 44100

    @Volatile private var freqs: FloatArray = FloatArray(0)
    @Volatile private var volume = 0.6f
    @Volatile private var brightness = 0.5f
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Boş liste = sessizlik. En fazla 4 nota. */
    fun setChord(f: List<Float>) {
        freqs = f.take(4).toFloatArray()
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

            val buf = ShortArray(256)
            val phase = DoubleArray(12) // 4 nota x 3 osilatör
            var gain = 0f
            var lp = 0f
            val gainCoef = 0.0012f // ~20 ms'lik yumuşak giriş/çıkış

            while (running) {
                val f = freqs
                val target = if (f.isEmpty()) 0f else volume
                val cutoff = 300f + brightness * brightness * 5000f
                val a = 1f - exp(-2f * PI.toFloat() * cutoff / sampleRate)

                for (i in buf.indices) {
                    gain += (target - gain) * gainCoef
                    var s = 0f
                    for (v in f.indices) {
                        val hz = f[v].toDouble()
                        val k = v * 3
                        phase[k] = (phase[k] + hz * 0.997 / sampleRate) % 1.0
                        phase[k + 1] = (phase[k + 1] + hz * 1.003 / sampleRate) % 1.0
                        phase[k + 2] = (phase[k + 2] + hz / sampleRate) % 1.0
                        s += (2.0 * phase[k] - 1.0).toFloat()
                        s += (2.0 * phase[k + 1] - 1.0).toFloat()
                        s += sin(2.0 * PI * phase[k + 2]).toFloat() * 1.5f
                    }
                    lp += a * (s * 0.06f - lp)
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
