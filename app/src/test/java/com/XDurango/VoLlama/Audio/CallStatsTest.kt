package com.XDurango.VoLlama.Audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStatsTest {
    private var now = 1_000_000_000L
    private val stats = CallStats { now }

    @Test
    fun `rtt promedia las muestras y oneWay es la mitad`() {
        repeat(2) { i ->
            val id = stats.beginPing()
            now += (40 + i * 20) * 1_000_000L // 40 ms y 60 ms
            stats.onPongReceived(id)
        }
        val s = stats.snapshot()
        assertEquals(50f, s.rttAvgMs, 0.01f)
        assertEquals(60f, s.rttLastMs, 0.01f)
        assertEquals(25f, s.oneWayMs, 0.01f)
    }

    @Test
    fun `un pong con id viejo se ignora`() {
        val old = stats.beginPing()
        stats.beginPing()
        now += 10_000_000L
        stats.onPongReceived(old)
        assertEquals(-1f, stats.snapshot().rttAvgMs, 0f)
    }

    @Test
    fun `la perdida sale de los frames ocultados sobre los esperados`() {
        repeat(9) { stats.onPacketReceived(100) }
        val s = stats.snapshot().copy(concealedFrames = 1)
        assertEquals(10f, s.lossPercent, 0.01f)
    }

    @Test
    fun `jitter y gap maximo reflejan paquetes tardios`() {
        stats.onPacketReceived(50)
        now += 40_000_000L; stats.onPacketReceived(50)
        now += 200_000_000L; stats.onPacketReceived(50)
        val s = stats.snapshot()
        assertEquals(200L, s.maxGapMs)
        assertTrue(s.jitterMs > 0f)
        assertEquals(3L, s.rxPackets)
        assertEquals(150L, s.rxBytes)
    }

    @Test
    fun `reset deja todo en cero`() {
        stats.onPacketSent(10); stats.onPacketReceived(10); stats.onQueueOverflow()
        stats.reset()
        val s = stats.snapshot()
        assertEquals(0L, s.txPackets + s.rxPackets + s.queueOverflows)
        assertEquals(-1L, s.msSinceLastRx)
    }
}
