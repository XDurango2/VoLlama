package com.XDurango.VoLlama

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.google.android.gms.nearby.connection.ConnectionInfo
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SegmentedButton
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import android.util.Log
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box

@Composable
fun OnConnectionInitiatedDialog(
    endpointId: String,
    info: ConnectionInfo,
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit
) {
    AlertDialog(
        title = { Text("Confirme Conexión a Dispositivo") },
        text = { Text("¿Está seguro que desea conectarse a este dispositivo?\n ${info.endpointName} - Codigo de confirmacion: ${info.authenticationToken}" ) },
        onDismissRequest = onDismissRequest,  // ← Sin paréntesis, solo la referencia
        confirmButton = {
            OutlinedButton(onClick = onConfirmation) {
                Text("Aceptar")
            }
        },
        dismissButton = {
            Button(onClick = onDismissRequest) {
                Text("Cancelar")
            }
        }
    )
}
@Composable
fun ConnectionErrorDialog(
    connectionStatus: NearbyConnectionService.NearbyStatus,
    onConfirmation: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onConfirmation, // ← Esto faltaba
        title = { Text("Hubo un Problema") }, // ← Debe ser un @Composable
        text = { // ← Debe ser un @Composable
            Text(
                if (connectionStatus == NearbyConnectionService.NearbyStatus.REJECTED) {
                    "Conexión Rechazada"
                } else if (connectionStatus == NearbyConnectionService.NearbyStatus.CONNECTION_ERROR) {
                    "Hubo un error de conexión, intente nuevamente"
                } else {
                    "Error desconocido"
                }
            )
        },
        confirmButton = {
            Button(onClick = onConfirmation) {
                Text("Aceptar")
            }
        }
    )
}


@Composable
fun DiscoveredDeviceCard(
    endpoint: NearbyConnectionService.DiscoveredEndpoint,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    endpoint.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    endpoint.endpointId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onClick) {
                Text("Conectar")
            }
        }
    }
}

@Composable
fun ConnectedDeviceCard(
    endpointId: String,
    isStreaming: Boolean,
    isReceiving: Boolean,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onDisconnect: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Conectado",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        endpointId,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                IconButton(onClick = onDisconnect) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Desconectar"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = if (isStreaming) onStopStreaming else onStartStreaming,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isStreaming) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                } else ButtonDefaults.buttonColors()
            ) {
                Text(if (isStreaming) "Detener Audio" else "Hablar")
            }

            if (isReceiving) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Recibiendo audio...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottom(selectedIndex: Int,
                onIndexChange: (Int) -> Unit,
                discoveredDevices: List<NearbyConnectionService.DiscoveredEndpoint>,
                onDeviceClick: (String) -> Unit){
    val options = listOf("Quiero conectarme!", "Espero una conexión")
    val deviceCount = discoveredDevices.size
    // 🔍 DEBUG: Log cada recomposición
    LaunchedEffect(deviceCount) {
        Log.d("ModalBottom", "🎨 Recomposed! Device count: $deviceCount")
        discoveredDevices.forEach {
            Log.d("ModalBottom", "  📱 ${it.name} - ${it.endpointId}")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "¡Estás listo para conectarte!",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    onClick = { onIndexChange(index) },
                    selected = index == selectedIndex,
                    label = {
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        when (selectedIndex) {
            0 -> {
                // Modo Discovery
                Text(
                    text = "Dispositivos disponibles ($deviceCount)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "apareces como ",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))


                if (discoveredDevices.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Buscando dispositivos...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(
                            items = discoveredDevices,
                            key = { device -> device.endpointId }
                        ) { device ->
                            DiscoveredDeviceCard(
                                endpoint = device,
                                onClick = { onDeviceClick(device.endpointId) }
                            )
                        }
                    }
                }
            }
            1 -> {
                // Modo Advertising
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Esperando que alguien se conecte...",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Otros dispositivos pueden verte ahora",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    viewModel: ViewModelApp = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val toastMessage by viewModel.toastMessage.observeAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // ---------- ONE SHOT UI EFFECTS ----------
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToastMessage()
        }
    }

    // ---------- ERROR DIALOG ----------
    if (
        state.nearbyStatus == NearbyConnectionService.NearbyStatus.REJECTED ||
        state.nearbyStatus == NearbyConnectionService.NearbyStatus.CONNECTION_ERROR
    ) {
        ConnectionErrorDialog(
            connectionStatus = state.nearbyStatus,
            onConfirmation = { viewModel.resetNearbyStatus() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VoLlama - Voice Chat") }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Iniciar conexión") },
                icon = { Icon(Icons.Default.Add, null) },
                onClick = {
                    viewModel.onEvent(MainEvent.StartConnection)
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->

        if (state.connectedEndpoints.isNotEmpty()) {

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                item {
                    Text(
                        "Dispositivos Conectados",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }

                items(
                    items = state.connectedEndpoints.toList(),
                    key = { it }
                ) { endpointId ->
                    ConnectedDeviceCard(
                        endpointId = endpointId,
                        isStreaming = state.isStreamingAudio,
                        isReceiving = state.isReceivingAudio,
                        onStartStreaming = {
                            viewModel.onEvent(
                                MainEvent.StartStreaming(endpointId)
                            )
                        },
                        onStopStreaming = {
                            viewModel.onEvent(MainEvent.StopStreaming)
                        },
                        onDisconnect = {
                            viewModel.onEvent(MainEvent.Disconnect)
                        }
                    )
                }
            }

        } else {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No hay dispositivos conectados",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "Presiona el botón + para comenzar",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    // ---------- CONNECTION CONFIRMATION ----------
    viewModel.showConnectionDialog.observeAsState().value?.let { (id, info) ->
        OnConnectionInitiatedDialog(
            endpointId = id,
            info = info,
            onDismissRequest = { viewModel.rejectConnection(id) },
            onConfirmation = { viewModel.acceptConnection(id) }
        )
    }

    // ---------- MODAL BOTTOM SHEET ----------
    if (state.showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.onEvent(MainEvent.CloseBottomSheet)
            }
        ) {
            ModalBottom(
                selectedIndex = state.selectedIndex,
                discoveredDevices = state.discoveredEndpoints,
                onIndexChange = {
                    viewModel.onEvent(MainEvent.ChangeMode(it))
                },
                onDeviceClick = {
                    viewModel.onEvent(MainEvent.Connect(it))
                }
            )
        }
    }
}



