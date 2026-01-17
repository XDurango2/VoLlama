package com.XDurango.VoLlama

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
import kotlin.collections.mutableSetOf
import android.media.AudioRecord
import android.media.AudioTrack
import java.io.OutputStream
import android.media.AudioFormat
import java.io.PipedInputStream
import java.io.PipedOutputStream
import android.media.MediaRecorder
import java.io.InputStream

class NearbyConnectionService (private val context: Context) {

    // LiveData para mensajes recibidos
    private val _receivedMessages = MutableLiveData<ReceivedMessage>()
    val receivedMessages: LiveData<ReceivedMessage> = _receivedMessages
    private val _connectedEnpoints = MutableLiveData<MutableSet<String>>(mutableSetOf())
    val connectedEndpoints : LiveData<MutableSet<String>> = _connectedEnpoints
    // LiveData para dispositivos descubiertos
    private val _discoveredEndpoints = MutableLiveData<MutableList<DiscoveredEndpoint>>(mutableListOf())
    val discoveredEndpoints: LiveData<MutableList<DiscoveredEndpoint>> = _discoveredEndpoints
    private val _showConnectionDialog = MutableLiveData<Pair<String, ConnectionInfo>?>()
    val showConnectionDialog: LiveData<Pair<String, ConnectionInfo>?> = _showConnectionDialog
    val SERVICE_ID = "com.XDurango.VoLlama"

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    private var streamOutputs = mutableMapOf<String, OutputStream>()

    // LiveData para estado de streaming
    private val _isStreamingAudio = MutableLiveData(false)
    val isStreamingAudio: LiveData<Boolean> = _isStreamingAudio

    private val _isReceivingAudio = MutableLiveData(false)
    val isReceivingAudio: LiveData<Boolean> = _isReceivingAudio

    companion object {
        const val SERVICE_ID = "com.XDurango.VoLlama"

        // Configuración de audio
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE :Int by lazy{
            val size=AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
            if (size <= 0) SAMPLE_RATE * 2 else size
        }
    }

    val endpoint = "${Build.MANUFACTURER} - ${Build.MODEL}"
    val connectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    fun startAdvertising(
        onSuccess:() -> Unit ={},
        onFailure:(Exception) -> Unit = {},
    ) {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()
        connectionsClient
            .startAdvertising(
                endpoint,
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
            ).addOnSuccessListener {
                //estamos disponibles para conectar!
                onSuccess()

            }.addOnFailureListener {
                //no podemos iniciar
                onFailure(it)
            }

    }

    fun startDiscovery(
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
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
                //estamos descubriendo dispositivos disponibles!!
                onSuccess()
            }
            .addOnFailureListener {
                // no podemos iniciar
                onFailure(it)
            }
    }

    fun stopDiscovery() {
       connectionsClient
            .stopDiscovery()
    }

    fun stopAdvertising() {
       connectionsClient
            .stopAdvertising()
    }

    fun acceptConnection(endpointId: String) {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener { _showConnectionDialog.postValue(null) }
            .addOnFailureListener { exception -> _showConnectionDialog.postValue(null) }

    }

    fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
        _showConnectionDialog.postValue(null)
    }

    fun requestConnection(endpointId: String) {
        connectionsClient
            .requestConnection(endpoint, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { _showConnectionDialog.postValue(null) }
            .addOnFailureListener { exception -> _showConnectionDialog.postValue(null) }
    }

    fun disconnectFrom(endpointId: String){
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    fun disconnectFromAll(){
        connectionsClient.stopAllEndpoints()
        _connectedEnpoints.postValue(mutableSetOf())
        _discoveredEndpoints.postValue(mutableListOf())
    }

    // Iniciar streaming de audio a un endpoint específico
    fun startAudioStream(endpointId: String) {
        try {
            // Crear stream de salida
            val pipedOutputStream = PipedOutputStream()
            val pipedInputStream = PipedInputStream(pipedOutputStream)

            // Guardar el stream
            streamOutputs[endpointId] = pipedOutputStream

            // Enviar el stream como Payload
            val payload = Payload.fromStream(pipedInputStream)
            connectionsClient.sendPayload(endpointId, payload)

            // Iniciar captura de audio
            startAudioCapture(pipedOutputStream)

            _isStreamingAudio.postValue(true)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Capturar audio del micrófono y escribir al stream
    fun startAudioCapture(outputStream: OutputStream) {
        if (isRecording) return

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

            // Thread para capturar y enviar audio
            Thread {
                val buffer = ByteArray(BUFFER_SIZE)

                while (isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (readBytes > 0) {
                        try {
                            outputStream.write(buffer, 0, readBytes)
                            outputStream.flush()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            break
                        }
                    }
                }

                try {
                    outputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Detener streaming de audio
    fun stopAudioStream() {
        isRecording = false
        _isStreamingAudio.postValue(false)

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        // Cerrar todos los streams
        streamOutputs.values.forEach { outputStream ->
            try {
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        streamOutputs.clear()
    }

    // Reproducir audio recibido del stream
    private fun startAudioPlayback(inputStream: InputStream) {
        if (isPlaying) return

        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )

            audioTrack = AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            audioTrack?.play()
            isPlaying = true
            _isReceivingAudio.postValue(true)

            // Thread para leer y reproducir audio
            Thread {
                val buffer = ByteArray(BUFFER_SIZE)

                while (isPlaying) {
                    try {
                        val readBytes = inputStream.read(buffer)

                        if (readBytes > 0) {
                            audioTrack?.write(buffer, 0, readBytes)
                        } else if (readBytes == -1) {
                            // Stream terminado
                            break
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        break
                    }
                }

                stopAudioPlayback()

                try {
                    inputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Detener reproducción de audio
    fun stopAudioPlayback() {
        isPlaying = false
        _isReceivingAudio.postValue(false)

        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            _showConnectionDialog.postValue(Pair(endpointId, connectionInfo))
        }

        override fun onConnectionResult(
            endpointId: String,
            result: ConnectionResolution
        ) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    //"Conexion Exitosa!"
                    _connectedEnpoints.value?.add(endpointId)
                    _connectedEnpoints.postValue(_connectedEnpoints.value)
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    TODO("Conexion Rechazada")

                ConnectionsStatusCodes.STATUS_ERROR ->
                    TODO("hubo un error al intentar conectarse!")
            }
        }

        override fun onDisconnected(endpointId: String) {
            // Desconectados de este endpoint
            // No se pueden enviar o recibir más datos
        }

    }

    //  Defines el PayloadCallback (cómo manejar datos)
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Aquí recibes los datos que te envían
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
                    val receivedFile = payload.asFile()
                    // Procesar archivo recibido
                }

                Payload.Type.STREAM -> {
                    val receivedStream = payload.asStream()?.asInputStream()
                    // Procesar stream recibido
                    receivedStream?.let {
                        startAudioPlayback(it)
                    }
                }
            }
        }


        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Aquí recibes actualizaciones del progreso de transferencia
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    // Transferencia completada
                }

                PayloadTransferUpdate.Status.FAILURE -> {
                    // Transferencia falló
                    if(update.payloadId !=0L){
                        stopAudioStream()
                        stopAudioPlayback()
                    }
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
            val enpointAvaliable = DiscoveredEndpoint(
                endpointId = endpointId,
                name = info.endpointName,
                serviceId = info.serviceId,
                fullInfo =  info
            )
            val currentList = _discoveredEndpoints.value ?: mutableListOf()
            if (currentList.none { it.endpointId == endpointId }) {//evita duplicados
                currentList.add(enpointAvaliable)
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

    // Limpiar recursos
    fun cleanup() {
        stopAudioStream()
        stopAudioPlayback()
        disconnectFromAll()
    }
}