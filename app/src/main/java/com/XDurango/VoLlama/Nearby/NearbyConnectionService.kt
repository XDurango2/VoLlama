package com.XDurango.VoLlama.Nearby

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.google.gson.Gson
import java.io.InputStream
import com.XDurango.VoLlama.CallMode.CallManager

class NearbyConnectionService(private val context: Context) {

    // Solo para trabajo que genuinamente necesita background (ninguno actualmente)
    // Mantenemos el scope por si se necesita en el futuro
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class NearbyStatus {
        IDLE, DISCOVERING, ADVERTISING, IN_PROGRESS, REJECTED, CONNECTION_ERROR, CONNECTION_SUCCESS
    }

    enum class ConnectionSignalsTypes {
        INCOMING_CALL, CALL_ACCEPTED, CALL_REJECTED, CALL_ENDED
    }

    private val _nearbyStatus = MutableStateFlow(NearbyStatus.IDLE)
    val nearbyMode: StateFlow<NearbyStatus> = _nearbyStatus

    private val _receivedSignals = MutableStateFlow<ConnectionSignal?>(null)
    val receivedSignals: StateFlow<ConnectionSignal?> = _receivedSignals.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<Set<Pair<String, ConnectionInfo>>>(emptySet())
    val connectedEndpoints: StateFlow<Set<Pair<String, ConnectionInfo>>> = _connectedEndpoints

    private val _discoveredEndpoints = MutableStateFlow<List<Pair<String, DiscoveredEndpointInfo>>>(emptyList())
    val discoveredEndpoints: StateFlow<List<Pair<String, DiscoveredEndpointInfo>>> = _discoveredEndpoints.asStateFlow()

    private val _showConnectionDialog = MutableLiveData<Pair<String, ConnectionInfo>?>()
    val showConnectionDialog: LiveData<Pair<String, ConnectionInfo>?> = _showConnectionDialog

    companion object {
        const val SERVICE_ID = "com.XDurango.VoLlama"
    }

    val endpoint = "${Build.MANUFACTURER} - ${Build.MODEL}"
    val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    private var callManager: CallManager? = null
    private var pendingIncomingStream: InputStream? = null
    private val pendingConnectionInfo = mutableMapOf<String, ConnectionInfo>()

    // ── Coordinator ───────────────────────────────────────────────────────────

    fun setCallManager(coordinator: CallManager?) {
        this.callManager = coordinator
        if (coordinator != null) {
            pendingIncomingStream?.let { stream ->
                coordinator.onIncomingAudioStream(stream)
                pendingIncomingStream = null
            }
        } else {
            try { pendingIncomingStream?.close() } catch (_: Exception) {}
            pendingIncomingStream = null
        }
    }

    // ── API de Nearby — main thread directo ───────────────────────────────────

    fun startAdvertising(onSuccess: () -> Unit = {}, onFailure: (Exception) -> Unit = {}) {
        try {
            connectionsClient.startAdvertising(
                endpoint, SERVICE_ID,
                advertisingConnectionCallback, // muestra dialog al usuario
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            ).addOnSuccessListener {
                _nearbyStatus.value = NearbyStatus.ADVERTISING
                onSuccess()
            }.addOnFailureListener { onFailure(it) }
        } catch (e: Exception) { onFailure(e) }
    }

    fun startDiscovery(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            connectionsClient.startDiscovery(
                SERVICE_ID, endpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            ).addOnSuccessListener {
                _nearbyStatus.value = NearbyStatus.DISCOVERING
                onSuccess()
            }.addOnFailureListener { onFailure(it) }
        } catch (e: Exception) { onFailure(e) }
    }

    fun stopDiscovery() {
        try { connectionsClient.stopDiscovery() } catch (e: Exception) { e.printStackTrace() }
    }

    fun stopAdvertising() {
        try { connectionsClient.stopAdvertising() } catch (e: Exception) { e.printStackTrace() }
    }

    fun acceptConnection(endpointId: String) {
        try {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener { _showConnectionDialog.value = null }
                .addOnFailureListener { _showConnectionDialog.value = null }
        } catch (e: Exception) {
            e.printStackTrace()
            _showConnectionDialog.value = null
        }
    }

    fun rejectConnection(endpointId: String) {
        try {
            connectionsClient.rejectConnection(endpointId)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _showConnectionDialog.value = null
            pendingConnectionInfo.remove(endpointId)
            try { pendingIncomingStream?.close() } catch (_: Exception) {}
            pendingIncomingStream = null
        }
    }

    fun requestConnection(endpointId: String) {
        try {
            connectionsClient.requestConnection(
                endpoint, endpointId,
                discoveryConnectionCallback // auto-acepta sin dialog
            ).addOnFailureListener { _showConnectionDialog.value = null }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun sendCallSignal(endpointId: String, signal: ConnectionSignalsTypes) {
        try {
            val json = Gson().toJson(
                ConnectionSignal(type = signal, senderId = "", senderName = endpoint)
            )
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(json.toByteArray()))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun clearIncomingCallSignal() {
        _receivedSignals.value = null
        try { pendingIncomingStream?.close() } catch (_: Exception) {}
        pendingIncomingStream = null
    }

    fun disconnectFromEndpoint(endpointId: String) {
        try {
            connectionsClient.disconnectFromEndpoint(endpointId)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _connectedEndpoints.update { it.filterNot { ep -> ep.first == endpointId }.toSet() }
            pendingConnectionInfo.remove(endpointId)
            if (_connectedEndpoints.value.isEmpty()) _nearbyStatus.value = NearbyStatus.IDLE
        }
    }

    fun disconnectFromAll() {
        try {
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _connectedEndpoints.value = emptySet()
            _discoveredEndpoints.value = emptyList()
            pendingConnectionInfo.clear()
            clearIncomingCallSignal()
        }
    }

    fun resetStatus() { _nearbyStatus.value = NearbyStatus.IDLE }

    // ── Callbacks de conexión ─────────────────────────────────────────────────

    // Discoverer: auto-acepta inmediatamente para no bloquear el handshake
    private val discoveryConnectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            _nearbyStatus.value = NearbyStatus.IN_PROGRESS
            pendingConnectionInfo[endpointId] = connectionInfo
            acceptConnection(endpointId)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            handleConnectionResult(endpointId, result)
        }
        override fun onDisconnected(endpointId: String) {
            handleDisconnected(endpointId)
        }
    }

    // Advertiser: muestra dialog — el usuario decide si acepta
    private val advertisingConnectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            _nearbyStatus.value = NearbyStatus.IN_PROGRESS
            pendingConnectionInfo[endpointId] = connectionInfo
            _showConnectionDialog.value = endpointId to connectionInfo
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            handleConnectionResult(endpointId, result)
        }
        override fun onDisconnected(endpointId: String) {
            handleDisconnected(endpointId)
        }
    }

    // ── Lógica compartida de callbacks ────────────────────────────────────────

    private fun handleConnectionResult(endpointId: String, result: ConnectionResolution) {
        when (result.status.statusCode) {
            ConnectionsStatusCodes.STATUS_OK -> {
                stopDiscovery()
                stopAdvertising()
                _nearbyStatus.value = NearbyStatus.CONNECTION_SUCCESS
                pendingConnectionInfo.remove(endpointId)?.let { info ->
                    _connectedEndpoints.update { it + (endpointId to info) }
                }
            }
            ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                _nearbyStatus.value = NearbyStatus.REJECTED
                pendingConnectionInfo.remove(endpointId)
            }
            ConnectionsStatusCodes.STATUS_ERROR -> {
                _nearbyStatus.value = NearbyStatus.CONNECTION_ERROR
                pendingConnectionInfo.remove(endpointId)
            }
        }
    }

    private fun handleDisconnected(endpointId: String) {
        _connectedEndpoints.update { it.filterNot { ep -> ep.first == endpointId }.toSet() }
        if (_connectedEndpoints.value.isEmpty()) _nearbyStatus.value = NearbyStatus.IDLE
        callManager?.onRemoteCallEnded()
    }

    // ── Callback de payloads ──────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            try {
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        val bytes = payload.asBytes() ?: return
                        val signal = Gson()
                            .fromJson(String(bytes), ConnectionSignal::class.java)
                            .copy(senderId = endpointId)

                        when (signal.type) {
                            ConnectionSignalsTypes.INCOMING_CALL ->
                                _receivedSignals.value = signal
                            ConnectionSignalsTypes.CALL_ACCEPTED ->
                                callManager?.onRemoteCallAccepted()
                            ConnectionSignalsTypes.CALL_REJECTED ->
                                callManager?.onRemoteCallRejected()
                            ConnectionSignalsTypes.CALL_ENDED ->
                                callManager?.onRemoteCallEnded()
                        }
                    }

                    Payload.Type.STREAM -> {
                        val stream = payload.asStream()?.asInputStream() ?: return
                        if (callManager != null) {
                            callManager?.onIncomingAudioStream(stream)
                        } else {
                            try { pendingIncomingStream?.close() } catch (_: Exception) {}
                            pendingIncomingStream = stream
                        }
                    }

                    Payload.Type.FILE -> { /* no usado */ }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                val activeEndpoint = callManager?.getCurrentEndpointId()
                if (activeEndpoint == endpointId) {
                    callManager?.onRemoteCallEnded()
                }
            }
        }
    }

    // ── Callback de discovery ─────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            _discoveredEndpoints.update { list ->
                if (list.none { it.first == endpointId }) list + (endpointId to info) else list
            }
        }
        override fun onEndpointLost(endpointId: String) {
            _discoveredEndpoints.update { it.filterNot { ep -> ep.first == endpointId } }
        }
    }

    // ── Modelos ───────────────────────────────────────────────────────────────

    data class ConnectionSignal(
        val type: ConnectionSignalsTypes,
        val senderId: String,
        val senderName: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class DiscoveredEndpoint(
        val endpointId: String,
        val name: String,
        val serviceId: String,
        val fullInfo: DiscoveredEndpointInfo
    )

    fun cleanup() {
        disconnectFromAll()
        serviceScope.cancel()
    }
}