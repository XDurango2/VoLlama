package com.XDurango.VoLlama.Audio

import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.google.android.gms.nearby.connection.Payload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responsabilidad única: capturar audio del micrófono y reproducir audio entrante.
 * No conoce nada de UI, ViewModel ni estado de llamada.
 *
 * El caller (CallCoordinator) decide cuándo iniciar/detener y provee
 * los callbacks para saber si algo falló.
 */
@Singleton
class AudioEngine @Inject constructor(
    private val audioManager: AudioManager,
    private val nearbyService: NearbyConnectionService
) {

    // ── Configuración de audio ────────────────────────────────────────────────

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = 640
        const val PIPED_BUFFER_SIZE = 1024 * 32
        const val AUDIO_QUEUE_CAPACITY = 80 // ~3.2s de buffer a 640 bytes/frame
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    private val isCapturing = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var captureJob: Job? = null
    private var pipeWriterJob: Job? = null
    private var playbackJob: Job? = null

    private var audioQueue: ArrayBlockingQueue<ByteArray>? = null
    private var pipedOutputStream: PipedOutputStream? = null

    // ── Callbacks hacia el coordinador ────────────────────────────────────────

    var onCaptureError: (() -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null

    // ── Propiedades mutables desde el coordinador ─────────────────────────────

    /** Si es true, se envían frames de silencio en lugar del micrófono. */
    var isMuted: Boolean = false

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Abre el stream de audio hacia [endpointId] y comienza a capturar el micrófono.
     * Usa una ArrayBlockingQueue para desacoplar la captura del micrófono
     * de la escritura en red, evitando bloqueos cuando hay backpressure.
     *
     * @param scope CoroutineScope del caller (viewModelScope del coordinador)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(endpointId: String, scope: CoroutineScope) {
        if (!isCapturing.compareAndSet(false, true)) return

        val queue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)
        audioQueue = queue

        val outStream = PipedOutputStream()
        val inStream = PipedInputStream(outStream, PIPED_BUFFER_SIZE)
        pipedOutputStream = outStream

        // Entregar el stream a Nearby
        val payload = Payload.fromStream(inStream)
        nearbyService.connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener {
                stopCapture()
                onCaptureError?.invoke()
            }

        // Hilo 1: micrófono → queue  (nunca bloquea por la red)
        captureJob = scope.launch(Dispatchers.IO) {
            runAudioCapture(queue)
        }

        // Hilo 2: queue → PipedOutputStream (desacoplado del micrófono)
        pipeWriterJob = scope.launch(Dispatchers.IO) {
            runPipeWriter(queue, outStream)
        }
    }

    /**
     * Inicia la reproducción del stream entrante en el altavoz/auricular.
     *
     * @param inputStream Stream recibido desde Nearby (Payload.Type.STREAM)
     * @param scope CoroutineScope del caller
     */
    fun startPlayback(inputStream: InputStream, scope: CoroutineScope) {
        if (!isPlaying.compareAndSet(false, true)) {
            try { inputStream.close() } catch (_: Exception) {}
            return
        }

        playbackJob = scope.launch(Dispatchers.IO) {
            runPlayback(inputStream)
        }
    }

    fun stopCapture() {
        isCapturing.set(false)
        captureJob?.cancel()
        captureJob = null
        pipeWriterJob?.cancel()
        pipeWriterJob = null
        audioQueue?.clear()
        audioQueue = null
        try { pipedOutputStream?.close() } catch (_: Exception) {}
        pipedOutputStream = null
    }

    fun stopPlayback() {
        isPlaying.set(false)
        playbackJob?.cancel()
        playbackJob = null
    }

    fun stopAll() {
        stopCapture()
        stopPlayback()
    }

    fun setSpeakerOn(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    fun resetAudioMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    // ── Implementación privada ────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun runAudioCapture(queue: ArrayBlockingQueue<ByteArray>) =
        withContext(Dispatchers.IO) {
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT,
                    minBuf.coerceAtLeast(FRAME_SIZE * 4)
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    onCaptureError?.invoke()
                    return@withContext
                }

                audioRecord = record
                record.startRecording()

                val buffer = ByteArray(FRAME_SIZE)
                val silence = ByteArray(FRAME_SIZE) { 0 }

                while (isActive && isCapturing.get()) {
                    val read = record.read(buffer, 0, FRAME_SIZE)
                    if (read > 0) {
                        // offer() descarta el frame si la queue está llena
                        // (mejor perder un frame que bloquear el hilo de captura)
                        queue.offer(if (isMuted) silence.copyOf() else buffer.copyOf())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onCaptureError?.invoke()
            } finally {
                isCapturing.set(false)
                try { record?.stop(); record?.release() } catch (_: Exception) {}
                audioRecord = null
            }
        }

    private suspend fun runPipeWriter(
        queue: ArrayBlockingQueue<ByteArray>,
        outStream: PipedOutputStream
    ) = withContext(Dispatchers.IO) {
        try {
            while (isActive && isCapturing.get()) {
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    outStream.write(frame)
                    outStream.flush()
                } catch (e: IOException) {
                    // El pipe se cerró (llamada terminada), salimos limpiamente
                    break
                }
            }
        } finally {
            try { outStream.close() } catch (_: Exception) {}
        }
    }

    private suspend fun runPlayback(inputStream: InputStream) =
        withContext(Dispatchers.IO) {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf.coerceAtLeast(FRAME_SIZE * 8))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.play()

                val buffer = ByteArray(FRAME_SIZE)
                while (isActive && isPlaying.get()) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    if (read > 0) track.write(buffer, 0, read)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isPlaying.set(false)
                try { track?.stop(); track?.release(); inputStream.close() } catch (_: Exception) {}
                audioTrack = null
                onPlaybackEnded?.invoke()
            }
        }
}