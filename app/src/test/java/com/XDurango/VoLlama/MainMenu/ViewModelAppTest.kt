package com.XDurango.VoLlama.MainMenu

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import com.XDurango.VoLlama.Nearby.NearbyConnectionService.ConnectionSignalsTypes
import com.XDurango.VoLlama.Nearby.NearbyConnectionService.NearbyStatus
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Capa de ViewModel del menú principal: eventos de UI → Nearby y sincronización de estado. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelAppTest {

    @get:Rule val instantTaskExecutor = InstantTaskExecutorRule()

    private val discovered = MutableStateFlow<List<Pair<String, DiscoveredEndpointInfo>>>(emptyList())
    private val connected = MutableStateFlow<Set<Pair<String, ConnectionInfo>>>(emptySet())
    private val mode = MutableStateFlow(NearbyStatus.IDLE)
    private val signals = MutableStateFlow<NearbyConnectionService.ConnectionSignal?>(null)

    private val nearby = mockk<NearbyConnectionService>(relaxed = true) {
        every { discoveredEndpoints } returns discovered
        every { connectedEndpoints } returns connected
        every { nearbyMode } returns mode
        every { receivedSignals } returns signals
        every { showConnectionDialog } returns MutableLiveData()
        every { endpoint } returns "Google - Pixel"
    }

    private lateinit var vm: ViewModelApp

    @Before fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = ViewModelApp(nearby, mockk<Application>(relaxed = true))
    }

    @After fun tearDown() { kotlinx.coroutines.Dispatchers.resetMain() }

    private fun endpointInfo(name: String) = mockk<DiscoveredEndpointInfo> {
        every { endpointName } returns name
        every { serviceId } returns NearbyConnectionService.SERVICE_ID
    }

    // ── Estado inicial y sincronización ───────────────────────────────────────

    @Test fun `estado inicial`() {
        val s = vm.uiState.value
        assertFalse(s.showBottomSheet)
        assertEquals(0, s.selectedIndex)
        assertEquals(NearbyStatus.IDLE, s.nearbyStatus)
        assertEquals("Google - Pixel", s.endpointName)
    }

    @Test fun `endpoints descubiertos se reflejan mapeados en el uiState`() {
        discovered.value = listOf("a" to endpointInfo("Galaxy A10"), "b" to endpointInfo("Moto G"))

        val list = vm.uiState.value.discoveredEndpoints
        assertEquals(listOf("a", "b"), list.map { it.endpointId })
        assertEquals(listOf("Galaxy A10", "Moto G"), list.map { it.name })
    }

    @Test fun `un endpoint que se pierde desaparece de la lista`() {
        discovered.value = listOf("a" to endpointInfo("A"), "b" to endpointInfo("B"))
        discovered.value = listOf("b" to endpointInfo("B"))

        assertEquals(listOf("b"), vm.uiState.value.discoveredEndpoints.map { it.endpointId })
    }

    @Test fun `cambios de estado de Nearby y de endpoints conectados se propagan`() {
        mode.value = NearbyStatus.CONNECTION_SUCCESS
        connected.value = setOf("a" to mockk<ConnectionInfo>())

        assertEquals(NearbyStatus.CONNECTION_SUCCESS, vm.uiState.value.nearbyStatus)
        assertEquals(1, vm.uiState.value.connectedEndpoints.size)
    }

    // ── Eventos ───────────────────────────────────────────────────────────────

    @Test fun `StartConnection abre el sheet y empieza a buscar`() {
        vm.onEvent(MainEvent.StartConnection)

        assertTrue(vm.uiState.value.showBottomSheet)
        verify { nearby.startDiscovery(any(), any()) }
    }

    @Test fun `exito y fallo de discovery generan toast`() {
        val ok = slot<() -> Unit>()
        val fail = slot<(Exception) -> Unit>()
        every { nearby.startDiscovery(capture(ok), capture(fail)) } returns Unit

        vm.onEvent(MainEvent.StartConnection)
        ok.captured.invoke()
        assertEquals("Buscando dispositivos", vm.toastMessage.value)

        fail.captured.invoke(RuntimeException())
        assertEquals("Error en búsqueda", vm.toastMessage.value)
    }

    @Test fun `ChangeMode 0 busca y deja de anunciar, 1 hace lo contrario`() {
        vm.onEvent(MainEvent.ChangeMode(1))
        verify { nearby.stopDiscovery() }
        verify { nearby.startAdvertising(any(), any()) }
        assertEquals(1, vm.uiState.value.selectedIndex)

        vm.onEvent(MainEvent.ChangeMode(0))
        verify { nearby.stopAdvertising() }
        verify { nearby.startDiscovery(any(), any()) }
        assertEquals(0, vm.uiState.value.selectedIndex)
    }

    @Test fun `CloseBottomSheet cierra y detiene discovery y advertising`() {
        vm.onEvent(MainEvent.StartConnection)
        vm.onEvent(MainEvent.CloseBottomSheet)

        assertFalse(vm.uiState.value.showBottomSheet)
        verify { nearby.stopDiscovery() }
        verify { nearby.stopAdvertising() }
    }

    @Test fun `CloseBottomSheet se ignora mientras hay una conexion en progreso`() {
        vm.onEvent(MainEvent.StartConnection)
        mode.value = NearbyStatus.IN_PROGRESS
        vm.onEvent(MainEvent.CloseBottomSheet)

        assertTrue(vm.uiState.value.showBottomSheet)
    }

    @Test fun `tras un error de conexion el sheet si se puede cerrar`() {
        // Regresion: con el estado atascado en IN_PROGRESS el usuario no podia cerrar el sheet.
        vm.onEvent(MainEvent.StartConnection)
        mode.value = NearbyStatus.CONNECTION_ERROR
        vm.onEvent(MainEvent.CloseBottomSheet)

        assertFalse(vm.uiState.value.showBottomSheet)
    }

    @Test fun `Connect pide la conexion y avisa`() {
        vm.onEvent(MainEvent.Connect("ep"))

        verify { nearby.requestConnection("ep") }
        assertEquals("Intentando conectar...", vm.toastMessage.value)
    }

    @Test fun `Disconnect desconecta solo ese endpoint`() {
        vm.onEvent(MainEvent.Disconnect("ep"))

        verify { nearby.disconnectFromEndpoint("ep") }
        assertEquals("Desconectado", vm.toastMessage.value)
    }

    @Test fun `RestartConnection limpia y vuelve a buscar`() {
        vm.onEvent(MainEvent.RestartConnection)

        verify(ordering = io.mockk.Ordering.ORDERED) {
            nearby.cleanup()
            nearby.startDiscovery(any(), any())
        }
    }

    // ── Delegaciones y ciclo de vida ──────────────────────────────────────────

    @Test fun `accept reject y señales se delegan en el servicio`() {
        vm.acceptConnection("ep")
        vm.rejectConnection("ep")
        vm.sendConnectionSignal("ep", ConnectionSignalsTypes.CALL_ACCEPTED)
        vm.clearConnectionSignalReceived()
        vm.resetNearbyStatus()

        verify { nearby.acceptConnection("ep") }
        verify { nearby.rejectConnection("ep") }
        verify { nearby.sendCallSignal("ep", ConnectionSignalsTypes.CALL_ACCEPTED) }
        verify { nearby.clearIncomingCallSignal() }
        verify { nearby.resetStatus() }
    }

    @Test fun `clearToastMessage limpia el toast`() {
        vm.onEvent(MainEvent.Connect("ep"))
        vm.clearToastMessage()

        assertNull(vm.toastMessage.value)
    }

    @Test fun `callStatus expone las señales recibidas`() {
        assertNull(vm.callStatus.value)
        val signal = NearbyConnectionService.ConnectionSignal(ConnectionSignalsTypes.INCOMING_CALL, "ep", "Pixel")
        signals.value = signal

        assertEquals(signal, vm.callStatus.value)
    }
}
