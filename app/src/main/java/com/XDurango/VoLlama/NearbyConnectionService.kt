package com.XDurango.VoLlama

import android.Manifest
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NearbyConnectionService(private val context: Context) {

    // Crear un CoroutineScope para operaciones asíncronas
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class NearbyStatus {
        IDLE,
        DISCOVERING,
        ADVERTISING,
        IN_PROGRESS,
        REJECTED,
        CONNECTION_ERROR,
        CONNECTION_SUCCESS
    }

    // LiveData
    private val _nearbyStatus = MutableLiveData(NearbyStatus.IDLE)
    val nearbyMode: LiveData<NearbyStatus> = _nearbyStatus

    private val _receivedMessages = MutableLiveData<ReceivedMessage>()
    val receivedMessages: LiveData<ReceivedMessage> = _receivedMessages

    private val _connectedEndpoints = MutableLiveData<MutableSet<Pair<String, ConnectionInfo>>>(mutableSetOf())
    val connectedEndpoints: LiveData<MutableSet<Pair<String, ConnectionInfo>>> = _connectedEndpoints

    private val _discoveredEndpoints = MutableLiveData<MutableList<DiscoveredEndpoint>>(mutableListOf())
    val discoveredEndpoints: LiveData<MutableList<DiscoveredEndpoint>> = _discoveredEndpoints

    private val _showConnectionDialog = MutableLiveData<Pair<String, ConnectionInfo>?>()
    val showConnectionDialog: LiveData<Pair<String, ConnectionInfo>?> = _showConnectionDialog

    companion object {
        const val SERVICE_ID = "com.XDurango.VoLlama"
    }

    val endpoint = "${Build.MANUFACTURER} - ${Build.MODEL}"

    val connectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    // Referencia opcional al CallManager para manejar streams de audio
    private var callManager: CallManager? = null // Cambiaremos el tipo después

    fun setCallManager(manager: CallManager) {
        this.callManager = manager
    }

    fun startAdvertising(
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {},
    ) {
        serviceScope.launch {
            try {
                val advertisingOptions = AdvertisingOptions.Builder()
                    .setStrategy(Strategy.P2P_STAR)
                    .build()

                connectionsClient
                    .startAdvertising(
                        endpoint,
                        SERVICE_ID,
                        connectionLifecycleCallback,
                        advertisingOptions
                    )
                    .addOnSuccessListener {
                        _nearbyStatus.postValue(NearbyStatus.ADVERTISING)
                        onSuccess()
                    }
                    .addOnFailureListener {
                        onFailure(it)
                    }
            } catch (e: Exception) {
                onFailure(e)
            }
        }
    }

    fun startDiscovery(
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        serviceScope.launch {
            try {
                val discoveryOptions = DiscoveryOptions.Builder()
                    .setStrategy(Strategy.P2P_STAR)
                    .build()

                connectionsClient
                    .startDiscovery(
                        SERVICE_ID,
                        endpointDiscoveryCallback,
                        discoveryOptions
                    )
                    .addOnSuccessListener {
                        _nearbyStatus.postValue(NearbyStatus.DISCOVERING)
                        onSuccess()
                    }
                    .addOnFailureListener {
                        onFailure(it)
                    }
            } catch (e: Exception) {
                onFailure(e)
            }
        }
    }

    fun stopDiscovery() {
        serviceScope.launch {
            try {
                connectionsClient.stopDiscovery()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopAdvertising() {
        serviceScope.launch {
            try {
                connectionsClient.stopAdvertising()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun acceptConnection(endpointId: String) {
        serviceScope.launch {
            try {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                    .addOnSuccessListener { }
                    .addOnFailureListener { exception ->
                        _showConnectionDialog.postValue(null)
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun rejectConnection(endpointId: String) {
        serviceScope.launch {
            try {
                connectionsClient.rejectConnection(endpointId)
                _showConnectionDialog.postValue(null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun requestConnection(endpointId: String) {
        serviceScope.launch {
            try {
                connectionsClient
                    .requestConnection(endpoint, endpointId, connectionLifecycleCallback)
                    .addOnSuccessListener { }
                    .addOnFailureListener { exception ->
                        _showConnectionDialog.postValue(null)
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnectFrom(endpointId: String) {
        serviceScope.launch {
            try {
                connectionsClient.disconnectFromEndpoint(endpointId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnectFromAll() {
        serviceScope.launch {
            try {
                connectionsClient.stopAllEndpoints()
                _connectedEndpoints.postValue(mutableSetOf())
                _discoveredEndpoints.postValue(mutableListOf())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            _nearbyStatus.postValue(NearbyStatus.IN_PROGRESS)
            _showConnectionDialog.postValue(endpointId to connectionInfo)
        }

        override fun onConnectionResult(
            endpointId: String,
            result: ConnectionResolution
        ) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    _nearbyStatus.postValue(NearbyStatus.CONNECTION_SUCCESS)
                    val updated = _connectedEndpoints.value.orEmpty().toMutableSet()
                    val connectionInfo = _showConnectionDialog.value?.second
                    if (connectionInfo != null) {
                        updated.add(endpointId to connectionInfo)
                    }
                    _connectedEndpoints.postValue(updated)
                    stopDiscovery()
                    stopAdvertising()
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    _nearbyStatus.postValue(NearbyStatus.REJECTED)
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    _nearbyStatus.postValue(NearbyStatus.CONNECTION_ERROR)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val updated = _connectedEndpoints.value.orEmpty().toMutableSet()
            updated.removeIf { it.first == endpointId }
            _connectedEndpoints.postValue(updated)

            if (updated.isEmpty()) {
                _nearbyStatus.postValue(NearbyStatus.IDLE)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            serviceScope.launch {
                try {
                    when (payload.type) {
                        Payload.Type.BYTES -> {
                            val receivedBytes = payload.asBytes()
                            val message = receivedBytes?.let { String(it) }
                            message?.let {
                                _receivedMessages.postValue(
                                    ReceivedMessage(endpointId, it)
                                )
                            }
                        }

                        Payload.Type.FILE -> {
                            // Procesar archivo recibido si es necesario
                        }

                        Payload.Type.STREAM -> {
                            val receivedStream = payload.asStream()?.asInputStream()
                            receivedStream?.let { stream ->
                                // Delegar al CallManager si está disponible
                                (callManager as? CallManager)?.let { manager ->
                                    serviceScope.launch {
                                        manager.startAudioPlayback(stream)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    // Transferencia completada
                }

                PayloadTransferUpdate.Status.FAILURE -> {
                    // Transferencia falló
                }

                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Transferencia en progreso
                    val progress = update.bytesTransferred
                }
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val endpointAvailable = DiscoveredEndpoint(
                endpointId = endpointId,
                name = info.endpointName,
                serviceId = info.serviceId,
                fullInfo = info
            )
            val currentList = _discoveredEndpoints.value ?: mutableListOf()
            if (currentList.none { it.endpointId == endpointId }) {
                currentList.add(endpointAvailable)
                _discoveredEndpoints.postValue(currentList)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            val currentList = _discoveredEndpoints.value ?: mutableListOf()
            currentList.removeAll { it.endpointId == endpointId }
            _discoveredEndpoints.postValue(currentList)
        }
    }

    data class DiscoveredEndpoint(
        val endpointId: String,
        val name: String,
        val serviceId: String,
        val fullInfo: DiscoveredEndpointInfo
    )

    data class ReceivedMessage(
        val fromEndpointId: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun cleanup() {
        serviceScope.launch {
            try {
                disconnectFromAll()
                serviceScope.cancel() // Cancelar todas las coroutines
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resetStatus() {
        _nearbyStatus.postValue(NearbyStatus.IDLE)
    }
}