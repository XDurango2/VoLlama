package com.XDurango.VoLlama.CallMode

import com.XDurango.VoLlama.Audio.AudioEngine
import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import com.XDurango.VoLlama.Nearby.NearbyConnectionService.ConnectionSignalsTypes
import com.google.android.gms.nearby.connection.ConnectionInfo
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Capa de llamadas: ciclo de vida, señalización y limpieza al colgar.
 *
 * Los tests marcados con "BUG:" describen el comportamiento correcto de síntomas reportados en
 * uso real; fallan mientras el bug exista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerTest {

    private val testScope = TestScope()
    private val mainDispatcher = StandardTestDispatcher(testScope.testScheduler)
    // Dispatcher distinto pero con el mismo scheduler: withContext(io) cede el hilo como en producción.
    private val ioDispatcher = StandardTestDispatcher(testScope.testScheduler)

    private val connected = MutableStateFlow<Set<Pair<String, ConnectionInfo>>>(emptySet())
    private val nearby = mockk<NearbyConnectionService>(relaxed = true) {
        every { connectedEndpoints } returns connected
    }
    private val audio = mockk<AudioEngine>(relaxed = true)

    private lateinit var manager: CallManager

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
        manager = CallManager(nearby, audio).apply { this.ioDispatcher = this@CallManagerTest.ioDispatcher }
        manager.initialize(ENDPOINT, "Pixel")
        connected.value = setOf(ENDPOINT to mockk<ConnectionInfo>())
        testScope.testScheduler.runCurrent()
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // El timer de la llamada (delay(1000) en loop) nunca queda "idle": se avanza un tiempo acotado.
    private fun idle() = testScope.testScheduler.run {
        advanceTimeBy(100)
        runCurrent()
    }

    // ── Llamada saliente ──────────────────────────────────────────────────────

    @Test
    fun `StartCall envia INCOMING_CALL y pasa a CONNECTING`() {
        manager.onCallEvent(CallEvent.StartCall)
        idle()

        assertEquals(CallState.CONNECTING, manager.callState.value)
        verify(exactly = 1) { nearby.sendCallSignal(ENDPOINT, ConnectionSignalsTypes.INCOMING_CALL) }
        assertTrue(manager.callUiState.value.isInCall)
    }

    @Test
    fun `StartCall repetido no envia la senal dos veces`() {
        manager.onCallEvent(CallEvent.StartCall)
        manager.onCallEvent(CallEvent.StartCall)
        idle()

        verify(exactly = 1) { nearby.sendCallSignal(ENDPOINT, ConnectionSignalsTypes.INCOMING_CALL) }
    }

    @Test
    fun `al aceptar el remoto arranca audio y pasa a IN_CALL`() {
        manager.onCallEvent(CallEvent.StartCall)
        idle()
        manager.onRemoteCallAccepted()
        idle()

        assertEquals(CallState.IN_CALL, manager.callState.value)
        verify { audio.startAudioMode() }
        verify { audio.startCapture(ENDPOINT, any()) }
        verify { audio.startPlayback(any()) }
    }

    @Test
    fun `rechazo del remoto termina con REJECTED sin tocar el audio`() {
        manager.onCallEvent(CallEvent.StartCall)
        idle()
        manager.onRemoteCallRejected()

        assertEquals(CallState.ENDED, manager.callState.value)
        assertEquals(CallEndReason.REJECTED, manager.callUiState.value.endReason)
        verify(exactly = 0) { audio.startCapture(any(), any()) }
    }

    // ── Llamada entrante ──────────────────────────────────────────────────────

    @Test
    fun `AnswerCall arranca captura y reproduccion`() {
        manager.onCallEvent(CallEvent.AnswerCall)
        idle()

        assertEquals(CallState.IN_CALL, manager.callState.value)
        verify { audio.startCapture(ENDPOINT, any()) }
        verify { audio.startPlayback(any()) }
    }

    // ── Colgar ────────────────────────────────────────────────────────────────

    @Test
    fun `EndCall avisa al remoto, detiene el audio y termina`() {
        manager.onCallEvent(CallEvent.AnswerCall)
        idle()
        manager.onCallEvent(CallEvent.EndCall)
        idle()

        verify { nearby.sendCallSignal(ENDPOINT, ConnectionSignalsTypes.CALL_ENDED) }
        verify { audio.stopAll() }
        assertEquals(CallState.ENDED, manager.callState.value)
        assertFalse(manager.callUiState.value.isInCall)
    }

    @Test
    fun `colgar el remoto no le reenvia CALL_ENDED`() {
        manager.onCallEvent(CallEvent.AnswerCall)
        idle()
        manager.onRemoteCallEnded()
        idle()

        assertEquals(CallState.ENDED, manager.callState.value)
        verify(exactly = 0) { nearby.sendCallSignal(any(), ConnectionSignalsTypes.CALL_ENDED) }
    }

    @Test
    fun `perder la conexion con el endpoint termina la llamada activa`() {
        manager.onCallEvent(CallEvent.AnswerCall)
        idle()
        connected.value = emptySet()
        idle()

        assertEquals(CallState.ENDED, manager.callState.value)
        verify { audio.stopAll() }
    }

    @Test
    fun `error de microfono termina la llamada`() {
        val callback = slot<() -> Unit>()
        verify { audio.onCaptureError = capture(callback) }
        manager.onCallEvent(CallEvent.AnswerCall)
        idle()

        callback.captured.invoke()
        idle()

        assertEquals(CallState.ENDED, manager.callState.value)
    }

    // ── BUG: silenciar / altavoz y colgar de inmediato ────────────────────────

    @Test
    fun `BUG colgar limpia mute y altavoz para que la siguiente llamada empiece limpia`() {
        manager.onCallEvent(CallEvent.AnswerCall)
        idle()
        manager.onCallEvent(CallEvent.ToggleMute)
        manager.onCallEvent(CallEvent.ToggleSpeaker)
        manager.onCallEvent(CallEvent.EndCall)
        idle()

        val ui = manager.callUiState.value
        assertFalse("mute debe limpiarse al colgar", ui.isMuted)
        assertFalse("altavoz debe limpiarse al colgar", ui.isSpeakerOn)
        // AudioEngine es @Singleton: si no se restablece, la siguiente llamada sale muda/en altavoz.
        verify { audio.isMuted = false }
        verify { audio.resetAudioMode() }
    }

    @Test
    fun `BUG CALL_ACCEPTED tardio despues de colgar no reabre la llamada`() {
        manager.onCallEvent(CallEvent.StartCall)
        idle()
        manager.onCallEvent(CallEvent.EndCall)
        idle()
        clearMocks(audio, answers = false)

        manager.onRemoteCallAccepted() // la senal del remoto cruzo el colgado
        idle()

        assertEquals(CallState.ENDED, manager.callState.value)
        verify(exactly = 0) { audio.startAudioMode() }
        verify(exactly = 0) { audio.startCapture(any(), any()) }
        verify(exactly = 0) { audio.startPlayback(any()) }
    }

    @Test
    fun `BUG toggles de mute y altavoz justo antes de colgar no dejan audio activo`() {
        manager.onCallEvent(CallEvent.StartCall)
        idle()
        manager.onRemoteCallAccepted()
        manager.onCallEvent(CallEvent.ToggleMute)
        manager.onCallEvent(CallEvent.ToggleSpeaker)
        manager.onCallEvent(CallEvent.EndCall)
        idle()

        assertEquals(CallState.ENDED, manager.callState.value)
        assertFalse(manager.callUiState.value.isInCall)
        // El ultimo cambio sobre el audio debe ser el stop, no un arranque tardio.
        verify(ordering = io.mockk.Ordering.ORDERED) {
            audio.startPlayback(any())
            audio.stopAll()
        }
    }

    // ── Audio entrante ────────────────────────────────────────────────────────

    @Test
    fun `frames que llegan antes de CALL_ACCEPTED se guardan y se entregan al aceptar`() {
        manager.onCallEvent(CallEvent.StartCall)
        idle()
        repeat(3) { manager.onIncomingAudioFrame(byteArrayOf(it.toByte())) }
        verify(exactly = 0) { audio.feedIncomingAudioFrame(any()) }

        manager.onRemoteCallAccepted()
        idle()

        verify(exactly = 3) { audio.feedIncomingAudioFrame(any()) }
    }

    @Test
    fun `el buffer previo a la aceptacion conserva solo los 10 frames mas recientes`() {
        manager.onCallEvent(CallEvent.StartCall)
        idle()
        repeat(15) { manager.onIncomingAudioFrame(byteArrayOf(it.toByte())) }
        manager.onRemoteCallAccepted()
        idle()

        val frames = mutableListOf<ByteArray>()
        verify(exactly = 10) { audio.feedIncomingAudioFrame(capture(frames)) }
        assertEquals(5, frames.first()[0].toInt())
        assertEquals(14, frames.last()[0].toInt())
    }

    @Test
    fun `frames despues de terminar la llamada se descartan`() {
        manager.onCallEvent(CallEvent.AnswerCall)
        idle()
        manager.onCallEvent(CallEvent.EndCall)
        idle()
        clearMocks(audio, answers = false)

        manager.onIncomingAudioFrame(byteArrayOf(1))

        verify(exactly = 0) { audio.feedIncomingAudioFrame(any()) }
    }

    // ── Mute / altavoz durante la llamada ─────────────────────────────────────

    @Test
    fun `ToggleMute y ToggleSpeaker se reflejan en UI y motor`() {
        manager.onCallEvent(CallEvent.ToggleMute)
        manager.onCallEvent(CallEvent.ToggleSpeaker)

        assertTrue(manager.callUiState.value.isMuted)
        assertTrue(manager.callUiState.value.isSpeakerOn)
        verify { audio.isMuted = true }
        verify { audio.setSpeakerOn(true) }

        manager.onCallEvent(CallEvent.ToggleMute)
        verify { audio.isMuted = false }
    }

    // ── Walkie-talkie ─────────────────────────────────────────────────────────

    @Test
    fun `walkie-talkie entrante no captura hasta presionar PTT`() {
        manager.startWalkieTalkieMode(isIncoming = true)
        idle()
        verify(exactly = 0) { audio.startCapture(any(), any()) }

        manager.onWalkieTalkieEvent(WalkieTalkieEvent.PressToTalk)
        idle()

        verify(exactly = 1) { audio.startCapture(ENDPOINT, any()) }
        assertTrue(manager.walkieTalkieUiState.value.isTransmitting)
    }

    @Test
    fun `soltar PTT detiene la captura`() {
        manager.startWalkieTalkieMode(isIncoming = true)
        manager.onWalkieTalkieEvent(WalkieTalkieEvent.PressToTalk)
        idle()
        manager.onWalkieTalkieEvent(WalkieTalkieEvent.ReleaseToTalk)

        verify { audio.stopCapture() }
        assertFalse(manager.walkieTalkieUiState.value.isTransmitting)
    }

    @Test
    fun `PTT sin llamada activa se ignora`() {
        manager.onWalkieTalkieEvent(WalkieTalkieEvent.PressToTalk)
        idle()

        verify(exactly = 0) { audio.startCapture(any(), any()) }
    }

    private companion object {
        const val ENDPOINT = "endpoint-1"
    }
}
