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
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
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
import org.junit.Assert.assertNull
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

    // ── Estado obsoleto entre intentos de conexion ────────────────────────────

    /** Arranca discovery y devuelve el callback para simular endpoints encontrados/perdidos. */
    private fun startDiscoveryCapturing(): EndpointDiscoveryCallback {
        val cb = slot<EndpointDiscoveryCallback>()
        every { client.startDiscovery(any(), capture(cb), any()) } returns completedTask()
        service.startDiscovery(onSuccess = {}, onFailure = {})
        return cb.captured
    }

    private fun found(cb: EndpointDiscoveryCallback, id: String) =
        cb.onEndpointFound(id, mockk<DiscoveredEndpointInfo>(relaxed = true))

    @Test
    fun `BUG al detener discovery la lista de dispositivos descubiertos se vacia`() {
        // Tras conectar se llama stopDiscovery y Nearby ya no emite onEndpointLost: la lista
        // conservaba los endpoints (con IDs ya invalidos) del primer intento.
        val discovery = startDiscoveryCapturing()
        found(discovery, "viejo-1")
        assertEquals(1, service.discoveredEndpoints.value.size)

        service.stopDiscovery()

        assertTrue(service.discoveredEndpoints.value.isEmpty())
    }

    @Test
    fun `BUG conectar y reconectar no arrastra los dispositivos del primer intento`() {
        val first = startDiscoveryCapturing()
        found(first, "viejo-1")
        connect("viejo-1") // handleConnectionResult(OK) detiene discovery
        service.disconnectFromEndpoint("viejo-1")

        val second = startDiscoveryCapturing()
        assertTrue(service.discoveredEndpoints.value.isEmpty())
        found(second, "nuevo-2")

        assertEquals(listOf("nuevo-2"), service.discoveredEndpoints.value.map { it.first })
    }

    @Test
    fun `startDiscovery descarta endpoints de una sesion previa sin stop intermedio`() {
        found(startDiscoveryCapturing(), "viejo-1")

        startDiscoveryCapturing()

        assertTrue(service.discoveredEndpoints.value.isEmpty())
    }

    // ── Handshake que no termina ──────────────────────────────────────────────

    @Test
    fun `BUG si el otro lado nunca responde el handshake expira con CONNECTION_ERROR`() {
        // Sintoma: presionas conectar y el popup no aparece en el otro dispositivo. Nearby no avisa.
        service.requestConnection(ENDPOINT)

        dispatcher.scheduler.advanceTimeBy(20_001)
        dispatcher.scheduler.runCurrent()

        assertEquals(NearbyStatus.CONNECTION_ERROR, service.nearbyMode.value)
        verify { client.disconnectFromEndpoint(ENDPOINT) }
    }

    @Test
    fun `el handshake no expira antes del plazo`() {
        service.requestConnection(ENDPOINT)

        dispatcher.scheduler.advanceTimeBy(19_000)
        dispatcher.scheduler.runCurrent()

        assertTrue(service.nearbyMode.value != NearbyStatus.CONNECTION_ERROR)
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
    }

    @Test
    fun `una conexion exitosa cancela el watchdog y no se marca error despues`() {
        connect()

        dispatcher.scheduler.advanceTimeBy(30_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(NearbyStatus.CONNECTION_SUCCESS, service.nearbyMode.value)
        assertTrue(service.connectedEndpoints.value.any { it.first == ENDPOINT })
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
    }

    @Test
    fun `un fallo inmediato de requestConnection cancela el watchdog`() {
        every { client.requestConnection(any<String>(), any(), any()) } returns
            failedTask(ConnectionsStatusCodes.STATUS_RADIO_ERROR)
        service.requestConnection(ENDPOINT)
        service.resetStatus()

        dispatcher.scheduler.advanceTimeBy(30_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(NearbyStatus.IDLE, service.nearbyMode.value)
    }

    @Test
    fun `STATUS_OK sin info pendiente corta el enlace en vez de dejarlo a medias`() {
        // Conectado en Nearby pero invisible para la app => "conexion rota": sin audio ni llamadas.
        val lifecycle = slot<ConnectionLifecycleCallback>()
        every { client.requestConnection(any<String>(), any(), capture(lifecycle)) } returns completedTask()
        service.requestConnection(ENDPOINT)

        lifecycle.captured.onConnectionResult(ENDPOINT, resolution(ConnectionsStatusCodes.STATUS_OK))

        assertTrue(service.connectedEndpoints.value.isEmpty())
        assertEquals(NearbyStatus.CONNECTION_ERROR, service.nearbyMode.value)
        verify { client.disconnectFromEndpoint(ENDPOINT) }
    }

    // ── Popup de aceptacion (advertiser) ──────────────────────────────────────

    private fun startAdvertisingCapturing(): ConnectionLifecycleCallback {
        val cb = slot<ConnectionLifecycleCallback>()
        every { client.startAdvertising(any<String>(), any(), capture(cb), any()) } returns completedTask()
        service.startAdvertising()
        return cb.captured
    }

    @Test
    fun `una conexion entrante muestra el popup con el endpoint correcto`() {
        val advertising = startAdvertisingCapturing()

        advertising.onConnectionInitiated(ENDPOINT, mockk(relaxed = true))

        assertEquals(ENDPOINT, service.showConnectionDialog.value?.first)
        assertEquals(NearbyStatus.IN_PROGRESS, service.nearbyMode.value)
    }

    @Test
    fun `BUG si nadie responde al popup, expira y se limpia el dialogo obsoleto`() {
        val advertising = startAdvertisingCapturing()
        advertising.onConnectionInitiated(ENDPOINT, mockk(relaxed = true))

        dispatcher.scheduler.advanceTimeBy(20_001)
        dispatcher.scheduler.runCurrent()

        assertNull(service.showConnectionDialog.value)
        assertEquals(NearbyStatus.CONNECTION_ERROR, service.nearbyMode.value)
    }

    @Test
    fun `BUG un handshake fallido limpia el popup de ese endpoint`() {
        val advertising = startAdvertisingCapturing()
        advertising.onConnectionInitiated(ENDPOINT, mockk(relaxed = true))

        advertising.onConnectionResult(ENDPOINT, resolution(ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR))

        assertNull(service.showConnectionDialog.value)
    }

    @Test
    fun `la desconexion del endpoint limpia su popup pendiente`() {
        val advertising = startAdvertisingCapturing()
        advertising.onConnectionInitiated(ENDPOINT, mockk(relaxed = true))

        advertising.onDisconnected(ENDPOINT)

        assertNull(service.showConnectionDialog.value)
    }

    @Test
    fun `el fallo de un endpoint no borra el popup de otro dispositivo`() {
        val advertising = startAdvertisingCapturing()
        advertising.onConnectionInitiated("otro", mockk(relaxed = true))

        advertising.onDisconnected(ENDPOINT)

        assertEquals("otro", service.showConnectionDialog.value?.first)
    }

    // ── Enlace roto detectado al señalizar ────────────────────────────────────

    @Test
    fun `BUG si la señal no sale tras los reintentos el endpoint se da por roto`() {
        // Sintoma: llamas y el otro lado nunca recibe el popup de llamada entrante.
        connect()
        every { client.sendPayload(any<String>(), any()) } returns failedTask(ConnectionsStatusCodes.STATUS_ERROR)

        service.sendCallSignal(ENDPOINT, ConnectionSignalsTypes.INCOMING_CALL)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(service.connectedEndpoints.value.isEmpty())
        assertEquals(NearbyStatus.CONNECTION_ERROR, service.nearbyMode.value)
        verify { client.disconnectFromEndpoint(ENDPOINT) }
    }

    @Test
    fun `un fallo transitorio que se recupera en un reintento no desconecta`() {
        connect()
        every { client.sendPayload(any<String>(), any()) } returnsMany listOf(
            failedTask(ConnectionsStatusCodes.STATUS_ERROR), completedTask()
        )

        service.sendCallSignal(ENDPOINT, ConnectionSignalsTypes.INCOMING_CALL)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(service.connectedEndpoints.value.any { it.first == ENDPOINT })
        assertEquals(NearbyStatus.CONNECTION_SUCCESS, service.nearbyMode.value)
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

    // ── Desconexion y limpieza ────────────────────────────────────────────────

    @Test
    fun `desconectar el ultimo endpoint vuelve a IDLE`() {
        connect()
        service.disconnectFromEndpoint(ENDPOINT)

        assertTrue(service.connectedEndpoints.value.isEmpty())
        assertEquals(NearbyStatus.IDLE, service.nearbyMode.value)
        verify { client.disconnectFromEndpoint(ENDPOINT) }
    }

    @Test
    fun `desconectar un endpoint no tumba el estado mientras quede otro conectado`() {
        connect("a")
        connect("b")
        service.disconnectFromEndpoint("a")

        assertEquals(setOf("b"), service.connectedEndpoints.value.map { it.first }.toSet())
        assertEquals(NearbyStatus.CONNECTION_SUCCESS, service.nearbyMode.value)
    }

    @Test
    fun `si el cliente falla al desconectar el endpoint se saca igual de la lista`() {
        connect()
        every { client.disconnectFromEndpoint(any()) } throws IllegalStateException("boom")

        service.disconnectFromEndpoint(ENDPOINT)

        assertTrue(service.connectedEndpoints.value.isEmpty())
        assertEquals(NearbyStatus.IDLE, service.nearbyMode.value)
    }

    @Test
    fun `disconnectFromAll limpia conectados, descubiertos y senal pendiente`() {
        val cb = connect()
        found(startDiscoveryCapturing(), "visto")
        cb.receive(signalBytes(ConnectionSignalsTypes.INCOMING_CALL))
        assertNotNull(service.receivedSignals.value)

        service.disconnectFromAll()

        assertTrue(service.connectedEndpoints.value.isEmpty())
        assertTrue(service.discoveredEndpoints.value.isEmpty())
        assertNull(service.receivedSignals.value)
        verify { client.stopAllEndpoints() }
    }

    @Test
    fun `disconnectFromAll cancela los handshakes en curso`() {
        service.requestConnection(ENDPOINT)
        service.disconnectFromAll()

        dispatcher.scheduler.advanceTimeBy(30_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(NearbyStatus.IDLE, service.nearbyMode.value)
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
    }

    @Test
    fun `cleanup corta todas las conexiones`() {
        connect()
        service.cleanup()

        assertTrue(service.connectedEndpoints.value.isEmpty())
        verify { client.stopAllEndpoints() }
    }

    // ── Aceptar / rechazar desde el popup ─────────────────────────────────────

    @Test
    fun `aceptar la conexion cierra el popup y se lo pide a Nearby`() {
        val advertising = startAdvertisingCapturing()
        advertising.onConnectionInitiated(ENDPOINT, mockk(relaxed = true))
        assertNotNull(service.showConnectionDialog.value)

        service.acceptConnection(ENDPOINT)

        assertNull(service.showConnectionDialog.value)
        verify { client.acceptConnection(ENDPOINT, any()) }
    }

    @Test
    fun `si Nearby falla al aceptar el popup no queda colgado`() {
        val advertising = startAdvertisingCapturing()
        advertising.onConnectionInitiated(ENDPOINT, mockk(relaxed = true))
        every { client.acceptConnection(any(), any()) } returns failedTask(ConnectionsStatusCodes.STATUS_ERROR)

        service.acceptConnection(ENDPOINT)

        assertNull(service.showConnectionDialog.value)
    }

    @Test
    fun `rechazar la conexion cierra el popup y se lo comunica a Nearby`() {
        val advertising = startAdvertisingCapturing()
        advertising.onConnectionInitiated(ENDPOINT, mockk(relaxed = true))

        service.rejectConnection(ENDPOINT)

        assertNull(service.showConnectionDialog.value)
        verify { client.rejectConnection(ENDPOINT) }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    @Test
    fun `un endpoint encontrado dos veces aparece una sola vez y al perderse se quita`() {
        val discovery = startDiscoveryCapturing()
        found(discovery, "x")
        found(discovery, "x")
        assertEquals(1, service.discoveredEndpoints.value.size)

        discovery.onEndpointLost("x")
        assertTrue(service.discoveredEndpoints.value.isEmpty())
    }

    @Test
    fun `perder un endpoint desconocido no rompe la lista`() {
        val discovery = startDiscoveryCapturing()
        found(discovery, "x")

        discovery.onEndpointLost("otro")

        assertEquals(listOf("x"), service.discoveredEndpoints.value.map { it.first })
    }

    // ── Resultados de conexion poco comunes ───────────────────────────────────

    @Test
    fun `STATUS_ALREADY_CONNECTED_TO_ENDPOINT registra el endpoint como conectado`() {
        connect(statusCode = ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT)

        assertEquals(NearbyStatus.CONNECTION_SUCCESS, service.nearbyMode.value)
        assertTrue(service.connectedEndpoints.value.any { it.first == ENDPOINT })
    }

    @Test
    fun `requestConnection a un endpoint ya conectado no se marca como error`() {
        every { client.requestConnection(any<String>(), any(), any()) } returns
            failedTask(ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT)

        service.requestConnection(ENDPOINT)

        assertEquals(NearbyStatus.IDLE, service.nearbyMode.value)
    }

    // ── Rachas de fallos de payload ───────────────────────────────────────────

    private fun PayloadCallback.transfer(status: Int, from: String = ENDPOINT) =
        onPayloadTransferUpdate(from, PayloadTransferUpdate.Builder().setStatus(status).build())

    @Test
    fun `una racha de 10 fallos seguidos del endpoint de la llamada la termina`() {
        val cb = connect()
        repeat(9) { cb.payload.transfer(PayloadTransferUpdate.Status.FAILURE) }
        verify(exactly = 0) { callManager.onRemoteCallEnded() }

        cb.payload.transfer(PayloadTransferUpdate.Status.FAILURE)

        verify(exactly = 1) { callManager.onRemoteCallEnded() }
    }

    @Test
    fun `un envio exitoso reinicia la racha de fallos`() {
        val cb = connect()
        repeat(9) { cb.payload.transfer(PayloadTransferUpdate.Status.FAILURE) }
        cb.payload.transfer(PayloadTransferUpdate.Status.SUCCESS)
        repeat(9) { cb.payload.transfer(PayloadTransferUpdate.Status.FAILURE) }

        verify(exactly = 0) { callManager.onRemoteCallEnded() }
    }

    @Test
    fun `los fallos de otro endpoint no cuentan para la llamada`() {
        val cb = connect()
        repeat(30) { cb.payload.transfer(PayloadTransferUpdate.Status.FAILURE, from = "otro") }

        verify(exactly = 0) { callManager.onRemoteCallEnded() }
    }

    // ── Payloads invalidos ────────────────────────────────────────────────────

    @Test
    fun `payload vacio o con tag desconocido se ignora`() {
        val cb = connect()
        cb.receive(byteArrayOf())
        cb.receive(byteArrayOf(0x7F, 1, 2, 3))

        verify(exactly = 0) { callManager.onIncomingAudioFrame(any()) }
        assertNull(service.receivedSignals.value)
    }

    @Test
    fun `payloads de tipo STREAM o FILE se ignoran sin romper el servicio`() {
        val cb = connect()
        cb.payload.onPayloadReceived(ENDPOINT, mockk<Payload>(relaxed = true) { every { type } returns Payload.Type.STREAM })
        cb.payload.onPayloadReceived(ENDPOINT, mockk<Payload>(relaxed = true) { every { type } returns Payload.Type.FILE })

        verify(exactly = 0) { callManager.onIncomingAudioFrame(any()) }
    }

    @Test
    fun `si sendPayload lanza al enviar audio el servicio no se cae`() {
        every { client.sendPayload(any<String>(), any()) } throws IllegalStateException("boom")

        service.sendAudioFrame(ENDPOINT, seq = 1, opus = byteArrayOf(1, 2))
    }

    private companion object {
        const val ENDPOINT = "endpoint-1"
    }
}
