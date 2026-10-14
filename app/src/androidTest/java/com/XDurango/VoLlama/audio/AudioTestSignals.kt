package com.XDurango.VoLlama.audio

import android.content.Context
import com.XDurango.VoLlama.Audio.OpusCodec
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Señales de prueba (WAV real o sintética) y métricas de calidad para los tests de audio. */
object AudioTestSignals {

    const val SAMPLE_RATE = OpusCodec.SAMPLE_RATE
    const val FRAME = OpusCodec.FRAME_SIZE_SAMPLES

    class Signal(val name: String, val samples: ShortArray) {
        val seconds get() = samples.size.toDouble() / SAMPLE_RATE
        /** Frames completos de 20ms (el resto se descarta para que el emparejado de 2 sea exacto). */
        fun frames(): List<ShortArray> {
            val count = samples.size / (FRAME * 2) * 2
            return List(count) { samples.copyOfRange(it * FRAME, (it + 1) * FRAME) }
        }
    }

    /** `speech.wav` de assets si existe; si no, voz sintética. */
    fun load(context: Context): Signal =
        try {
            val bytes = context.assets.open("speech.wav").use { it.readBytes() }
            Signal("speech.wav", resampleTo16k(readWav(bytes)))
        } catch (_: FileNotFoundException) {
            synthVoice()
        }

    // ── WAV ───────────────────────────────────────────────────────────────────

    private class Wav(val rate: Int, val channels: Int, val pcm: ShortArray)

    private fun readWav(bytes: ByteArray): Wav {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "no es WAV" }
        var pos = 12
        var rate = 0; var channels = 0; var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                require(b.getShort(body).toInt() == 1) { "solo PCM" }
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(bits == 16) { "solo 16 bits" }
                val n = minOf(size, bytes.size - body) / 2
                val pcm = ShortArray(n) { b.getShort(body + it * 2) }
                return Wav(rate, channels, pcm)
            }
            pos = body + size + (size and 1)
        }
        error("WAV sin chunk data")
    }

    /** Mezcla a mono y remuestrea a 16kHz (promedio de ventana al reducir, interpolación lineal). */
    private fun resampleTo16k(wav: Wav): ShortArray {
        val mono = FloatArray(wav.pcm.size / wav.channels) { i ->
            var sum = 0f
            for (c in 0 until wav.channels) sum += wav.pcm[i * wav.channels + c]
            sum / wav.channels
        }
        val ratio = wav.rate.toDouble() / SAMPLE_RATE
        val out = ShortArray((mono.size / ratio).toInt())
        val half = max(1, ceil(ratio / 2).toInt())
        for (i in out.indices) {
            val center = i * ratio
            var acc = 0f; var n = 0
            for (k in -half + 1..half) {
                val idx = (center + k).toInt()
                if (idx in mono.indices) { acc += mono[idx]; n++ }
            }
            out[i] = (acc / max(n, 1)).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ── Voz sintética ─────────────────────────────────────────────────────────

    /** ~4 s: sílabas con tono variable y formantes, separadas por pausas (como una frase hablada). */
    fun synthVoice(seconds: Double = 4.0): Signal {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = ShortArray(n)
        val syllable = 0.35 // s
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val idx = (t / syllable).toInt()
            val local = (t % syllable) / syllable
            val voiced = idx % 5 != 4 // cada quinta "sílaba" es pausa
            val env = if (voiced) sin(PI * local).let { it * it } else 0.0
            val f0 = 110.0 + 35.0 * sin(2 * PI * 0.8 * t) + 10.0 * (idx % 3)
            phase += 2 * PI * f0 / SAMPLE_RATE
            val formants = doubleArrayOf(600.0 + 150 * (idx % 3), 1500.0 - 100 * (idx % 2), 2600.0)
            var s = 0.0
            var h = 1
            while (h * f0 < 5000) {
                val freq = h * f0
                var gain = 0.0
                for ((j, fm) in formants.withIndex()) {
                    val bw = 90.0 + 60 * j
                    gain += exp(-((freq - fm) * (freq - fm)) / (2 * bw * bw)) / (j + 1)
                }
                s += gain * sin(h * phase) / sqrt(h.toDouble())
                h++
            }
            val noise = (((i * 1103515245L + 12345L) and 0x7fffffff) / 2147483647.0 - 0.5) * 0.02
            out[i] = ((s * 0.55 + noise) * env * 12000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return Signal("sintetica", out)
    }

    // ── Métricas ──────────────────────────────────────────────────────────────

    fun rms(x: ShortArray, from: Int = 0, to: Int = x.size): Double {
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return sqrt(e / max(1, to - from))
    }

    fun peak(x: ShortArray): Int = x.maxOfOrNull { abs(it.toInt()) } ?: 0

    class Match(val lag: Int, val correlation: Double, val snrDb: Double)

    /**
     * Compara [decoded] con [reference] buscando el retardo (en muestras) que mejor los alinea
     * dentro de [lagRange]: Opus tiene latencia algorítmica, así que no coinciden muestra a muestra.
     * SNR con ganancia óptima: tolera pequeñas diferencias de nivel.
     */
    fun align(reference: ShortArray, decoded: ShortArray, lagRange: IntRange = -200..800): Match {
        var best = Match(0, -1.0, Double.NEGATIVE_INFINITY)
        for (lag in lagRange) {
            val start = max(0, -lag)
            val end = minOf(reference.size, decoded.size - lag)
            if (end - start < SAMPLE_RATE / 2) continue
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in start until end) {
                val x = reference[i].toDouble(); val y = decoded[i + lag].toDouble()
                xy += x * y; xx += x * x; yy += y * y
            }
            if (xx == 0.0 || yy == 0.0) continue
            val corr = xy / sqrt(xx * yy)
            if (corr > best.correlation) {
                val gain = xy / yy
                val err = xx - 2 * gain * xy + gain * gain * yy
                best = Match(lag, corr, 10 * log10(xx / max(err, 1e-9)))
            }
        }
        return best
    }

    fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(out, pos); pos += p.size }
        return out
    }
}
