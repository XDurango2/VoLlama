package com.XDurango.VoLlama.Audio

import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import com.steegler.koopus.OpusRepacketizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responsabilidad única: capturar audio del micrófono, codificarlo/decodificarlo con Opus
 * (vía [OpusCodec]) y reproducir el audio entrante. No conoce nada de UI ni de ViewModel.
 *
 * El audio viaja como paquetes Opus discretos (`Payload.BYTES` de Nearby, ver
 * [NearbyConnectionService.sendAudioFrame]) en vez de un stream continuo de PCM crudo:
 * cada paquete lleva un header de 2 bytes con un número de secuencia (para detectar
 * pérdida/reordering) seguido del paquete Opus en sí. Dos frames de 20ms se fusionan con
 * [OpusRepacketizer] en un solo paquete de 40ms antes de enviarse, para reducir a la mitad
 * la tasa de payloads/seg sobre Nearby.
 *
 * El caller (CallManager) decide cuándo iniciar/detener y provee los callbacks para saber
 * si algo falló.
 */
@Singleton
class AudioEngine @Inject constructor(
    private val audioManager: AudioManager,
    private val nearbyService: NearbyConnectionService,
    private val opusCodec: OpusCodec
) {

    // ── Configuración de audio ────────────────────────────────────────────────

    companion object {
        const val SAMPLE_RATE = OpusCodec.SAMPLE_RATE
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = OpusCodec.FRAME_SIZE_SAMPLES * 2 // 640 bytes = 320 samples = 20ms
        private const val MAX_OPUS_PACKET_SIZE = 4000 // tamaño máximo de un paquete Opus
        private const val FRAMES_PER_MERGED_PACKET = 2 // 2×20ms → paquete de 40ms
        private const val INCOMING_QUEUE_CAPACITY = 64
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    private val isCapturing = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var incomingFrames: Channel<ByteArray>? = null

    // ── Callbacks hacia el coordinador ────────────────────────────────────────

    var onCaptureError: (() -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null

    // ── Propiedades mutables desde el coordinador ─────────────────────────────

    /** Si es true, se envían frames de silencio en lugar del micrófono. */
    var isMuted: Boolean = false

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Comienza a capturar el micrófono, codificar a Opus y enviar los paquetes resultantes
     * a [endpointId] vía [NearbyConnectionService.sendAudioFrame].
     *
     * @param scope CoroutineScope del caller (viewModelScope del coordinador)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(endpointId: String, scope: CoroutineScope) {
        if (!isCapturing.compareAndSet(false, true)) return
        opusCodec.open()
        captureJob = scope.launch(Dispatchers.IO) {
            runAudioCapture(endpointId)
        }
    }

    /**
     * Inicia el consumidor de audio entrante: decodifica los paquetes Opus recibidos
     * (alimentados vía [feedIncomingAudioFrame]) y los reproduce en el altavoz/auricular.
     *
     * @param scope CoroutineScope del caller
     */
    fun startPlayback(scope: CoroutineScope) {
        if (!isPlaying.compareAndSet(false, true)) return
        opusCodec.open()
        val channel = Channel<ByteArray>(capacity = INCOMING_QUEUE_CAPACITY)
        incomingFrames = channel
        playbackJob = scope.launch(Dispatchers.IO) {
            runPlayback(channel)
        }
    }

    /**
     * Alimenta un paquete de audio entrante (seq de 2 bytes + paquete Opus) recibido de Nearby.
     * Se descarta silenciosamente si la reproducción no está activa.
     */
    fun feedIncomingAudioFrame(frame: ByteArray) {
        incomingFrames?.trySend(frame)
    }

    fun stopCapture() {
        isCapturing.set(false)
        captureJob?.cancel()
        captureJob = null
    }

    fun stopPlayback() {
        isPlaying.set(false)
        playbackJob?.cancel()
        playbackJob = null
        incomingFrames?.close()
        incomingFrames = null
    }

    /** Detiene captura y reproducción, y libera el codec Opus (fin de la llamada). */
    fun stopAll() {
        stopCapture()
        stopPlayback()
        opusCodec.close()
    }

    fun startAudioMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    fun setSpeakerOn(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    fun resetAudioMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    // ── Implementación privada ────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun runAudioCapture(endpointId: String) =
        withContext(Dispatchers.IO) {
            var record: AudioRecord? = null
            var repacketizer: OpusRepacketizer? = null
            var pendingPacket: ByteArray? = null
            var seq = 0

            fun sendPacket(packet: ByteArray) {
                val header = byteArrayOf((seq shr 8).toByte(), seq.toByte())
                seq = (seq + 1) and 0xFFFF
                nearbyService.sendAudioFrame(endpointId, header + packet)
            }

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

                if (NoiseSuppressor.isAvailable())
                    noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
                if (AcousticEchoCanceler.isAvailable())
                    echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)

                repacketizer = OpusRepacketizer.create()

                val buffer = ByteArray(FRAME_SIZE)
                val silencePcm = ShortArray(OpusCodec.FRAME_SIZE_SAMPLES)
                val mergeBuffer = ByteArray(MAX_OPUS_PACKET_SIZE)

                while (isActive && isCapturing.get()) {
                    val read = record.read(buffer, 0, FRAME_SIZE)
                    if (read <= 0) continue

                    val pcm = if (isMuted) silencePcm else bytesToShorts(buffer, read)
                    val packet = opusCodec.encode(pcm) ?: continue

                    val first = pendingPacket
                    if (first == null) {
                        pendingPacket = packet
                        continue
                    }
                    pendingPacket = null

                    repacketizer.init()
                    repacketizer.cat(first)
                    repacketizer.cat(packet)
                    val mergedLen = repacketizer.out(mergeBuffer)
                    if (mergedLen > 0) sendPacket(mergeBuffer.copyOf(mergedLen))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onCaptureError?.invoke()
            } finally {
                isCapturing.set(false)
                // Frame suelto (20ms) que quedó sin pareja al detener la captura — se manda
                // sin fusionar para no perder la cola de audio; decodeAggregated() lo soporta igual.
                pendingPacket?.let { sendPacket(it) }
                try { record?.stop(); record?.release() } catch (_: Exception) {}
                audioRecord = null
                noiseSuppressor?.release(); noiseSuppressor = null
                echoCanceler?.release(); echoCanceler = null
                try { repacketizer?.destroy() } catch (_: Exception) {}
            }
        }

    private suspend fun runPlayback(channel: Channel<ByteArray>) =
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

                val maxSamples = OpusCodec.FRAME_SIZE_SAMPLES * FRAMES_PER_MERGED_PACKET
                var expectedSeq = -1

                while (isActive && isPlaying.get()) {
                    val frame = channel.receiveCatching().getOrNull() ?: break
                    if (frame.size <= 2) continue

                    val seq = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
                    val packet = frame.copyOfRange(2, frame.size)

                    if (expectedSeq >= 0) {
                        val gap = (seq - expectedSeq) and 0xFFFF
                        when {
                            gap in 1..2 -> repeat(gap) {
                                opusCodec.decode(null)?.let { pcm -> track.write(pcm, 0, pcm.size) }
                            }
                            gap > 2 -> opusCodec.resetDecoder()
                        }
                    }
                    expectedSeq = (seq + 1) and 0xFFFF

                    val pcm = opusCodec.decodeAggregated(packet, maxSamples)
                    if (pcm != null) track.write(pcm, 0, pcm.size)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isPlaying.set(false)
                try { track?.stop(); track?.release() } catch (_: Exception) {}
                audioTrack = null
                onPlaybackEnded?.invoke()
            }
        }

    private fun bytesToShorts(bytes: ByteArray, length: Int): ShortArray {
        val shorts = ShortArray(length / 2)
        ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }
}
