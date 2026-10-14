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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.google.gson.Gson
import com.XDurango.VoLlama.CallMode.CallManager

class NearbyConnectionService(private val context: Context) {

    // Solo para trabajo que genuinamente necesita background (ninguno actualmente)
    // Mantenemos el scope por si se necesita en el futuro
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class NearbyStatus {
        IDLE, DISCOVERING, ADVERTISING, IN_PROGRESS, REJECTED, CONNECTION_ERROR, CONNECTION_SUCCESS
    }

    enum class ConnectionSignalsTypes {
        INCOMING_CALL, INCOMING_WALKIE_TALKIE, CALL_ACCEPTED, CALL_REJECTED, CALL_ENDED
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

        // Tag de 1 byte al inicio de cada Payload.BYTES para distinguir señalización de audio.
        private const val TAG_SIGNAL: Byte = 0x53 // 'S'
        private const val TAG_AUDIO: Byte = 0x41  // 'A'

        private const val MAX_SIGNAL_RETRIES = 3
        private const val SIGNAL_RETRY_DELAY_MS = 300L
        private const val MAX_START_RETRIES = 1
        private const val RETRY_DELAY_MS = 500L
    }

    val endpoint = "${Build.MANUFACTURER} - ${Build.MODEL}"
    val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    private var callManager: CallManager? = null
    private val pendingConnectionInfo = mutableMapOf<String, ConnectionInfo>()

    // ── Coordinator ───────────────────────────────────────────────────────────

    fun setCallManager(coordinator: CallManager?) {
        this.callManager = coordinator
    }

    // ── API de Nearby — main thread directo ───────────────────────────────────

    fun startAdvertising(onSuccess: () -> Unit = {}, onFailure: (Exception) -> Unit = {}) {
        startWithRetry(
            start = {
                connectionsClient.startAdvertising(
                    endpoint, SERVICE_ID,
                    advertisingConnectionCallback, // muestra dialog al usuario
                    AdvertisingOptions.Builder()
                        .setStrategy(Strategy.P2P_STAR)
                        .setLowPower(false) // prioriza velocidad de aparición sobre batería
                        .build()
                )
            },
            alreadyActive = ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING,
            status = NearbyStatus.ADVERTISING,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    fun startDiscovery(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        startWithRetry(
            start = {
                connectionsClient.startDiscovery(
                    SERVICE_ID, endpointDiscoveryCallback,
                    DiscoveryOptions.Builder()
                        .setStrategy(Strategy.P2P_STAR)
                        .setLowPower(false)
                        .build()
                )
            },
            alreadyActive = ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING,
            status = NearbyStatus.DISCOVERING,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    /**
     * Lanza start{Advertising,Discovery} tolerando el estado ya activo (se trata como éxito)
     * y reintentando una vez tras un breve retardo ante errores transitorios del radio,
     * frecuentes en algunos dispositivos justo después de un stop*().
     */
    private fun startWithRetry(
        start: () -> com.google.android.gms.tasks.Task<Void>,
        alreadyActive: Int,
        status: NearbyStatus,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
        attempt: Int = 0
    ) {
        try {
            start().addOnSuccessListener {
                _nearbyStatus.value = status
                onSuccess()
            }.addOnFailureListener { e ->
                val code = (e as? ApiException)?.statusCode
                when {
                    code == alreadyActive -> {
                        _nearbyStatus.value = status
                        onSuccess()
                    }
                    attempt < MAX_START_RETRIES -> serviceScope.launch {
                        delay(RETRY_DELAY_MS)
                        startWithRetry(start, alreadyActive, status, onSuccess, onFailure, attempt + 1)
                    }
                    else -> onFailure(e)
                }
            }
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

    /**
     * Envía una señal de control. sendPayload puede fallar de forma transitoria (p. ej. justo
     * tras conectar), así que se reintenta; el id permite al receptor descartar duplicados.
     */
    fun sendCallSignal(endpointId: String, signal: ConnectionSignalsTypes) {
        try {
            val json = gson.toJson(
                ConnectionSignal(type = signal, senderId = "", senderName = endpoint)
            )
            sendSignalBytes(endpointId, byteArrayOf(TAG_SIGNAL) + json.toByteArray(), 0)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun sendSignalBytes(endpointId: String, bytes: ByteArray, attempt: Int) {
        try {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnFailureListener {
                    if (attempt < MAX_SIGNAL_RETRIES && endpointId in connectedIds()) {
                        serviceScope.launch {
                            delay(SIGNAL_RETRY_DELAY_MS)
                            sendSignalBytes(endpointId, bytes, attempt + 1)
                        }
                    }
                }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun connectedIds() = _connectedEndpoints.value.map { it.first }.toSet()

    /**
     * Envía un paquete Opus ya codificado como Payload.BYTES: [TAG_AUDIO][seq 2 bytes][opus].
     * Se arma en una sola asignación (se ejecuta ~25 veces/seg durante una llamada).
     */
    fun sendAudioFrame(endpointId: String, seq: Int, opus: ByteArray, opusLen: Int = opus.size) {
        try {
            val payload = ByteArray(3 + opusLen)
            payload[0] = TAG_AUDIO
            payload[1] = (seq shr 8).toByte()
            payload[2] = seq.toByte()
            System.arraycopy(opus, 0, payload, 3, opusLen)
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun clearIncomingCallSignal() {
        _receivedSignals.value = null
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

    private val gson = Gson()

    private val seenSignalIds = ArrayDeque<String>()

    /** @return false si la señal ya se procesó (reintento duplicado). */
    private fun markSignalSeen(id: String?): Boolean {
        if (id == null) return true // peer antiguo sin id
        if (id in seenSignalIds) return false
        seenSignalIds.addLast(id)
        if (seenSignalIds.size > 32) seenSignalIds.removeFirst()
        return true
    }

    // ── Callback de payloads ──────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            try {
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        val bytes = payload.asBytes() ?: return
                        if (bytes.isEmpty()) return

                        when (bytes[0]) {
                            TAG_AUDIO -> {
                                callManager?.onIncomingAudioFrame(bytes.copyOfRange(1, bytes.size))
                            }
                            TAG_SIGNAL -> {
                                val json = String(bytes, 1, bytes.size - 1)
                                val signal = gson
                                    .fromJson(json, ConnectionSignal::class.java)
                                    .copy(senderId = endpointId)

                                if (!markSignalSeen(signal.id)) return

                                when (signal.type) {
                                    ConnectionSignalsTypes.INCOMING_CALL,
                                    ConnectionSignalsTypes.INCOMING_WALKIE_TALKIE ->
                                        _receivedSignals.value = signal
                                    ConnectionSignalsTypes.CALL_ACCEPTED ->
                                        callManager?.onRemoteCallAccepted()
                                    ConnectionSignalsTypes.CALL_REJECTED ->
                                        callManager?.onRemoteCallRejected()
                                    ConnectionSignalsTypes.CALL_ENDED ->
                                        callManager?.onRemoteCallEnded()
                                }
                            }
                        }
                    }

                    Payload.Type.STREAM -> { /* no usado: el audio viaja por BYTES (Opus) */ }

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
        val timestamp: Long = System.currentTimeMillis(),
        val id: String? = java.util.UUID.randomUUID().toString()
    )

    data class DiscoveredEndpoint(
        val endpointId: String,
        val name: String,
        val serviceId: String,
        val fullInfo: DiscoveredEndpointInfo
    )

    fun cleanup() {
        disconnectFromAll()
    }
}