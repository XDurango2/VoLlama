package com.XDurango.VoLlama.CallMode

import com.XDurango.VoLlama.Audio.AudioEngine
import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

/**
 * ViewModel de la pantalla de llamada.
 *
 * Responsabilidades:
 *  - Mantener el estado de UI (CallUiState, WalkieTalkieUiState, CallState)
 *  - Orquestar la señalización con NearbyConnectionService
 *  - Delegar toda la captura/reproducción de audio a AudioEngine
 *
 * Lo que NO hace:
 *  - No toca AudioRecord, AudioTrack ni streams directamente
 *  - No conoce PipedOutputStream ni ArrayBlockingQueue
 */
@HiltViewModel
class CallManager @Inject constructor(
    private val nearbyService: NearbyConnectionService,
    private val audioEngine: AudioEngine
) : ViewModel() {

    // ── Identidad del endpoint remoto ─────────────────────────────────────────

    private var endpointId: String = ""
    private var endpointName: String = ""

    fun getCurrentEndpointId(): String = endpointId

    // ── Estado de UI ──────────────────────────────────────────────────────────

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _callUiState = MutableStateFlow(CallUiState())
    val callUiState: StateFlow<CallUiState> = _callUiState.asStateFlow()

    private val _walkieTalkieUiState = MutableStateFlow(WalkieTalkieUiState())
    val walkieTalkieUiState: StateFlow<WalkieTalkieUiState> = _walkieTalkieUiState.asStateFlow()

    private var callTimerJob: Job? = null
    private var pendingStream: InputStream? = null
    private var isWalkieTalkieMode = false

    // ── Inicialización ────────────────────────────────────────────────────────

    /**
     * Debe llamarse al abrir la pantalla de llamada, antes de cualquier evento.
     * Conecta el AudioEngine con este scope y observa desconexiones.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun initialize(endpointId: String, endpointName: String) {
        this.endpointId = endpointId
        this.endpointName = endpointName

        wireAudioEngineCallbacks()
        observeConnectionState()
    }

    private fun wireAudioEngineCallbacks() {
        audioEngine.onCaptureError = {
            // El micrófono falló: terminar la llamada limpiamente
            onCallEvent(CallEvent.EndCall)
        }
        audioEngine.onPlaybackEnded = {
            // El stream remoto se cerró: actualizar el estado de recepción
            _walkieTalkieUiState.update { it.copy(isReceiving = false) }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            nearbyService.connectedEndpoints.collect { endpoints ->
                val stillConnected = endpoints.any { it.first == endpointId }
                val callIsActive = _callState.value !in listOf(CallState.IDLE, CallState.ENDED)
                if (!stillConnected && callIsActive) {
                    onCallEvent(CallEvent.EndCall)
                }
            }
        }
    }

    // ── Eventos de UI ─────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun onCallEvent(event: CallEvent) {
        when (event) {

            CallEvent.ToggleMute -> {
                val muted = !_callUiState.value.isMuted
                _callUiState.update { it.copy(isMuted = muted) }
                audioEngine.isMuted = muted
            }

            CallEvent.ToggleSpeaker -> {
                val speaker = !_callUiState.value.isSpeakerOn
                _callUiState.update { it.copy(isSpeakerOn = speaker) }
                audioEngine.setSpeakerOn(speaker)
            }

            CallEvent.StartCall -> {
                if (_callState.value != CallState.IDLE) return
                viewModelScope.launch {
                    _callState.value = CallState.CONNECTING
                    withContext(Dispatchers.IO) {
                        nearbyService.sendCallSignal(
                            endpointId,
                            NearbyConnectionService.ConnectionSignalsTypes.INCOMING_CALL
                        )
                    }
                    startCallTimer()
                    _callUiState.update { it.copy(isInCall = true) }
                }
            }

            CallEvent.AnswerCall -> {
                if (_callState.value != CallState.IDLE) return
                viewModelScope.launch {
                    audioEngine.startAudioMode()
                    _callState.value = CallState.IN_CALL
                    startCallTimer()
                    withContext(Dispatchers.IO) {
                        audioEngine.startCapture(endpointId, viewModelScope)
                    }
                    _callUiState.update { it.copy(isInCall = true) }
                }
            }

            CallEvent.EndCall -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        nearbyService.sendCallSignal(
                            endpointId,
                            NearbyConnectionService.ConnectionSignalsTypes.CALL_ENDED
                        )
                    }
                    teardownCall()
                }
            }
        }
    }

    // ── Notificaciones desde NearbyConnectionService ──────────────────────────

    /** Llamado cuando el remoto acepta la llamada que iniciamos nosotros. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun onRemoteCallAccepted() {
        viewModelScope.launch {
            audioEngine.startAudioMode()
            // En modo walkie-talkie no iniciamos captura aquí — solo cuando se presiona PTT
            if (!isWalkieTalkieMode) {
                withContext(Dispatchers.IO) {
                    audioEngine.startCapture(endpointId, viewModelScope)
                }
            }
            _callState.value = CallState.IN_CALL
            _callUiState.update { it.copy(isInCall = true) }
            pendingStream?.let { stream ->
                _walkieTalkieUiState.update { it.copy(isReceiving = true) }
                audioEngine.startPlayback(stream, viewModelScope)
                pendingStream = null
            }
        }
    }

    /** Llamado cuando el remoto rechaza la llamada. */
    fun onRemoteCallRejected() {
        _callUiState.update { it.copy(isInCall = false, endReason = CallEndReason.REJECTED) }
        _callState.value = CallState.ENDED
    }

    /** Llamado cuando el remoto cuelga o se pierde la conexión. */
    fun onRemoteCallEnded() {
        viewModelScope.launch { teardownCall() }
    }

    /**
     * Llamado por NearbyConnectionService cuando llega un stream de audio entrante.
     * El coordinator decide si reproducirlo (la llamada está activa) o descartarlo.
     */
    fun onIncomingAudioStream(stream: InputStream) {
        when (_callState.value) {
            CallState.IN_CALL -> {
                _walkieTalkieUiState.update { it.copy(isReceiving = true) }
                audioEngine.startPlayback(stream, viewModelScope)
            }
            CallState.CONNECTING -> {
                // El stream llegó antes que CALL_ACCEPTED — lo guardamos
                try { pendingStream?.close() } catch (_: Exception) {}
                pendingStream = stream
            }
            else -> try { stream.close() } catch (_: Exception) {}
        }
    }

    // ── Walkie-Talkie ─────────────────────────────────────────────────────────

    fun startWalkieTalkieMode(isIncoming: Boolean) {
        if (_callState.value != CallState.IDLE) return
        isWalkieTalkieMode = true
        if (isIncoming) {
            audioEngine.startAudioMode()
            _callState.value = CallState.IN_CALL
            _callUiState.update { it.copy(isInCall = true) }
            startCallTimer()
        } else {
            viewModelScope.launch {
                _callState.value = CallState.CONNECTING
                withContext(Dispatchers.IO) {
                    nearbyService.sendCallSignal(
                        endpointId,
                        NearbyConnectionService.ConnectionSignalsTypes.INCOMING_WALKIE_TALKIE
                    )
                }
                startCallTimer()
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun onWalkieTalkieEvent(event: WalkieTalkieEvent) {
        when (event) {
            WalkieTalkieEvent.PressToTalk -> {
                if (_callState.value != CallState.IN_CALL) return
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        audioEngine.startCapture(endpointId, viewModelScope)
                    }
                    _walkieTalkieUiState.update { it.copy(isTransmitting = true) }
                }
            }
            WalkieTalkieEvent.ReleaseToTalk -> {
                audioEngine.stopCapture()
                _walkieTalkieUiState.update { it.copy(isTransmitting = false) }
            }
        }
    }

    // ── Limpieza ──────────────────────────────────────────────────────────────

    private fun teardownCall() {
        audioEngine.stopAll()
        stopCallTimer()
        try { pendingStream?.close() } catch (_: Exception) {}
        pendingStream = null
        isWalkieTalkieMode = false
        _callState.value = CallState.ENDED
        _callUiState.update { it.copy(isInCall = false, callDuration = 0L, endReason = null) }
        _walkieTalkieUiState.update { it.copy(isReceiving = false, isTransmitting = false) }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.stopAll()
        audioEngine.resetAudioMode()
        audioEngine.onCaptureError = null
        audioEngine.onPlaybackEnded = null
        stopCallTimer()
        nearbyService.setCallManager(null)
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                _callUiState.update { it.copy(callDuration = System.currentTimeMillis() - start) }
                delay(1000)
            }
        }
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
    }
}

// ── Modelos de estado ─────────────────────────────────────────────────────────

enum class CallState { IDLE, CONNECTING, IN_CALL, ENDED }

enum class CallEndReason { REJECTED }

data class CallUiState(
    val isInCall: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val callDuration: Long = 0L,
    val endReason: CallEndReason? = null
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
    data object AnswerCall : CallEvent()
}

sealed class WalkieTalkieEvent {
    data object PressToTalk : WalkieTalkieEvent()
    data object ReleaseToTalk : WalkieTalkieEvent()
}