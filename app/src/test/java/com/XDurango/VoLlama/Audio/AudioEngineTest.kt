package com.XDurango.VoLlama.Audio

import android.media.AudioManager
import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Capa de audio, solo lo que se puede verificar sin hardware: modo de audio, ciclo de vida del codec
 * y manejo de fallo del micrófono. El pipeline real (AudioRecord/AudioTrack) lo cubren las pruebas
 * instrumentadas; la política de secuencia, PlaybackSequencingTest.
 *
 * En JVM, AudioRecord es un stub con state=0 (no inicializado): sirve para simular "micrófono ocupado".
 */
class AudioEngineTest {

    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val nearby = mockk<NearbyConnectionService>(relaxed = true)
    private val codec = mockk<OpusCodec>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var engine: AudioEngine

    @Before fun setUp() { engine = AudioEngine(audioManager, nearby, codec) }

    @After fun tearDown() { scope.cancel() }

    @Test fun `startAudioMode usa MODE_IN_COMMUNICATION`() {
        engine.startAudioMode()
        verify { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
    }

    @Test fun `setSpeakerOn se refleja en el AudioManager`() {
        engine.setSpeakerOn(true)
        verify { audioManager.isSpeakerphoneOn = true }
        engine.setSpeakerOn(false)
        verify { audioManager.isSpeakerphoneOn = false }
    }

    @Test fun `resetAudioMode vuelve a modo normal y apaga el altavoz`() {
        engine.resetAudioMode()
        verify { audioManager.mode = AudioManager.MODE_NORMAL }
        verify { audioManager.isSpeakerphoneOn = false }
    }

    @Test fun `stopAll cierra el codec`() {
        engine.stopAll()
        verify(exactly = 1) { codec.close() }
    }

    @Test fun `stopCapture y stopPlayback sin haber iniciado no fallan ni cierran el codec`() {
        engine.stopCapture()
        engine.stopPlayback()
        verify(exactly = 0) { codec.close() }
    }

    @Test fun `feedIncomingAudioFrame sin reproduccion activa se descarta sin error`() {
        engine.feedIncomingAudioFrame(byteArrayOf(0, 1, 2, 3))
    }

    @Test fun `isMuted se puede cambiar y leer`() {
        engine.isMuted = true
        assertTrue(engine.isMuted)
        engine.isMuted = false
        assertEquals(false, engine.isMuted)
    }

    @Test fun `si el microfono no inicializa se notifica onCaptureError y se puede reintentar`() {
        val errors = AtomicInteger()
        val first = CountDownLatch(1)
        engine.onCaptureError = { errors.incrementAndGet(); first.countDown() }

        engine.startCapture("ep", scope)
        assertTrue("onCaptureError no se invocó", first.await(5, TimeUnit.SECONDS))

        // El flag isCapturing debe liberarse: un segundo intento tiene que volver a ejecutarse.
        val second = CountDownLatch(1)
        engine.onCaptureError = { errors.incrementAndGet(); second.countDown() }
        engine.startCapture("ep", scope)
        assertTrue("el segundo intento fue ignorado: isCapturing quedó atascado", second.await(5, TimeUnit.SECONDS))
        assertEquals(2, errors.get())
        verify(exactly = 2) { codec.open() }
    }

    @Test fun `startCapture mientras ya captura se ignora`() {
        every { codec.open() } answers { Thread.sleep(300) } // mantiene isCapturing=true un rato
        engine.startCapture("ep", scope)
        engine.startCapture("ep", scope)

        verify(exactly = 1) { codec.open() }
    }
}
