package com.XDurango.VoLlama.MainMenu

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import com.google.android.gms.nearby.connection.ConnectionInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ViewModelApp @Inject constructor(private val nearbyService: NearbyConnectionService, application: Application) : AndroidViewModel(application) {


    // ---------------- UI STATE ----------------
    private val _uiState = MutableStateFlow(MainMenuUiState())
    val uiState = _uiState.asStateFlow()

    // ---------------- ONE-SHOT EVENTS ----------------
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    val showConnectionDialog: LiveData<Pair<String, ConnectionInfo>?> =
        nearbyService.showConnectionDialog

    // ---------------- INIT ----------------
    init {
        observeNearby()
    }
    val callStatus : StateFlow<NearbyConnectionService.ConnectionSignal?> = nearbyService.receivedSignals

    // ---------------- EVENT HANDLER ----------------
   // @RequiresPermission(Manifest.permission.RECORD_AUDIO)
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
            is MainEvent.RestartConnection -> {
                restartConnection()
            }



            is MainEvent.Disconnect -> {
                nearbyService.disconnectFromEndpoint(event.endpointId)
                _toastMessage.value = "Desconectado"
            }
        }
    }

    // ---------------- NEARBY CONTROL ----------------

    private fun restartConnection(){
        nearbyService.cleanup()
        startDiscovery()
    }

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
        viewModelScope.launch {
            // ✅ Combinar múltiples flows en uno solo
            combine(
                nearbyService.discoveredEndpoints,
                nearbyService.connectedEndpoints,
                nearbyService.nearbyMode
            ) { discovered, connected, mode ->
                Triple(discovered, connected, mode)
            }.collect { (discovered, connected, mode) ->
                _uiState.update { state ->
                    state.copy(
                        discoveredEndpoints = discovered.map { (id, info) ->
                            NearbyConnectionService.DiscoveredEndpoint(
                                endpointId = id,
                                name = info.endpointName,
                                serviceId = info.serviceId,
                                fullInfo = info
                            )
                        },
                        connectedEndpoints = connected,
                        nearbyStatus = mode,
                        endpointName = nearbyService.endpoint
                    )
                }
            }
        }
    }

    // ---------------- DIALOG ACTIONS ----------------
    fun acceptConnection(endpointId: String) {
        nearbyService.acceptConnection(endpointId)

    }
    fun sendConnectionSignal(endpointId: String,signal: NearbyConnectionService.ConnectionSignalsTypes){
        nearbyService.sendCallSignal(endpointId,signal)
    }
    fun clearConnectionSignalReceived(){
        nearbyService.clearIncomingCallSignal()
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