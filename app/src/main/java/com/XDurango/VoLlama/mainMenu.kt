package com.XDurango.VoLlama


data class MainMenuUiState(
    val showBottomSheet: Boolean = false,
    val selectedIndex: Int = 0,

    val discoveredEndpoints:
    List<NearbyConnectionService.DiscoveredEndpoint> = emptyList(),

    val connectedEndpoints: Set<String> = emptySet(),

    val isStreamingAudio: Boolean = false,
    val isReceivingAudio: Boolean = false,

    val nearbyStatus: NearbyConnectionService.NearbyStatus =
        NearbyConnectionService.NearbyStatus.IDLE
)


sealed interface MainEvent {

    data object StartConnection : MainEvent
    data object CloseBottomSheet : MainEvent

    data class ChangeMode(val index: Int) : MainEvent
    data class Connect(val endpointId: String) : MainEvent

    data class StartStreaming(val endpointId: String) : MainEvent
    data object StopStreaming : MainEvent

    data object Disconnect : MainEvent
}
