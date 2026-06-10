package com.XDurango.VoLlama.MainMenu

import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import com.google.android.gms.nearby.connection.ConnectionInfo


data class MainMenuUiState(
    val showBottomSheet: Boolean = false,
    val selectedIndex: Int = 0,
    val endpointName: String = "",

    val discoveredEndpoints:
    List<NearbyConnectionService.DiscoveredEndpoint> = emptyList(),

    val connectedEndpoints: Set<Pair<String, ConnectionInfo>> = emptySet(),


    val nearbyStatus: NearbyConnectionService.NearbyStatus =
        NearbyConnectionService.NearbyStatus.IDLE
)


sealed interface MainEvent {

    data object StartConnection : MainEvent
    data object CloseBottomSheet : MainEvent
    data object RestartConnection: MainEvent

    data class ChangeMode(val index: Int) : MainEvent
    data class Connect(val endpointId: String) : MainEvent

    data class Disconnect(val endpointId: String) : MainEvent
}
