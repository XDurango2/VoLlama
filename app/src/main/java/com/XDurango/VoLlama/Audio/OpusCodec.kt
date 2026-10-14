package com.XDurango.VoLlama.Audio

import com.steegler.koopus.OpusDecoder
import com.steegler.koopus.OpusEncoder
import com.steegler.koopus.OpusProfiles
import com.steegler.koopus.OpusTuning
import com.steegler.koopus.apply
import javax.inject.Inject
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Encapsula el ciclo de vida de los singletons `OpusEncoder`/`OpusDecoder` de KoOpus,
 * para que AudioEngine no dependa directamente de la librería nativa.
 *
 * OpusEncoder/OpusDecoder son `object` de Kotlin (un único handle nativo global por proceso):
 * solo puede haber una llamada activa a la vez, lo cual encaja con el modelo 1:1 de VoLlama.
 */
@Singleton
class OpusCodec @Inject constructor() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val FRAME_SIZE_SAMPLES = 320 // 20ms @ 16kHz, coincide con AudioEngine.FRAME_SIZE (640 bytes)
        private const val OPUS_APPLICATION_VOIP = 2048
    }

    private var isOpen = false

    // encode/decode corren en hilos distintos (captura / reproducción) y pueden ejecutarse a la vez
    // (read lock); open/close destruyen los handles nativos y deben esperar a que terminen (write lock).
    // Sin esto, stopAll() podía destruir el encoder mientras el hilo de captura lo estaba usando.
    private val lock = ReentrantReadWriteLock()

    /** Crea encoder y decoder, y aplica el perfil de tuning. Debe llamarse al iniciar una llamada. */
    fun open() = lock.write {
        if (isOpen) return@write

        check(OpusEncoder.create(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_VOIP)) {
            "No se pudo crear OpusEncoder"
        }
        check(OpusDecoder.create(SAMPLE_RATE, CHANNELS)) {
            "No se pudo crear OpusDecoder"
        }
        isOpen = true

        // El tuning es best-effort: si el CTL nativo falla (bug conocido en KoOpus v0.1.2,
        // OpusTuning busca el campo `nativeHandle` en su propia clase en vez de en `encoder`),
        // seguimos con los defaults de libopus en vez de romper la llamada completa.
        try {
            OpusTuning(OpusEncoder).apply(OpusProfiles.Default)
        } catch (e: Throwable) { // incluye UnsatisfiedLinkError (es Error, no Exception)
            e.printStackTrace()
        }
    }

    /** Codifica un frame de [FRAME_SIZE_SAMPLES] samples PCM. Devuelve null si falló o no está abierto. */
    fun encode(pcm: ShortArray): ByteArray? = lock.read {
        if (!isOpen) return@read null
        OpusEncoder.encode(pcm, FRAME_SIZE_SAMPLES)
    }

    /**
     * Decodifica un paquete Opus. Pasar `null` (o un [ByteArray] vacío) dispara PLC
     * (packet-loss concealment) nativo para un frame perdido.
     */
    fun decode(packet: ByteArray?): ShortArray? = lock.read {
        if (!isOpen) return@read null
        val data = packet ?: ByteArray(0)
        OpusDecoder.decode(data, data.size, FRAME_SIZE_SAMPLES)
    }

    /**
     * Decodifica un paquete que agrega varios frames Opus (p. ej. 2×20ms fusionados con
     * [com.steegler.koopus.OpusRepacketizer]). [maxSamplesTotal] debe cubrir la suma de
     * samples de todos los frames agregados.
     */
    fun decodeAggregated(packet: ByteArray, maxSamplesTotal: Int): ShortArray? = lock.read {
        if (!isOpen) return@read null
        val out = ShortArray(maxSamplesTotal)
        val decoded = OpusDecoder.decodeAggregated(packet, packet.size, FRAME_SIZE_SAMPLES, out)
        if (decoded > 0) out.copyOf(decoded) else null
    }

    /** Reinicia el estado interno del decoder tras un gap grande de paquetes perdidos/reordenados. */
    fun resetDecoder() = lock.read {
        if (isOpen) OpusDecoder.reset()
    }

    /** Libera los handles nativos. Debe llamarse siempre al terminar la llamada. */
    fun close() = lock.write {
        if (!isOpen) return@write
        OpusEncoder.destroy()
        OpusDecoder.destroy()
        isOpen = false
    }
}
