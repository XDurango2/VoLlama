package com.XDurango.VoLlama

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.nearby.connection.ConnectionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ViewModelApp(application: Application) : AndroidViewModel(application) {

    private val nearbyService = NearbyConnectionService(application)

    // ---------------- UI STATE ----------------
    private val _uiState = MutableStateFlow(MainMenuUiState())
    val uiState = _uiState.asStateFlow()

    // ---------------- ONE-SHOT EVENTS ----------------
    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    val showConnectionDialog: LiveData<Pair<String, ConnectionInfo>?> =
        nearbyService.showConnectionDialog

    // ---------------- INIT ----------------
    init {
        observeNearby()
    }

    // ---------------- EVENT HANDLER ----------------
    fun onEvent(event: MainEvent) {
        when (event) {

            MainEvent.StartConnection -> {
                _uiState.update { it.copy(showBottomSheet = true) }
                startDiscovery()
            }

            MainEvent.CloseBottomSheet -> {
                if (_uiState.value.nearbyStatus != NearbyConnectionService.NearbyStatus.IN_PROGRESS) {
                    stopDiscovery()
                    stopAdvertising()
                    _uiState.update { it.copy(showBottomSheet = false) }
                }
            }

            is MainEvent.ChangeMode -> {
                if (event.index == 0) {
                    stopAdvertising()
                    startDiscovery()
                } else {
                    stopDiscovery()
                    startAdvertising()
                }
                _uiState.update { it.copy(selectedIndex = event.index) }
            }

            is MainEvent.Connect -> {
                nearbyService.requestConnection(event.endpointId)
                _toastMessage.value = "Intentando conectar..."
            }

            is MainEvent.StartStreaming -> {
                nearbyService.startAudioStream(event.endpointId)
            }

            MainEvent.StopStreaming -> {
                nearbyService.stopAudioStream()
            }

            MainEvent.Disconnect -> {
                nearbyService.disconnectFromAll()
                _toastMessage.value = "Desconectado"
            }
        }
    }

    // ---------------- NEARBY CONTROL ----------------
    private fun startDiscovery() {
        nearbyService.startDiscovery(
            onSuccess = { _toastMessage.value = "Buscando dispositivos" },
            onFailure = { _toastMessage.value = "Error en búsqueda" }
        )
    }

    private fun stopDiscovery() {
        nearbyService.stopDiscovery()
    }

    private fun startAdvertising() {
        nearbyService.startAdvertising(
            onSuccess = { _toastMessage.value = "Esperando conexión" },
            onFailure = { _toastMessage.value = "Error en advertising" }
        )
    }

    private fun stopAdvertising() {
        nearbyService.stopAdvertising()
    }

    // ---------------- STATE SYNC ----------------
    private fun observeNearby() {

        nearbyService.discoveredEndpoints.observeForever {
            _uiState.update { state ->
                state.copy(discoveredEndpoints = it)
            }
        }

        nearbyService.connectedEndpoints.observeForever {
            _uiState.update { state ->
                state.copy(connectedEndpoints = it)
            }
        }

        nearbyService.isStreamingAudio.observeForever {
            _uiState.update { state ->
                state.copy(isStreamingAudio = it)
            }
        }

        nearbyService.isReceivingAudio.observeForever {
            _uiState.update { state ->
                state.copy(isReceivingAudio = it)
            }
        }

        nearbyService.nearbyMode.observeForever {
            _uiState.update { state ->
                state.copy(nearbyStatus = it)
            }
        }
        _uiState.update { state ->
            state.copy(endpointName = nearbyService.endpoint)
        }
    }

    // ---------------- DIALOG ACTIONS ----------------
    fun acceptConnection(endpointId: String) {
        nearbyService.acceptConnection(endpointId)
    }

    fun rejectConnection(endpointId: String) {
        nearbyService.rejectConnection(endpointId)
    }

    fun resetNearbyStatus() {
        nearbyService.resetStatus()
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        nearbyService.cleanup()
    }
}
