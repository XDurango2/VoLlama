package com.XDurango.VoLlama.Nearby

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.XDurango.VoLlama.CallMode.CallManager
import com.XDurango.VoLlama.Nearby.NearbyConnectionService.ConnectionSignal
import com.XDurango.VoLlama.Nearby.NearbyConnectionService.ConnectionSignalsTypes
import com.XDurango.VoLlama.Nearby.NearbyConnectionService.NearbyStatus
import com.XDurango.VoLlama.testutil.completedTask
import com.XDurango.VoLlama.testutil.failedTask
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Capa de red (Nearby): arranque de advertising/discovery, handshake de conexión,
 * señalización con reintentos/deduplicación y formato de los frames de audio.
 *
 * Los tests "BUG:" describen síntomas vistos en uso real ("a veces no conecta"); fallan mientras
 * el bug exista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyConnectionServiceTest {

    @get:Rule val instantTaskExecutor = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<ConnectionsClient>(relaxed = true)
    private val gson = Gson()

    private lateinit var service: NearbyConnectionService
    private lateinit var callManager: CallManager

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        mockkStatic(Nearby::class)
        every { Nearby.getConnectionsClient(any<Context>()) } returns client
        every { client.startAdvertising(any<String>(), any(), any(), any()) } returns completedTask()
        every { client.startDiscovery(any(), any(), any()) } returns completedTask()
        every { client.requestConnection(any<String>(), any(), any()) } returns completedTask()
        every { client.acceptConnection(any(), any()) } returns completedTask()
        every { client.sendPayload(any<String>(), any()) } returns completedTask()

        service = NearbyConnectionService(mockk(relaxed = true))
        callManager = mockk(relaxed = true) { every { getCurrentEndpointId() } returns ENDPOINT }
        service.setCallManager(callManager)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Conecta [id] vía discovery y devuelve los callbacks capturados para simular eventos. */
    private fun connect(id: String = ENDPOINT, statusCode: Int = ConnectionsStatusCodes.STATUS_OK): Callbacks {
        val lifecycle = slot<ConnectionLifecycleCallback>()
        val payload = slot<PayloadCallback>()
        every { client.requestConnection(any<String>(), any(), capture(lifecycle)) } returns completedTask()
        every { client.acceptConnection(any(), capture(payload)) } returns completedTask()

        service.requestConnection(id)
        lifecycle.captured.onConnectionInitiated(id, mockk<ConnectionInfo>(relaxed = true))
        lifecycle.captured.onConnectionResult(id, resolution(statusCode))
        return Callbacks(lifecycle.captured, payload.captured)
    }

    private fun resolution(code: Int) = mockk<ConnectionResolution> { every { status } returns Status(code) }

    private fun signalBytes(type: ConnectionSignalsTypes, id: String? = "sig-1") =
        byteArrayOf(0x53) + gson.toJson(ConnectionSignal(type, "", "Peer", id = id)).toByteArray()

    private fun Callbacks.receive(bytes: ByteArray, from: String = ENDPOINT) =
        payload.onPayloadReceived(from, Payload.fromBytes(bytes))

    private class Callbacks(val lifecycle: ConnectionLifecycleCallback, val payload: PayloadCallback)

    // ── Advertising / discovery ───────────────────────────────────────────────

    @Test
    fun `startAdvertising con exito pasa a ADVERTISING`() {
        var ok = false
        service.startAdvertising(onSuccess = { ok = true })

        assertTrue(ok)
        assertEquals(NearbyStatus.ADVERTISING, service.nearbyMode.value)
    }

    @Test
    fun `STATUS_ALREADY_ADVERTISING se trata como exito`() {
        every { client.startAdvertising(any<String>(), any(), any(), any()) } returns
            failedTask(ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING)
        var ok = false
        service.startAdvertising(onSuccess = { ok = true })

        assertTrue(ok)
        assertEquals(NearbyStatus.ADVERTISING, service.nearbyMode.value)
    }

    @Test
    fun `un fallo transitorio de startDiscovery se reintenta una vez`() {
        every { client.startDiscovery(any(), any(), any()) } returnsMany listOf(
            failedTask(ConnectionsStatusCodes.STATUS_RADIO_ERROR), completedTask()
        )
        var ok = false
        service.startDiscovery(onSuccess = { ok = true }, onFailure = { error("no debia fallar") })
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(ok)
        assertEquals(NearbyStatus.DISCOVERING, service.nearbyMode.value)
        verify(exactly = 2) { client.startDiscovery(any(), any(), any()) }
    }

    @Test
    fun `si el reintento tambien falla se reporta el error una sola vez`() {
        every { client.startDiscovery(any(), any(), any()) } returns
            failedTask(ConnectionsStatusCodes.STATUS_RADIO_ERROR)
        var failures = 0
        service.startDiscovery(onSuccess = {}, onFailure = { failures++ })
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, failures)
        verify(exactly = 2) { client.startDiscovery(any(), any(), any()) }
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    @Test
    fun `conexion exitosa registra el endpoint y detiene discovery y advertising`() {
        connect()

        assertEquals(NearbyStatus.CONNECTION_SUCCESS, service.nearbyMode.value)
        assertTrue(service.connectedEndpoints.value.any { it.first == ENDPOINT })
        verify { client.stopDiscovery() }
        verify { client.stopAdvertising() }
    }

    @Test
    fun `conexion rechazada pasa a REJECTED`() {
        connect(statusCode = ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED)

        assertEquals(NearbyStatus.REJECTED, service.nearbyMode.value)
        assertTrue(service.connectedEndpoints.value.isEmpty())
    }

    @Test
    fun `BUG cualquier otro codigo de error no deja el estado atascado en IN_PROGRESS`() {
        // Codigos reales de campo: IO error / timeout / bluetooth. Hoy el when() los ignora y la
        // UI se queda "conectando" para siempre: sintoma "a veces no se pueden conectar".
        listOf(
            ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR,
            ConnectionsStatusCodes.STATUS_RADIO_ERROR,
            ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT
        ).forEach { code ->
            service.resetStatus()
            connect(statusCode = code)

            assertTrue(
                "codigo $code dejo el estado en ${service.nearbyMode.value}",
                service.nearbyMode.value != NearbyStatus.IN_PROGRESS
            )
        }
    }

    @Test
    fun `BUG si requestConnection falla se informa CONNECTION_ERROR`() {
        every { client.requestConnection(any<String>(), any(), any()) } returns
            failedTask(ConnectionsStatusCodes.STATUS_RADIO_ERROR)

        service.requestConnection(ENDPOINT)

        assertEquals(NearbyStatus.CONNECTION_ERROR, service.nearbyMode.value)
    }

    @Test
    fun `desconexion del endpoint lo saca de la lista y avisa al CallManager`() {
        val cb = connect()
        cb.lifecycle.onDisconnected(ENDPOINT)

        assertTrue(service.connectedEndpoints.value.isEmpty())
        assertEquals(NearbyStatus.IDLE, service.nearbyMode.value)
        verify { callManager.onRemoteCallEnded() }
    }

    @Test
    fun `BUG desconexion de otro endpoint no cuelga la llamada activa`() {
        val cb = connect(ENDPOINT)
        cb.lifecycle.onDisconnected("otro-dispositivo")

        verify(exactly = 0) { callManager.onRemoteCallEnded() }
    }

    // ── Señalización ──────────────────────────────────────────────────────────

    @Test
    fun `INCOMING_CALL queda disponible en receivedSignals con el senderId del endpoint`() {
        val cb = connect()
        cb.receive(signalBytes(ConnectionSignalsTypes.INCOMING_CALL))

        val signal = service.receivedSignals.value
        assertNotNull(signal)
        assertEquals(ConnectionSignalsTypes.INCOMING_CALL, signal!!.type)
        assertEquals(ENDPOINT, signal.senderId)
    }

    @Test
    fun `senales de aceptar rechazar y terminar llegan al CallManager`() {
        val cb = connect()
        cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ACCEPTED, "a"))
        cb.receive(signalBytes(ConnectionSignalsTypes.CALL_REJECTED, "b"))
        cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ENDED, "c"))

        verify(exactly = 1) { callManager.onRemoteCallAccepted() }
        verify(exactly = 1) { callManager.onRemoteCallRejected() }
        verify(exactly = 1) { callManager.onRemoteCallEnded() }
    }

    @Test
    fun `un reintento duplicado de la misma senal se procesa una sola vez`() {
        val cb = connect()
        repeat(3) { cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ENDED, id = "mismo-id")) }

        verify(exactly = 1) { callManager.onRemoteCallEnded() }
    }

    @Test
    fun `senales con distinto id no se deduplican`() {
        val cb = connect()
        cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ENDED, id = "uno"))
        cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ENDED, id = "dos"))

        verify(exactly = 2) { callManager.onRemoteCallEnded() }
    }

    @Test
    fun `senal de un peer antiguo sin id siempre se procesa`() {
        val cb = connect()
        repeat(2) { cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ENDED, id = null)) }

        verify(exactly = 2) { callManager.onRemoteCallEnded() }
    }

    @Test
    fun `la deduplicacion recuerda solo las ultimas 32 senales`() {
        val cb = connect()
        cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ENDED, id = "viejo"))
        repeat(32) { cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ACCEPTED, id = "n$it")) }
        cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ENDED, id = "viejo"))

        verify(exactly = 2) { callManager.onRemoteCallEnded() }
    }

    @Test
    fun `payload con JSON corrupto no rompe el servicio`() {
        val cb = connect()
        cb.receive(byteArrayOf(0x53) + "{no es json".toByteArray())
        cb.receive(byteArrayOf())

        cb.receive(signalBytes(ConnectionSignalsTypes.CALL_ACCEPTED, "ok"))
        verify(exactly = 1) { callManager.onRemoteCallAccepted() }
    }

    @Test
    fun `sendCallSignal reintenta hasta 3 veces si sendPayload falla`() {
        connect()
        every { client.sendPayload(any<String>(), any()) } returns failedTask(ConnectionsStatusCodes.STATUS_ERROR)

        service.sendCallSignal(ENDPOINT, ConnectionSignalsTypes.CALL_ENDED)
        dispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 4) { client.sendPayload(ENDPOINT, any()) } // 1 intento + 3 reintentos
    }

    @Test
    fun `sendCallSignal no reintenta si el endpoint ya se desconecto`() {
        every { client.sendPayload(any<String>(), any()) } returns failedTask(ConnectionsStatusCodes.STATUS_ERROR)

        service.sendCallSignal("desconocido", ConnectionSignalsTypes.CALL_ENDED)
        dispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { client.sendPayload("desconocido", any()) }
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    @Test
    fun `sendAudioFrame arma TAG seq y paquete Opus en un solo payload`() {
        val sent = slot<Payload>()
        every { client.sendPayload(any<String>(), capture(sent)) } returns completedTask()

        service.sendAudioFrame(ENDPOINT, seq = 0x0102, opus = byteArrayOf(9, 8, 7))

        assertArrayEquals(byteArrayOf(0x41, 0x01, 0x02, 9, 8, 7), sent.captured.asBytes())
    }

    @Test
    fun `sendAudioFrame respeta opusLen y no envia la cola del buffer reutilizado`() {
        val sent = slot<Payload>()
        every { client.sendPayload(any<String>(), capture(sent)) } returns completedTask()

        service.sendAudioFrame(ENDPOINT, seq = 1, opus = byteArrayOf(5, 6, 99, 99), opusLen = 2)

        assertArrayEquals(byteArrayOf(0x41, 0x00, 0x01, 5, 6), sent.captured.asBytes())
    }

    @Test
    fun `el numero de secuencia 0xFFFF se codifica sin signo`() {
        val sent = slot<Payload>()
        every { client.sendPayload(any<String>(), capture(sent)) } returns completedTask()

        service.sendAudioFrame(ENDPOINT, seq = 0xFFFF, opus = byteArrayOf(1))

        val bytes = sent.captured.asBytes()!!
        assertEquals(0xFF, bytes[1].toInt() and 0xFF)
        assertEquals(0xFF, bytes[2].toInt() and 0xFF)
    }

    @Test
    fun `frame de audio recibido llega al CallManager sin el tag`() {
        val cb = connect()
        cb.receive(byteArrayOf(0x41, 0x00, 0x07, 1, 2, 3))

        val frame = slot<ByteArray>()
        verify { callManager.onIncomingAudioFrame(capture(frame)) }
        assertArrayEquals(byteArrayOf(0x00, 0x07, 1, 2, 3), frame.captured)
    }

    @Test
    fun `BUG un fallo puntual de transferencia de un frame no cuelga la llamada`() {
        // Con ~25 payloads/seg en radios saturados (gama baja) un FAILURE aislado es normal; tratarlo
        // como fin de llamada tira la sesion entera. Criterio de diseno: tolerar fallos aislados.
        val cb = connect()
        cb.payload.onPayloadTransferUpdate(
            ENDPOINT,
            PayloadTransferUpdate.Builder().setStatus(PayloadTransferUpdate.Status.FAILURE).build()
        )

        verify(exactly = 0) { callManager.onRemoteCallEnded() }
    }

    private companion object {
        const val ENDPOINT = "endpoint-1"
    }
}
