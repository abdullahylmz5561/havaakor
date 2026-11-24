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
private const val STRUM_GAP_MS = 14    // gitar: teller arası gecikme (strum hızı)
private const val PIANO_GAP_MS = 2     // piyano: notalar neredeyse aynı anda basılır
private const val DEBOUNCE_MS = 60     // akor değişimi bu kadar sürmeli (titremeyi önler)
private const val SUSTAIN = 0.9985f    // gitar: 1'e yaklaştıkça tel daha uzun ötüyor
private const val MUTED = 0.90f        // gitar: yumrukla susturunca tellerin sönmesi
private const val DAMP = 0.60f         // gitar: '-' kapatma, kısa süre hızlı sönme
private const val DAMP_MS = 50         // '-' kapatmanın süresi
private const val JITTER_MS = 5        // insan dokunuşu: vuruşlara eklenen en çok gecikme

/** Ses seçenekleri: 0 = gitar, 1 = elektrik piyano. */
object Instruments {
    val names = listOf("Gitar", "Piyano")
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

    fun clear() {
        buf.fill(0f)
        apIn = 0f
        apOut = 0f
    }

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
 * Elektrik piyano (Rhodes tarzı) tek nota: FM sentezi.
 * Taşıyıcı sinüsü, aynı frekanstaki bir modülatörle bükülür; modülasyon derinliği hızla
 * azalır (vuruşta parlak, sonra yumuşak). Kısa, yüksek bir "tine" çınlaması vuruşa karakter
 * katar. Yüksek notalar daha çabuk söner.
 */
private class PianoVoice(private val sampleRate: Int) {
    private val twoPi = (2.0 * PI).toFloat()
    private val modDecay = exp(-5.0 / sampleRate).toFloat()
    private val tineDecay = exp(-55.0 / sampleRate).toFloat()
    private val dampDecay = exp(-30.0 / sampleRate).toFloat()

    private var freq = 0f
    private var pc = 0f
    private var pm = 0f
    private var pt = 0f
    private var env = 0f
    private var peak = 0f
    private var attack = false
    private var releasing = false
    private var modEnv = 0f
    private var tineEnv = 0f
    private var decayPerSample = 0.9999f

    fun clear() {
        env = 0f
        peak = 0f
        attack = false
        releasing = false
        modEnv = 0f
        tineEnv = 0f
    }

    /** Pedal bırakma: yeni vuruş gelene kadar nota hızla söner. */
    fun release() {
        releasing = true
    }

    fun trigger(f: Float, vel: Float) {
        freq = f
        peak = vel
        attack = true
        releasing = false
        modEnv = vel
        tineEnv = vel
        val rate = 0.7 + f / 450.0
        decayPerSample = exp(-rate / sampleRate).toFloat()
    }

    /** damp = true ise (yumruk, kapatma) hızla susar. */
    fun next(damp: Boolean): Float {
        if (!attack && env < 0.0005f) return 0f
        if (attack) {
            env += (peak - env) * 0.012f
            if (env >= peak * 0.97f) attack = false
        } else {
            env *= if (damp || releasing) dampDecay else decayPerSample
        }
        modEnv *= modDecay
        tineEnv *= tineDecay

        val inc = twoPi * freq / sampleRate
        pc += inc
        pm += inc
        pt += inc * 9f
        if (pc > twoPi) pc -= twoPi
        if (pm > twoPi) pm -= twoPi
        if (pt > twoPi) pt -= twoPi

        val mod = sin(pm) * (0.4f + 2.2f * modEnv)
        val tone = sin(pc + mod)
        val tine = sin(pt) * tineEnv * 0.25f
        return (tone + tine) * env
    }
}

/**
 * Gitar/piyano + davul motoru.
 * - Ritim kapalıyken: akor değişince akor bir kez çalınır (gitarda strum, piyanoda blok akor).
 * - Ritim açıkken: tempoya (BPM) bağlı bir saat çalışır; her adımda seçili kalıp
 *   o anki akoru çalar, davul kalıbı da aynı saatten tetiklenir. Akor değişimi sonraki
 *   vuruşa oturur. Kalıp uzunluğu ölçüyü belirler (8 adım = 4/4, 6 adım = 6/8).
 * - stop() sesi tamamen keser; start() temiz bir durumla yeniden başlatır.
 */
class ChordSynth {
    private val sampleRate = 44100

    // Analiz thread'i yazar, ses thread'i okur
    @Volatile private var committed = FloatArray(0)
    @Volatile private var version = 0
    @Volatile private var volume = 0.6f
    @Volatile private var brightness = 0.5f
    @Volatile private var bpm = 90
    @Volatile private var pattern: Pattern = Patterns.guitarRhythm[0]
    @Volatile private var drumIdx = 0
    @Volatile private var drumVol = 0.7f
    @Volatile private var rhythmOn = false
    @Volatile private var instrument = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    // Titremeyi önleyen bekleme (yalnız setChord kullanır)
    private var pending = FloatArray(0)
    private var pendingSince = 0L

    // ---- yalnız ses thread'i kullanır ----
    private val strings = Array(MAX_STRINGS) { GuitarString(sampleRate) }
    private val voices = Array(MAX_STRINGS) { PianoVoice(sampleRate) }
    private val noise = Rng()
    private val countdown = IntArray(MAX_STRINGS) { -1 }
    private val velocity = FloatArray(MAX_STRINGS)
    private var notes = FloatArray(0)
    private var muted = true
    private var seenVersion = 0
    private var activeInstrument = 0
    private var activePattern: Pattern = pattern
    private var drumRows: List<String> = DrumKits.rows(0, 8)
    private var lastDrum = 0
    private var lastLen = 8
    private var wasRunning = false
    private var step = 0
    private var stepCountdown = 0
    private var dampLeft = 0
    private var gain = 0f
    private var lp = 0f
    private val gapSamples = sampleRate * STRUM_GAP_MS / 1000
    private val pianoGap = sampleRate * PIANO_GAP_MS / 1000
    private val dampSamples = sampleRate * DAMP_MS / 1000
    private val jitterMax = sampleRate * JITTER_MS / 1000

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

    /** Boş liste = sustur. En fazla 6 nota (alttan üste). */
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

    fun setPattern(p: Pattern) {
        pattern = p
    }

    fun setDrums(i: Int) {
        drumIdx = i.coerceIn(0, DrumKits.names.size - 1)
    }

    fun setDrumVolume(v: Float) {
        drumVol = v.coerceIn(0f, 1f)
    }

    fun setRhythm(on: Boolean) {
        rhythmOn = on
    }

    fun setInstrument(i: Int) {
        instrument = i.coerceIn(0, Instruments.names.size - 1)
    }

    private fun jitter(): Int = ((noise.next() + 1f) * 0.5f * jitterMax).toInt()

    /** Ritim kapalıyken akor değişince çalınan tek vuruş. */
    private fun onceStep(): Step =
        if (activeInstrument == 1) Patterns.pianoBlock else Patterns.guitarStrumOnce

    /** Bir adımdaki notaları zamanlar: countdown[k] kaç örnek sonra çalınacağını belirler. */
    private fun schedule(st: Step, human: Boolean) {
        if (notes.isEmpty() || muted) return
        val gap = if (activeInstrument == 1) pianoGap else gapSamples
        val n = st.slots.size
        for (idx in 0 until n) {
            val k = st.slots[idx]
            if (k >= notes.size) continue
            val order = if (st.kind == KIND_UP) n - 1 - idx else idx
            val delay = if (st.kind == KIND_BLOCK && n == 1) 0 else order * gap
            countdown[k] = delay + (if (human) jitter() else 0)
            velocity[k] = st.vels[idx] * (if (human) 1f + noise.next() * 0.07f else 1f)
        }
    }

    private fun clearVoices() {
        for (s in strings) s.clear()
        for (v in voices) v.clear()
        for (k in 0 until MAX_STRINGS) countdown[k] = -1
    }

    /** Durdurulup yeniden başlatılınca eski akor ve vuruşlardan hiçbir şey kalmasın. */
    private fun resetState() {
        clearVoices()
        notes = FloatArray(0)
        muted = true
        committed = FloatArray(0)
        pending = FloatArray(0)
        seenVersion = version
        activeInstrument = instrument
        activePattern = pattern
        lastDrum = drumIdx
        lastLen = activePattern.length
        drumRows = DrumKits.rows(lastDrum, lastLen)
        gain = 0f
        lp = 0f
        kickEnv = 0f
        snareEnv = 0f
        hatEnv = 0f
        dampLeft = 0
        step = 0
        stepCountdown = 0
        wasRunning = false
    }

    private fun fireStep(s: Int) {
        val st = activePattern.steps[s]
        when (st.kind) {
            KIND_NONE -> {}
            KIND_DAMP -> dampLeft = dampSamples
            else -> schedule(st, true)
        }
        if (drumRows[0][s] == 'x') {
            kickEnv = 1f
            kickFreq = 140f
        }
        if (drumRows[1][s] == 'x') snareEnv = 1f
        if (drumRows[2][s] == 'x') hatEnv = if (s % 2 == 0) 1f else 0.6f
    }

    fun start() {
        if (running) return
        resetState()
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
                // Ses (gitar/piyano) değişti mi?
                val inst = instrument
                if (inst != activeInstrument) {
                    clearVoices()
                    activeInstrument = inst
                    if (!muted && notes.isNotEmpty() && !rhythmOn) schedule(onceStep(), false)
                }
                val piano = activeInstrument == 1

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
                        // piyano pedalı: önceki akorun notaları hızla bırakılır
                        if (piano) for (v in voices) v.release()
                        if (!rhythmOn) schedule(onceStep(), false)
                    }
                }

                // Kalıp ya da davul değişti mi?
                val pat = pattern
                if (pat !== activePattern) {
                    activePattern = pat
                    step = 0
                }
                val di = drumIdx
                if (di != lastDrum || activePattern.length != lastLen) {
                    lastDrum = di
                    lastLen = activePattern.length
                    drumRows = DrumKits.rows(lastDrum, lastLen)
                }

                val rOn = rhythmOn
                if (rOn && !wasRunning) {
                    step = 0
                    stepCountdown = 0
                }
                wasRunning = rOn
                val stepSamples = maxOf(1000, sampleRate * 60 / (bpm * 2))

                val damping = dampLeft > 0
                val decay = if (muted) MUTED else if (damping) DAMP else SUSTAIN
                val pianoDamp = muted || damping
                val target = volume
                val cutoff = 1500f + brightness * brightness * 9000f
                val a = 1f - exp(-2f * PI.toFloat() * cutoff / sampleRate)
                val dv = drumVol
                val inScale = if (piano) 0.18f else 0.25f

                for (i in buf.indices) {
                    if (rOn) {
                        if (stepCountdown <= 0) {
                            fireStep(step)
                            step = (step + 1) % activePattern.length
                            stepCountdown += stepSamples
                        }
                        stepCountdown--
                    }

                    gain += (target - gain) * gainCoef
                    var s = 0f
                    for (k in 0 until MAX_STRINGS) {
                        val cd = countdown[k]
                        if (cd == 0) {
                            if (k < notes.size) {
                                if (piano) {
                                    voices[k].trigger(notes[k], velocity[k])
                                } else {
                                    strings[k].pluck(notes[k], velocity[k], noise)
                                }
                            }
                            countdown[k] = -1
                        } else if (cd > 0) {
                            countdown[k] = cd - 1
                        }
                        s += if (piano) voices[k].next(pianoDamp) else strings[k].next(decay)
                    }
                    lp += a * (s * inScale - lp)
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
                if (dampLeft > 0) dampLeft -= buf.size
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
