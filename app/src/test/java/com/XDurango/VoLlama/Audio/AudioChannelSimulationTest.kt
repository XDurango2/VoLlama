package com.XDurango.VoLlama.Audio

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encoder y decoder reales conectados por un "canal" simulado (pérdida, duplicados, reordering),
 * con el codec nativo sustituido. Verifica que la política de secuencia se comporta bien de punta
 * a punta con el mismo framing que usa Nearby: `[seq 2 bytes][opus]`.
 */
class AudioChannelSimulationTest {

    private val pcm20ms = ShortArray(OpusCodec.FRAME_SIZE_SAMPLES)

    private val codec = mockk<OpusCodec>(relaxed = true) {
        every { encode(any()) } answers { byteArrayOf(1, 2) }
        every { decodeAggregated(any(), any()) } returns ShortArray(OpusCodec.FRAME_SIZE_SAMPLES * 2)
        every { decode(null) } returns ShortArray(OpusCodec.FRAME_SIZE_SAMPLES)
    }

    private val concatMerger = object : PacketMerger {
        override fun merge(first: ByteArray, second: ByteArray, out: ByteArray): Int {
            val merged = first + second
            merged.copyInto(out)
            return merged.size
        }
        override fun release() {}
    }

    private val decoder = OpusFrameDecoder(codec)
    private var played = 0
    private fun play(@Suppress("UNUSED_PARAMETER") pcm: ShortArray) { played++ }

    /** Genera [packets] paquetes de 40ms como los recibiría el decoder, empezando en [startSeq]. */
    private fun transmit(packets: Int, startSeq: Int = 0): List<ByteArray> {
        val wire = mutableListOf<ByteArray>()
        val encoder = OpusFrameEncoder(codec, concatMerger, startSeq)
        val sink: PacketSink = { seq, data, len ->
            wire += byteArrayOf((seq shr 8).toByte(), seq.toByte()) + data.copyOf(len)
        }
        repeat(packets * 2) { encoder.encode(pcm20ms, sink) }
        return wire
    }

    private fun receive(wire: List<ByteArray>) = wire.forEach { decoder.decode(it) { pcm -> play(pcm) } }

    @Test fun `canal perfecto no oculta, reinicia ni descarta nada`() {
        receive(transmit(packets = 500))

        assertEquals(500, decoder.packetsDecoded)
        assertEquals(0, decoder.concealedFrames)
        assertEquals(0, decoder.decoderResets)
        assertEquals(0, decoder.packetsDropped)
        assertEquals(500, played)
    }

    @Test fun `la secuencia da la vuelta a 0xFFFF a mitad de la llamada sin efectos`() {
        receive(transmit(packets = 100, startSeq = 0xFFF0))

        assertEquals(100, decoder.packetsDecoded)
        assertEquals(0, decoder.concealedFrames)
        assertEquals(0, decoder.decoderResets)
    }

    @Test fun `perdidas aisladas se ocultan una por una`() {
        // Se pierde cada 20º paquete (nunca dos seguidos).
        val wire = transmit(packets = 400).filterIndexed { i, _ -> i % 20 != 10 }
        val lost = 400 - wire.size
        receive(wire)

        assertEquals(lost, decoder.concealedFrames)
        assertEquals(0, decoder.decoderResets)
        assertEquals(wire.size, decoder.packetsDecoded)
        assertEquals(wire.size + lost, played) // cada hueco rellenado con un frame de PLC
    }

    @Test fun `una rafaga de 2 paquetes aun se oculta, de 3 reinicia el decoder`() {
        val base = transmit(packets = 40)
        receive(base.filterIndexed { i, _ -> i !in 10..11 })
        assertEquals(2, decoder.concealedFrames)
        assertEquals(0, decoder.decoderResets)

        val decoder2 = OpusFrameDecoder(codec)
        base.filterIndexed { i, _ -> i !in 10..12 }.forEach { decoder2.decode(it) {} }
        assertEquals(0, decoder2.concealedFrames)
        assertEquals(1, decoder2.decoderResets)
    }

    @Test fun `un corte largo no impide seguir decodificando despues`() {
        val wire = transmit(packets = 200).filterIndexed { i, _ -> i !in 50..120 }
        receive(wire)

        assertEquals(1, decoder.decoderResets)
        assertEquals(wire.size, decoder.packetsDecoded)
    }

    @Test fun `paquetes duplicados por reintentos se descartan sin alterar el audio`() {
        val wire = transmit(packets = 100)
        receive(wire.flatMapIndexed { i, p -> if (i % 10 == 0) listOf(p, p) else listOf(p) })

        assertEquals(100, decoder.packetsDecoded)
        assertEquals(10, decoder.packetsDropped)
        assertEquals(0, decoder.concealedFrames)
        assertEquals(0, decoder.decoderResets)
    }

    @Test fun `dos paquetes adyacentes intercambiados cuestan un PLC y un descarte`() {
        val wire = transmit(packets = 100).toMutableList()
        java.util.Collections.swap(wire, 40, 41)
        receive(wire)

        // Llega el 41 antes que el 40: se oculta el hueco y el 40, ya tardio, se descarta.
        assertEquals(1, decoder.concealedFrames)
        assertEquals(1, decoder.packetsDropped)
        assertEquals(99, decoder.packetsDecoded)
        assertEquals(0, decoder.decoderResets)
    }

    @Test fun `con perdida y duplicados aleatorios el decoder nunca se bloquea`() {
        val random = java.util.Random(42)
        val wire = transmit(packets = 2_000, startSeq = 0xFF00)
        val delivered = wire.filter { random.nextInt(100) >= 8 } // ~8% de perdida
            .flatMap { if (random.nextInt(100) < 3) listOf(it, it) else listOf(it) }
        receive(delivered)

        assertTrue("debe seguir decodificando", decoder.packetsDecoded > 1_500)
        assertTrue("todo paquete recibido se decodifica o se descarta",
            decoder.packetsDecoded + decoder.packetsDropped == delivered.size)
        assertTrue(played >= decoder.packetsDecoded)
    }
}
