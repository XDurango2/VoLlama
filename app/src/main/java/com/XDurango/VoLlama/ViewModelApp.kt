package com.XDurango.VoLlama

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.google.android.gms.nearby.connection.ConnectionInfo
class ViewModelApp(application: Application): AndroidViewModel(application){
    val nearbyService = NearbyConnectionService(application)

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
            onSuccess = { /* Log o actualizar UI */ },
            onFailure = { exception -> /* Manejar error */ }
        )
    }

    fun stopAdvertising() {
        nearbyService.stopAdvertising()
    }

    fun startDiscovery() {
        nearbyService.startDiscovery(
            onSuccess = { /* Log o actualizar UI */ },
            onFailure = { exception -> /* Manejar error */ }
        )
    }

    fun stopDiscovery() {
        nearbyService.stopDiscovery()
    }

    fun connectToEndpoint(endpointId: String) {
        nearbyService.requestConnection(endpointId)
    }

    fun acceptConnection(endpointId: String) {
        nearbyService.acceptConnection(endpointId)
    }

    fun rejectConnection(endpointId: String) {
        nearbyService.rejectConnection(endpointId)
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
    }

    override fun onCleared() {
        super.onCleared()
        nearbyService.cleanup()
    }

}