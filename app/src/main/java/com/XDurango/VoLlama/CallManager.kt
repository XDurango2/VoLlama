package com.XDurango.VoLlama

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.PowerManager
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.nearby.connection.Payload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

@HiltViewModel
class CallManager @Inject constructor(
    private val audioManager: AudioManager,
    private val nearbyService: NearbyConnectionService
) : ViewModel() {

    private var endpointId: String = ""
    private var endpointName: String = ""
    private val proximityLock: PowerManager.WakeLock? = null

    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
   @Volatile private var isRecording = false
    private var isPlaying = false
    private var streamOutputs = mutableMapOf<String, OutputStream>()

    // UI States
    private val _callUiState = MutableStateFlow(CallUiState())
    val callUiState: StateFlow<CallUiState> = _callUiState.asStateFlow()

    private val _walkieTalkieUiState = MutableStateFlow(WalkieTalkieUiState())
    val walkieTalkieUiState: StateFlow<WalkieTalkieUiState> = _walkieTalkieUiState.asStateFlow()

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()
    private var audioJob: Job? = null
    private var audioCaptureJob: Job? = null


    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE: Int by lazy {
            val size = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            if (size <= 0) SAMPLE_RATE * 2 else size
        }
    }

    enum class CallState {
        IDLE,
        CONNECTING,
        IN_CALL,
        ENDED
    }

    // Inicializar con los datos del Intent
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun initialize(endpointId: String, endpointName: String) {
        this.endpointId = endpointId
        this.endpointName = endpointName

        // Observar el estado de la conexión
        nearbyService.connectedEndpoints.observeForever { endpoints ->
            val isConnected = endpoints.any { it.first == endpointId }
            if (!isConnected && _callState.value == CallState.IN_CALL) {
                // La conexión se perdió durante la llamada

                onCallEvent(CallEvent.EndCall)
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun onCallEvent(event: CallEvent) {
        when (event) {

            CallEvent.ToggleMute -> {
                viewModelScope.launch {
                    val newMuted = !_callUiState.value.isMuted
                    _callUiState.update { it.copy(isMuted = newMuted) }

                    withContext(Dispatchers.IO) {
                        if (newMuted) {
                            pauseMicrophone()
                        } else {
                            streamOutputs[endpointId]?.let { resumeMicrophone(it) }
                        }
                    }
                }
            }


            CallEvent.ToggleSpeaker -> {
                viewModelScope.launch {
                    val newSpeaker = !_callUiState.value.isSpeakerOn
                    _callUiState.update { it.copy(isSpeakerOn = newSpeaker) }

                    withContext(Dispatchers.IO) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = newSpeaker
                    }
                }
            }

            CallEvent.StartCall -> {
                if (_callState.value != CallState.IDLE) return

                viewModelScope.launch {
                    _callState.value = CallState.CONNECTING

                    audioJob = launch(Dispatchers.IO) {
                        startAudioStream(endpointId)
                    }

                    _callState.value = CallState.IN_CALL
                    _callUiState.update { it.copy(isInCall = true) }
                }
            }

            CallEvent.EndCall -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        stopAudioStream()
                        nearbyService.disconnectFrom(endpointId)
                    }

                    _callState.value = CallState.ENDED
                    _callUiState.update { it.copy(isInCall = false) }
                }
            }

        }
    }



    // Manejar eventos de walkie-talkie
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun onWalkieTalkieEvent(event: WalkieTalkieEvent) {
        viewModelScope.launch (Dispatchers.IO){
        when (event) {
            WalkieTalkieEvent.PressToTalk -> {
                _walkieTalkieUiState.update { it.copy(isTransmitting = true) }
                // Iniciar transmisión de audio
                streamOutputs[endpointId]?.let { outputStream ->
                    if (!isRecording) {
                        startAudioCapture(outputStream)
                    }
                }
            }

            WalkieTalkieEvent.ReleaseToTalk -> {
                _walkieTalkieUiState.update { it.copy(isTransmitting = false) }
                // Detener transmisión de audio
                stopAudioCapture()
            }
        }
    }
}

    // ==================== AUDIO STREAMING ====================

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startAudioStream(endpointId: String) = withContext(Dispatchers.IO) {

            // Crear stream de salida
            val pipedOutputStream = PipedOutputStream()
            val pipedInputStream = PipedInputStream(pipedOutputStream)

            // Guardar el stream
            streamOutputs[endpointId] = pipedOutputStream

            // Enviar el stream como Payload a través del NearbyService
            val payload = Payload.fromStream(pipedInputStream)
            nearbyService.connectionsClient.sendPayload(endpointId, payload)

            // Iniciar captura de audio
            startAudioCapture(pipedOutputStream)

           }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioCapture(outputStream: OutputStream) {

        if (isRecording) return

        audioCaptureJob?.cancel()

        audioCaptureJob = viewModelScope.launch(Dispatchers.IO) {

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE * 4
                )

                audioRecord?.startRecording()
                isRecording = true

                val buffer = ByteArray(BUFFER_SIZE)

                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        outputStream.write(buffer, 0, readBytes)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isRecording = false
                try { outputStream.close() } catch (_: Exception) {}
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
            }
        }
    }


    private fun stopAudioCapture() {
        isRecording = false
        audioCaptureJob?.cancel()
        audioCaptureJob = null

        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: IllegalStateException) {
            // estado inválido, ignorar
        } finally {
            audioRecord = null
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun resumeMicrophone(outputStream: OutputStream) {
        startAudioCapture(outputStream)
    }


    private fun pauseMicrophone() {
        isRecording = false
        audioCaptureJob?.cancel()
        audioCaptureJob = null

        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (_: IllegalStateException) {
        } finally {
            audioRecord = null
        }
    }



    // Reproducir audio recibido del stream (llamado desde NearbyService)
    suspend fun startAudioPlayback(inputStream: InputStream) = withContext(Dispatchers.IO) {
        if (isPlaying) return@withContext

        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )

            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            audioTrack?.play()
            isPlaying = true
            _walkieTalkieUiState.update { it.copy(isReceiving = true) }

            // Thread para leer y reproducir audio
            viewModelScope.launch {
                val buffer = ByteArray(BUFFER_SIZE)

                while (isPlaying) {
                    try {
                        val readBytes = inputStream.read(buffer)

                        if (readBytes > 0) {
                            audioTrack?.write(buffer, 0, readBytes)
                        } else if (readBytes == -1) {
                            break
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        break
                    }
                }

                stopAudioStream()

                try {
                    inputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAudioStream() {
        pauseMicrophone()

        streamOutputs[endpointId]?.let {
            try { it.close() } catch (_: Exception) {}
        }
        streamOutputs.remove(endpointId)
    }


    override fun onCleared() {
        super.onCleared()
        proximityLock?.release()
        viewModelScope.launch(Dispatchers.IO) {
            stopAudioStream()
        }
        // Restaurar modo de audio normal
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }
}

// Data classes (mantenerlas como están)
data class CallUiState(
    val isInCall: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val callDuration: Long = 0L
)

data class WalkieTalkieUiState(
    val isTransmitting: Boolean = false,
    val isReceiving: Boolean = false
)

sealed class CallEvent {
    data object ToggleMute : CallEvent()
    data object ToggleSpeaker : CallEvent()
    data object EndCall : CallEvent()
    data object StartCall : CallEvent()
}

sealed class WalkieTalkieEvent {
    data object PressToTalk : WalkieTalkieEvent()
    data object ReleaseToTalk : WalkieTalkieEvent()
}