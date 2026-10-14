package com.XDurango.VoLlama.audio

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.XDurango.VoLlama.Audio.OpusCodec
import com.XDurango.VoLlama.Audio.OpusFrameDecoder
import com.XDurango.VoLlama.Audio.OpusFrameEncoder
import com.XDurango.VoLlama.Audio.RepacketizerMerger
import com.XDurango.VoLlama.audio.AudioTestSignals.FRAME
import com.XDurango.VoLlama.audio.AudioTestSignals.SAMPLE_RATE
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * Pipeline de audio completo (OpusFrameEncoder → "red" simulada → OpusFrameDecoder) con el codec
 * nativo real y un archivo de audio de entrada (assets/speech.wav, o voz sintética si no existe).
 * Es lo más cercano a una llamada que se puede probar sin micrófono ni altavoz.
 */
@RunWith(AndroidJUnit4::class)
class AudioFileRoundTripTest {

    private class Packet(val seq: Int, val bytes: ByteArray)
    private class Decoded(val pcm: ShortArray, val decoder: OpusFrameDecoder)

    private val codec = OpusCodec()
    private lateinit var signal: AudioTestSignals.Signal
    private lateinit var frames: List<ShortArray>

    @Before fun setUp() {
        signal = AudioTestSignals.load(InstrumentationRegistry.getInstrumentation().context)
        frames = signal.frames()
        codec.open()
        log("señal=${signal.name} duración=${"%.2f".format(signal.seconds)}s frames=${frames.size}")
    }

    @After fun tearDown() { codec.close() }

    private fun log(msg: String) = Log.i("AudioFileTest", msg)

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Como al empezar una llamada: libera y recrea encoder/decoder nativos (estado limpio). */
    private fun freshCodec() { codec.close(); codec.open() }

    private fun encode(input: List<ShortArray> = frames, initialSeq: Int = 0): List<Packet> {
        freshCodec()
        val encoder = OpusFrameEncoder(codec, RepacketizerMerger(), initialSeq)
        val out = mutableListOf<Packet>()
        val sink: (Int, ByteArray, Int) -> Unit = { seq, data, len -> out += Packet(seq, data.copyOf(len)) }
        input.forEach { encoder.encode(it, sink) }
        encoder.flush(sink)
        encoder.release()
        return out
    }

    private fun wire(p: Packet) = byteArrayOf((p.seq shr 8).toByte(), p.seq.toByte()) + p.bytes

    private fun decode(packets: List<Packet>): Decoded {
        freshCodec()
        val decoder = OpusFrameDecoder(codec)
        val out = mutableListOf<ShortArray>()
        packets.forEach { decoder.decode(wire(it)) { pcm -> out += pcm } }
        return Decoded(AudioTestSignals.concat(out), decoder)
    }

    // ── Calidad sin pérdidas ──────────────────────────────────────────────────

    @Test fun sinPerdidas_conservaLaForma_yLaDuracion() {
        val result = decode(encode())

        assertEquals("duración", frames.size * FRAME, result.pcm.size)
        val m = AudioTestSignals.align(AudioTestSignals.concat(frames), result.pcm)
        log("limpio: lag=${m.lag} corr=${"%.3f".format(m.correlation)} snr=${"%.1f".format(m.snrDb)}dB")
        // Medido: voz sintética corr 0.99 / 16 dB; voz real (48k→16k) corr 0.93 / 9 dB. Opus es perceptual.
        assertTrue("correlación ${m.correlation}", m.correlation > 0.85)
        assertTrue("SNR ${m.snrDb} dB", m.snrDb > 6.0)
        assertEquals(0, result.decoder.concealedFrames)
        assertEquals(0, result.decoder.decoderResets)
    }

    @Test fun elNivelSeConserva() {
        val result = decode(encode())
        val ratio = AudioTestSignals.rms(result.pcm) / AudioTestSignals.rms(AudioTestSignals.concat(frames))
        log("ratio de nivel RMS=${"%.2f".format(ratio)}")
        assertTrue("ratio $ratio", ratio in 0.7..1.3)
        assertTrue("sin clipping", AudioTestSignals.peak(result.pcm) < 32767)
    }

    @Test fun elBitrateEstaEnElPerfilConfigurado() {
        val packets = encode()
        val bytes = packets.sumOf { it.bytes.size }
        val kbps = bytes * 8 / signal.seconds / 1000
        log("bitrate efectivo=${"%.1f".format(kbps)} kbps en ${packets.size} paquetes")
        // Perfil Default = 24 kbps CBR; el repacketizer añade/ahorra un byte de TOC por paquete.
        assertTrue("bitrate $kbps", kbps in 16.0..30.0)
        assertEquals(frames.size / 2, packets.size)
    }

    @Test fun nuncaSeGeneraUnPaqueteMasGrandeQueElLimite() {
        assertTrue(encode().all { it.bytes.size in 1..1275 * 2 })
    }

    // ── Red: pérdida, duplicados, wrap ────────────────────────────────────────

    @Test fun perdidasAisladas_seOcultanSinReiniciarElDecoder() {
        val packets = encode()
        val rnd = Random(42)
        val kept = mutableListOf<Packet>()
        var lost = 0
        packets.forEachIndexed { i, p ->
            val canDrop = i > 2 && i < packets.size - 1 && (kept.isEmpty() || kept.last().seq == packets[i - 1].seq)
            if (canDrop && rnd.nextInt(10) == 0) lost++ else kept += p
        }
        val result = decode(kept)
        val full = AudioTestSignals.concat(frames)
        log("pérdidas: perdidos=$lost ocultados=${result.decoder.concealedFrames} " +
            "muestras out=${result.pcm.size} in=${full.size}")

        assertTrue("el caso debe perder paquetes", lost > 0)
        assertEquals(lost, result.decoder.concealedFrames)
        assertEquals(0, result.decoder.decoderResets)
        // Sin NaN/explosiones: el nivel sigue siendo comparable al original.
        val ratio = AudioTestSignals.rms(result.pcm) / AudioTestSignals.rms(full)
        assertTrue("ratio de nivel $ratio", ratio in 0.6..1.4)
    }

    @Test fun duplicadosYTardios_noCambianLaSalida() {
        val packets = encode()
        val clean = decode(packets)
        val noisy = mutableListOf<Packet>()
        packets.forEachIndexed { i, p ->
            noisy += p
            if (i % 7 == 3) noisy += p               // duplicado inmediato
            if (i % 11 == 5 && i > 3) noisy += packets[i - 3] // paquete tardío
        }
        val result = decode(noisy)

        assertEquals(0, result.decoder.decoderResets)
        assertEquals(0, result.decoder.concealedFrames)
        assertTrue(result.decoder.packetsDropped > 0)
        assertArrayEquals("la salida debe ser idéntica a la limpia", clean.pcm, result.pcm)
    }

    @Test fun rafagaDePerdida_reiniciaYRecupera() {
        val packets = encode()
        val mid = packets.size / 2
        val kept = packets.filterIndexed { i, _ -> i !in mid until mid + 5 }
        val clean = decode(packets)
        val result = decode(kept)

        assertEquals(1, result.decoder.decoderResets)
        // Al final ambos streams terminan en el mismo punto de la señal: deben converger.
        val tail = SAMPLE_RATE / 2
        val a = clean.pcm.copyOfRange(clean.pcm.size - tail, clean.pcm.size)
        val b = result.pcm.copyOfRange(result.pcm.size - tail, result.pcm.size)
        val m = AudioTestSignals.align(a, b, 0..0)
        log("ráfaga: corr cola=${"%.3f".format(m.correlation)}")
        assertTrue("el decoder no se recuperó: corr=${m.correlation}", m.correlation > 0.95)
    }

    @Test fun secuenciaQueDaLaVuelta_equivaleASinVuelta() {
        val normal = decode(encode(initialSeq = 0))
        val wrapped = decode(encode(initialSeq = 0xFFF0))

        assertEquals(0, wrapped.decoder.concealedFrames)
        assertEquals(0, wrapped.decoder.decoderResets)
        assertEquals(0, wrapped.decoder.packetsDropped)
        assertArrayEquals(normal.pcm, wrapped.pcm)
    }

    // ── Casos de borde ────────────────────────────────────────────────────────

    @Test fun silencio_producePaquetesPequenosYSalidaSilenciosa() {
        val silence = List(100) { ShortArray(FRAME) }
        val packets = encode(silence)
        val result = decode(packets)

        log("silencio: bytes/paquete=${packets.map { it.bytes.size }.average()} pico=${AudioTestSignals.peak(result.pcm)}")
        assertTrue("pico ${AudioTestSignals.peak(result.pcm)}", AudioTestSignals.peak(result.pcm) < 100)
    }

    @Test fun lecturaParcialDelMicrofono_noCrasheaYSeRellenaConSilencio() {
        val partial = frames.take(10).toMutableList().also { it[3] = it[3].copyOf(100) }
        val packets = encode(partial)

        assertEquals(5, packets.size)
        assertEquals(10 * FRAME, decode(packets).pcm.size)
    }

    @Test fun framesImpares_elFrameSueltoSeEnviaAlHacerFlush() {
        val packets = encode(frames.take(5))
        val result = decode(packets)

        assertEquals(3, packets.size) // 2 fusionados + 1 suelto
        assertEquals(5 * FRAME, result.pcm.size)
    }

    // ── Entre llamadas ────────────────────────────────────────────────────────

    @Test fun dosLlamadasSeguidas_noArrastranEstado() {
        val first = encode()
        val second = encode() // encode() reabre el codec: simula la llamada siguiente

        assertEquals(first.size, second.size)
        first.indices.forEach { assertArrayEquals("paquete $it", first[it].bytes, second[it].bytes) }
        assertArrayEquals(decode(first).pcm, decode(second).pcm)
    }

    // ── Rendimiento (gama baja) ───────────────────────────────────────────────

    @Test fun codificarYDecodificarVaMuchoMasRapidoQueTiempoReal() {
        val t0 = System.nanoTime()
        val packets = encode()
        val t1 = System.nanoTime()
        decode(packets)
        val t2 = System.nanoTime()

        val encRtf = (t1 - t0) / 1e9 / signal.seconds
        val decRtf = (t2 - t1) / 1e9 / signal.seconds
        log("RTF codificar=${"%.3f".format(encRtf)} decodificar=${"%.3f".format(decRtf)} (1.0 = tiempo real)")
        // Margen amplio: un dispositivo de gama baja puede ser 5-10x más lento que este.
        assertTrue("codificar RTF $encRtf", encRtf < 0.2)
        assertTrue("decodificar RTF $decRtf", decRtf < 0.2)
    }
}
