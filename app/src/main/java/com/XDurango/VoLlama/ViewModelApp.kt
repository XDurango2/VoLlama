package com.XDurango.VoLlama

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.nearby.connection.ConnectionInfo
class ViewModelApp(application: Application): AndroidViewModel(application){
    val nearbyService = NearbyConnectionService(application)

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    val isStreamingAudio: LiveData<Boolean> = nearbyService.isStreamingAudio
    val isReceivingAudio: LiveData<Boolean> = nearbyService.isReceivingAudio

    // Exponer LiveData del servicio
    val discoveredEndpoints: LiveData<MutableList<NearbyConnectionService.DiscoveredEndpoint>>
            = nearbyService.discoveredEndpoints

    val connectedEndpoints: LiveData<MutableSet<String>>
            = nearbyService.connectedEndpoints

    val showConnectionDialog: LiveData<Pair<String, ConnectionInfo>?>
            = nearbyService.showConnectionDialog

//    val receivedMessages: LiveData<NearbyConnectionService.ReceivedMessage>
//            = nearbyService.receivedMessages

    // ========== FUNCIONES PÚBLICAS ==========
    fun startAdvertising() {
        nearbyService.startAdvertising(
            onSuccess = { _toastMessage.value = "Esperando conexión" },
            onFailure = { exception -> _toastMessage.value = "error en broadcast" }
        )
    }

    fun stopAdvertising() {
        nearbyService.stopAdvertising()
        _toastMessage.value = "Broadcast terminado"
    }

    fun startDiscovery() {
        nearbyService.startDiscovery(
            onSuccess = { _toastMessage.value = "Buscando dispositivos" },
            onFailure = { exception -> _toastMessage.value = "error en busqueda" }
        )
    }

    fun stopDiscovery() {
        nearbyService.stopDiscovery()
        _toastMessage.value = "busqueda terminada"
    }

    fun connectToEndpoint(endpointId: String) {
        nearbyService.requestConnection(endpointId)
        _toastMessage.value ="Intentando conectar a dispositivo"
    }

    fun acceptConnection(endpointId: String) {
        nearbyService.acceptConnection(endpointId)
        _toastMessage.value = "conexion aceptada"
    }

    fun rejectConnection(endpointId: String) {
        nearbyService.rejectConnection(endpointId)
        _toastMessage.value = "conexion rechazada"
    }

    fun startVoiceStreaming(endpointId: String) {
        nearbyService.startAudioStream(endpointId)
    }

//    fun startVoiceStreamingToAll() {
//        nearbyService.startAudioStreamToAll()
//    }

    fun stopVoiceStreaming() {
        nearbyService.stopAudioStream()
    }

    fun stopReceivingAudio() {
        nearbyService.stopAudioPlayback()
    }

//    fun sendMessage(endpointId: String, message: String) {
//        nearbyService.sendMessage(endpointId, message)
//    }

//    fun sendMessageToAll(message: String) {
//        nearbyService.sendMessageToAll(message)
//    }

    fun disconnectAll() {
        nearbyService.disconnectFromAll()
        _toastMessage.value = "terminando servicio - cerrando conexiones"
    }

    fun clearToastMessage(){
        _toastMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        nearbyService.cleanup()
    }

}