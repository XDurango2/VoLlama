package com.XDurango.VoLlama.Audio

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lógica del pipeline (emparejado, secuencia, PLC, descarte) con el codec y el merger simulados. */
class OpusFramePipelineTest {

    private class Sent(val seq: Int, val data: ByteArray)

    private val frame = ShortArray(OpusCodec.FRAME_SIZE_SAMPLES)
    private val sent = mutableListOf<Sent>()
    private val sink: PacketSink = { seq, data, len -> sent += Sent(seq, data.copyOf(len)) }

    // ── Encoder ───────────────────────────────────────────────────────────────

    private fun encoder(
        initialSeq: Int = 0,
        codec: OpusCodec = mockk { every { encode(any()) } answers { byteArrayOf(1, 2) } },
        merger: PacketMerger = object : PacketMerger {
            override fun merge(first: ByteArray, second: ByteArray, out: ByteArray): Int {
                val merged = first + second
                merged.copyInto(out)
                return merged.size
            }
            override fun release() {}
        }
    ) = OpusFrameEncoder(codec, merger, initialSeq)

    @Test fun `cada dos frames se emite un paquete fusionado`() {
        val enc = encoder()
        enc.encode(frame, sink)
        assertTrue("el primer frame espera pareja", sent.isEmpty())
        enc.encode(frame, sink)

        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(1, 2, 1, 2), sent[0].data)
    }

    @Test fun `la secuencia es consecutiva`() {
        val enc = encoder()
        repeat(8) { enc.encode(frame, sink) }

        assertEquals(listOf(0, 1, 2, 3), sent.map { it.seq })
    }

    @Test fun `la secuencia da la vuelta en 0xFFFF`() {
        val enc = encoder(initialSeq = 0xFFFE)
        repeat(8) { enc.encode(frame, sink) }

        assertEquals(listOf(0xFFFE, 0xFFFF, 0, 1), sent.map { it.seq })
    }

    @Test fun `flush envia el frame suelto sin fusionar`() {
        val enc = encoder()
        enc.encode(frame, sink)
        enc.flush(sink)

        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(1, 2), sent[0].data)
    }

    @Test fun `flush sin frame pendiente no envia nada, y es idempotente`() {
        val enc = encoder()
        enc.flush(sink)
        enc.encode(frame, sink)
        enc.encode(frame, sink)
        sent.clear()
        enc.flush(sink)
        enc.flush(sink)

        assertTrue(sent.isEmpty())
    }

    @Test fun `si el codec no produce paquete el frame se descarta sin romper el emparejado`() {
        val results = ArrayDeque(listOf<ByteArray?>(byteArrayOf(1), null, byteArrayOf(2)))
        val codec = mockk<OpusCodec> { every { encode(any()) } answers { results.removeFirst() } }
        val enc = encoder(codec = codec)
        repeat(3) { enc.encode(frame, sink) }

        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(1, 2), sent[0].data)
    }

    @Test fun `una lectura parcial del microfono se completa al tamano de frame`() {
        val received = mutableListOf<Int>()
        val codec = mockk<OpusCodec> { every { encode(any()) } answers { received += firstArg<ShortArray>().size; byteArrayOf(1) } }
        val enc = encoder(codec = codec)
        enc.encode(ShortArray(100), sink)

        assertEquals(listOf(OpusCodec.FRAME_SIZE_SAMPLES), received)
    }

    @Test fun `si el merger falla no se emite paquete ni se consume secuencia`() {
        val failing = object : PacketMerger {
            override fun merge(first: ByteArray, second: ByteArray, out: ByteArray) = 0
            override fun release() {}
        }
        val enc = encoder(merger = failing)
        repeat(2) { enc.encode(frame, sink) }

        assertTrue(sent.isEmpty())
    }

    // ── Decoder ───────────────────────────────────────────────────────────────

    private val pcm = ShortArray(10) { it.toShort() }
    private val codec = mockk<OpusCodec>(relaxed = true) {
        every { decodeAggregated(any(), any()) } returns pcm
        every { decode(null) } returns ShortArray(5)
    }
    private val played = mutableListOf<ShortArray>()
    private val decoder = OpusFrameDecoder(codec)

    private fun packet(seq: Int) = byteArrayOf((seq shr 8).toByte(), seq.toByte(), 9, 9)

    @Test fun `paquetes en orden se decodifican sin ocultar nada`() {
        (0..4).forEach { decoder.decode(packet(it)) { p -> played += p } }

        assertEquals(5, decoder.packetsDecoded)
        assertEquals(0, decoder.concealedFrames)
        assertEquals(5, played.size)
    }

    @Test fun `un paquete perdido genera PLC antes del siguiente`() {
        decoder.decode(packet(0)) { played += it }
        decoder.decode(packet(2)) { played += it }

        assertEquals(1, decoder.concealedFrames)
        verify(exactly = 1) { codec.decode(null) }
        assertEquals(3, played.size) // paquete 0, PLC, paquete 2
    }

    @Test fun `una perdida grande reinicia el decoder y sigue decodificando`() {
        decoder.decode(packet(0)) { played += it }
        decoder.decode(packet(50)) { played += it }

        assertEquals(1, decoder.decoderResets)
        verify(exactly = 1) { codec.resetDecoder() }
        assertEquals(2, decoder.packetsDecoded)
    }

    @Test fun `un duplicado se descarta sin tocar el decoder`() {
        decoder.decode(packet(0)) { played += it }
        decoder.decode(packet(1)) { played += it }
        decoder.decode(packet(1)) { played += it } // duplicado
        decoder.decode(packet(0)) { played += it } // tardio

        assertEquals(2, decoder.packetsDropped)
        assertEquals(0, decoder.decoderResets)
        assertEquals(2, decoder.packetsDecoded)
        verify(exactly = 0) { codec.resetDecoder() }
    }

    @Test fun `tras descartar un duplicado el siguiente paquete en orden sigue siendo valido`() {
        decoder.decode(packet(0)) { played += it }
        decoder.decode(packet(0)) { played += it }
        decoder.decode(packet(1)) { played += it }

        assertEquals(0, decoder.concealedFrames)
        assertEquals(2, decoder.packetsDecoded)
    }

    @Test fun `la secuencia atraviesa 0xFFFF sin ocultar ni reiniciar`() {
        listOf(0xFFFE, 0xFFFF, 0, 1).forEach { decoder.decode(packet(it)) { p -> played += p } }

        assertEquals(0, decoder.concealedFrames)
        assertEquals(0, decoder.decoderResets)
        assertEquals(4, decoder.packetsDecoded)
    }

    @Test fun `paquetes sin payload se ignoran`() {
        decoder.decode(byteArrayOf()) { played += it }
        decoder.decode(byteArrayOf(0, 1)) { played += it }

        assertEquals(0, decoder.packetsDecoded)
        assertTrue(played.isEmpty())
    }

    @Test fun `si el codec no devuelve audio no se reproduce nada pero la secuencia avanza`() {
        every { codec.decodeAggregated(any(), any()) } returns null
        decoder.decode(packet(0)) { played += it }
        every { codec.decodeAggregated(any(), any()) } returns pcm
        decoder.decode(packet(1)) { played += it }

        assertEquals(0, decoder.concealedFrames)
        assertEquals(1, played.size)
    }
}
