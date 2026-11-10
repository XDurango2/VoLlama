package com.XDurango.VoLlama.Audio

import java.util.concurrent.atomic.AtomicLong

/** Foto de las estadísticas de la llamada para el menú de debug. */
data class CallDebugStats(
    val txPackets: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val rxBytes: Long = 0,
    val txKbps: Float = 0f,
    val rxKbps: Float = 0f,
    val decodedPackets: Long = 0,
    val concealedFrames: Long = 0,
    val decoderResets: Long = 0,
    val latePacketsDropped: Long = 0,
    val queueOverflows: Long = 0,
    val jitterMs: Float = 0f,
    val maxGapMs: Long = 0,
    val msSinceLastRx: Long = -1,
    /** Promedio del RTT de las últimas [CallStats.RTT_WINDOW] muestras; -1 si aún no hay. */
    val rttAvgMs: Float = -1f,
    val rttLastMs: Float = -1f
) {
    /** Latencia de ida aproximada (RTT/2): sin relojes sincronizados no se puede medir un solo sentido. */
    val oneWayMs: Float get() = if (rttAvgMs < 0f) -1f else rttAvgMs / 2f

    /** Porcentaje de paquetes perdidos (ocultados con PLC) sobre los esperados. */
    val lossPercent: Float
        get() {
            val expected = rxPackets + concealedFrames
            return if (expected == 0L) 0f else concealedFrames * 100f / expected
        }
}

/**
 * Contadores thread-safe de la llamada: los escriben el hilo de captura, el de Nearby y el de
 * reproducción, y los lee la UI una vez por segundo vía [snapshot].
 */
class CallStats(private val clock: () -> Long = System::nanoTime) {
    private val txPackets = AtomicLong()
    private val txBytes = AtomicLong()
    private val rxPackets = AtomicLong()
    private val rxBytes = AtomicLong()
    private val queueOverflows = AtomicLong()

    @Volatile private var decoded = 0L
    @Volatile private var concealed = 0L
    @Volatile private var resets = 0L
    @Volatile private var dropped = 0L

    // Solo los escribe el hilo de Nearby (onPayloadReceived).
    @Volatile private var lastRxNanos = 0L
    @Volatile private var jitterMs = 0f
    @Volatile private var maxGapMs = 0L

    // RTT: beginPing() corre en el hilo del timer y onPongReceived() en el de Nearby.
    private val rttLock = Any()
    private val rttSamples = ArrayDeque<Float>()
    private var pingId = 0
    private var pingSentAt = 0L
    @Volatile private var rttAvgMs = -1f
    @Volatile private var rttLastMs = -1f

    /** Registra el envío de un ping y devuelve su id (16 bits). Solo hay un ping pendiente a la vez. */
    fun beginPing(): Int = synchronized(rttLock) {
        pingId = (pingId + 1) and 0xFFFF
        pingSentAt = clock()
        pingId
    }

    /** Un pong con id viejo (de un ping ya reemplazado) se ignora. */
    fun onPongReceived(id: Int) {
        val now = clock()
        synchronized(rttLock) {
            if (id != pingId || pingSentAt == 0L) return
            val rtt = (now - pingSentAt) / 1_000_000f
            pingSentAt = 0L
            rttSamples.addLast(rtt)
            if (rttSamples.size > RTT_WINDOW) rttSamples.removeFirst()
            rttLastMs = rtt
            rttAvgMs = rttSamples.average().toFloat()
        }
    }

    fun onPacketSent(bytes: Int) {
        txPackets.incrementAndGet()
        txBytes.addAndGet(bytes.toLong())
    }

    fun onPacketReceived(bytes: Int) {
        val now = clock()
        val last = lastRxNanos
        if (last != 0L) {
            val gapMs = (now - last) / 1_000_000
            // Estimador RFC 3550: el desvío respecto al ritmo esperado, suavizado con 1/16.
            jitterMs += (kotlin.math.abs(gapMs - EXPECTED_GAP_MS) - jitterMs) / 16f
            if (gapMs > maxGapMs) maxGapMs = gapMs
        }
        lastRxNanos = now
        rxPackets.incrementAndGet()
        rxBytes.addAndGet(bytes.toLong())
    }

    fun onQueueOverflow() {
        queueOverflows.incrementAndGet()
    }

    internal fun onDecoderStats(decoder: OpusFrameDecoder) {
        decoded = decoder.packetsDecoded.toLong()
        concealed = decoder.concealedFrames.toLong()
        resets = decoder.decoderResets.toLong()
        dropped = decoder.packetsDropped.toLong()
    }

    /** [txKbps]/[rxKbps] los calcula quien muestrea (necesita el delta entre dos fotos). */
    fun snapshot(txKbps: Float = 0f, rxKbps: Float = 0f): CallDebugStats {
        val last = lastRxNanos
        return CallDebugStats(
            txPackets = txPackets.get(), txBytes = txBytes.get(),
            rxPackets = rxPackets.get(), rxBytes = rxBytes.get(),
            txKbps = txKbps, rxKbps = rxKbps,
            decodedPackets = decoded, concealedFrames = concealed,
            decoderResets = resets, latePacketsDropped = dropped,
            queueOverflows = queueOverflows.get(),
            jitterMs = jitterMs, maxGapMs = maxGapMs,
            msSinceLastRx = if (last == 0L) -1 else (clock() - last) / 1_000_000,
            rttAvgMs = rttAvgMs, rttLastMs = rttLastMs
        )
    }

    fun reset() {
        txPackets.set(0); txBytes.set(0); rxPackets.set(0); rxBytes.set(0); queueOverflows.set(0)
        decoded = 0; concealed = 0; resets = 0; dropped = 0
        lastRxNanos = 0; jitterMs = 0f; maxGapMs = 0
        synchronized(rttLock) { rttSamples.clear(); pingSentAt = 0L }
        rttAvgMs = -1f; rttLastMs = -1f
    }

    companion object {
        const val RTT_WINDOW = 15

        /** Cada paquete fusionado lleva 40ms de audio. */
        private const val EXPECTED_GAP_MS = 40L
    }
}
