package com.XDurango.VoLlama.Audio

import com.steegler.koopus.OpusRepacketizer

/**
 * Pipeline de audio sin dependencia de hardware: PCM de 20ms → paquetes Opus de 40ms con número de
 * secuencia (emisor) y paquetes recibidos → PCM con ocultación de pérdidas (receptor).
 *
 * [AudioEngine] solo conecta estas clases con AudioRecord/AudioTrack/Nearby, de modo que se pueden
 * probar alimentándolas con un archivo de audio.
 */

/** Fusiona dos paquetes Opus de 20ms en uno de 40ms. */
internal interface PacketMerger {
    /** @return longitud escrita en [out], o <= 0 si falló. */
    fun merge(first: ByteArray, second: ByteArray, out: ByteArray): Int
    fun release()
}

internal class RepacketizerMerger : PacketMerger {
    private val repacketizer = OpusRepacketizer.create()

    override fun merge(first: ByteArray, second: ByteArray, out: ByteArray): Int {
        repacketizer.init()
        repacketizer.cat(first)
        repacketizer.cat(second)
        return repacketizer.out(out)
    }

    override fun release() = repacketizer.destroy()
}

internal typealias PacketSink = (seq: Int, data: ByteArray, length: Int) -> Unit

internal class OpusFrameEncoder(
    private val codec: OpusCodec,
    private val merger: PacketMerger,
    initialSeq: Int = 0
) {
    private var seq = initialSeq and SEQ_MASK
    private var pending: ByteArray? = null
    private val mergeBuffer = ByteArray(MAX_OPUS_PACKET_SIZE)

    /** Codifica un frame de 20ms; cada dos frames entrega un paquete de 40ms a [sink]. */
    fun encode(pcm: ShortArray, sink: PacketSink) {
        // AudioRecord.read() puede devolver una lectura parcial; el encoder nativo siempre lee un
        // frame completo, así que un arreglo corto se completaría con memoria ajena.
        val frame = if (pcm.size == OpusCodec.FRAME_SIZE_SAMPLES) pcm else pcm.copyOf(OpusCodec.FRAME_SIZE_SAMPLES)
        val packet = codec.encode(frame) ?: return
        val first = pending
        if (first == null) {
            pending = packet
            return
        }
        pending = null
        val len = merger.merge(first, packet, mergeBuffer)
        if (len > 0) emit(mergeBuffer, len, sink)
    }

    /**
     * Entrega el frame suelto (20ms) que quedó sin pareja, sin fusionar, para no perder la cola de
     * audio; [OpusFrameDecoder] lo soporta igual.
     */
    fun flush(sink: PacketSink) {
        pending?.let { emit(it, it.size, sink) }
        pending = null
    }

    fun release() {
        try { merger.release() } catch (_: Exception) {}
    }

    private fun emit(data: ByteArray, length: Int, sink: PacketSink) {
        sink(seq, data, length)
        seq = PlaybackSequencing.next(seq)
    }

    private companion object {
        const val MAX_OPUS_PACKET_SIZE = 4000
        const val SEQ_MASK = 0xFFFF
    }
}

internal class OpusFrameDecoder(
    private val codec: OpusCodec,
    private val maxSamplesPerPacket: Int = OpusCodec.FRAME_SIZE_SAMPLES * 2
) {
    var packetsDecoded = 0; private set
    var concealedFrames = 0; private set
    var decoderResets = 0; private set
    var packetsDropped = 0; private set

    private var expectedSeq = -1

    /** Procesa un paquete recibido `[seq 2 bytes][opus]`; entrega a [onPcm] el audio a reproducir. */
    fun decode(frame: ByteArray, onPcm: (ShortArray) -> Unit) {
        if (frame.size <= HEADER_SIZE) return

        val seq = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)

        if (expectedSeq >= 0) {
            when (val action = PlaybackSequencing.classify(expectedSeq, seq)) {
                is GapAction.Conceal -> repeat(action.count) {
                    concealedFrames++
                    codec.decode(null)?.let(onPcm)
                }
                GapAction.ResetDecoder -> {
                    decoderResets++
                    codec.resetDecoder()
                }
                GapAction.Drop -> {
                    packetsDropped++
                    return
                }
                GapAction.InOrder -> Unit
            }
        }
        expectedSeq = PlaybackSequencing.next(seq)

        val packet = frame.copyOfRange(HEADER_SIZE, frame.size)
        codec.decodeAggregated(packet, maxSamplesPerPacket)?.let {
            packetsDecoded++
            onPcm(it)
        }
    }

    private companion object {
        const val HEADER_SIZE = 2
    }
}
