package com.XDurango.VoLlama.MainMenu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.ConnectWithoutContact
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.XDurango.VoLlama.CallMode.CallModeActivity
import com.XDurango.VoLlama.CallMode.WalkieTalkieActivity
import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import com.XDurango.VoLlama.MainMenu.ViewModelApp
import com.XDurango.VoLlama.ui.theme.VoLlamaTheme
import com.google.android.gms.nearby.connection.ConnectionInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlin.time.Duration.Companion.milliseconds
private const val TIMEOUT_SECONDS = 7

/**
 * Dialog de confirmación de conexión con countdown.
 * Si el usuario no responde en [TIMEOUT_SECONDS] segundos,
 * se llama [onTimeout] automáticamente (que debe rechazar la conexión).
 */
@Composable
fun OnConnectionInitiatedDialog(
    info: ConnectionInfo,
    onConfirmation: () -> Unit,
    onDismissRequest: () -> Unit,
    onTimeout: () -> Unit = onDismissRequest
) {
    var secondsLeft by remember { mutableIntStateOf(TIMEOUT_SECONDS) }
    var progress by remember { mutableFloatStateOf(1f) }
    var decided by remember { mutableStateOf(false) }

    // Animación fluida del círculo
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 950),
        label = "connection_timer"
    )

    // Countdown — se cancela solo si el usuario ya tomó una decisión
    LaunchedEffect(Unit) {
        for (second in TIMEOUT_SECONDS downTo 1) {
            secondsLeft = second
            progress = second.toFloat() / TIMEOUT_SECONDS
            delay(1000.milliseconds)
            if (decided) return@LaunchedEffect
        }
        if (!decided) {
            decided = true
            onTimeout()
        }
    }

    Dialog(onDismissRequest = {
        if (!decided) {
            decided = true
            onDismissRequest()
        }
    }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Timer circular
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round,
                        color = when {
                            secondsLeft > 4 -> MaterialTheme.colorScheme.primary
                            secondsLeft > 2 -> MaterialTheme.colorScheme.tertiary
                            else            -> MaterialTheme.colorScheme.error
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = "$secondsLeft",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Solicitud de conexión",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = info.endpointName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Código de verificación: ${info.authenticationDigits}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "La solicitud expirará en $secondsLeft segundos",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        secondsLeft > 4 -> MaterialTheme.colorScheme.onSurfaceVariant
                        else            -> MaterialTheme.colorScheme.error
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!decided) {
                                decided = true
                                onDismissRequest()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Rechazar")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    FilledTonalButton(
                        onClick = {
                            if (!decided) {
                                decided = true
                                // Llamar acceptConnection inmediatamente,
                                // sin pasar por coroutine scope
                                onConfirmation()
                            }
                        }
                    ) {
                        Text("Aceptar")
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun IncomingCallDialogPreview(){
    VoLlamaTheme {
        IncomingCallDialog("Google - Pixel 2 XL", onConfirmation = {}, onDismissRequest = {})
    }
}

@Composable
fun IncomingCallDialog(
    endpointName: String,
    isWalkieTalkie: Boolean = false,
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit
) {
    AlertDialog(
        icon = {
            Icon(
                if (isWalkieTalkie) Icons.Outlined.ConnectWithoutContact
                else Icons.Outlined.CallReceived,
                contentDescription = null
            )
        },
        title = { Text(if (isWalkieTalkie) "Solicitud de Walkie-Talkie" else "Solicitud de llamada") },
        text = {
            Text(
                if (isWalkieTalkie)
                    "$endpointName quiere iniciar un Walkie-Talkie contigo, ¿deseas aceptar?"
                else
                    "$endpointName intenta iniciar una llamada contigo, ¿deseas aceptarla?"
            )
        },
        onDismissRequest = { onDismissRequest() },
        confirmButton = {
            TextButton(onClick = onConfirmation) {
                Text(if (isWalkieTalkie) "Aceptar" else "Aceptar llamada")
            }
        },
        dismissButton = {
            Button(onClick = { onDismissRequest() }) {
                Text("Rechazar")
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
        onDismissRequest = onConfirmation,
        title = { Text("Hubo un Problema") },
        text = {
            Text(
                when (connectionStatus) {
                    NearbyConnectionService.NearbyStatus.REJECTED -> "Conexión Rechazada"
                    NearbyConnectionService.NearbyStatus.CONNECTION_ERROR -> "Hubo un error de conexión, intente nuevamente"
                    else -> "Error desconocido"
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    endpoint.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    endpoint.endpointId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Conectar")
            }
        }
    }
}

@Composable
fun ConnectedDeviceCard(
    endpointName: String,
    endpointId: String,
    onCallMode: () -> Unit,
    onWalkieTalkieMode: () -> Unit,
    onDisconnect: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Name, Status and Disconnect
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = endpointName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF4CAF50), shape = CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Conectado • $endpointId",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDisconnect,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Desconectar")
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onWalkieTalkieMode,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ConnectWithoutContact,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Walkie")
                }

                Button(
                    onClick = onCallMode,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Phone,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Llamar")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottom(selectedIndex: Int,
                endpointName:String,
                onIndexChange: (Int) -> Unit,
                discoveredDevices: List<NearbyConnectionService.DiscoveredEndpoint>,
                onDeviceClick: (String) -> Unit,
                onRefreshButton: ()-> Unit){
    val options = listOf("Quiero conectarme!", "Espero una conexión")
    val deviceCount = discoveredDevices.size

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
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(),verticalAlignment = Alignment.Top) {
                    Text(
                        text = "Dispositivos disponibles ($deviceCount)",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onRefreshButton) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reiniciar Búsqueda")
                    }
                }
                Text(
                    text = "apareces como $endpointName",
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

@Composable
private fun rememberPermissionLauncher(
    onGranted: () -> Unit
): ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>> {

    return rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            onGranted()
        }
    }
}

private fun hasAllPermissions(
    context: Context,
    permissions: Array<String>
): Boolean {
    return permissions.all {
        ContextCompat.checkSelfPermission(
            context,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberNearbyPermissions(): Array<String> {
    return remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO
            )
        }
    }
}



@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    viewModel: ViewModelApp = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val incomingCall by viewModel.callStatus.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val permissions = rememberNearbyPermissions()
    val context = LocalContext.current
    val permissionLauncher = rememberPermissionLauncher(
        onGranted = {
            viewModel.onEvent(MainEvent.StartConnection)
        }
    )

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
                    if (hasAllPermissions(context ,permissions)) {
                        viewModel.onEvent(MainEvent.StartConnection)
                    }else{
                        permissionLauncher.launch(permissions)
                    }
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
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(
                    items = state.connectedEndpoints.toList(),
                    key = { it.first }
                ) { pair ->
                    ConnectedDeviceCard(
                        endpointName =pair.second.endpointName,
                        endpointId = pair.first,
                        onCallMode = {
                            val intent = Intent(context, CallModeActivity::class.java).apply {
                                putExtra("ENDPOINT_ID",pair.first)
                                putExtra("ENDPOINT_NAME", pair.second.endpointName)
                            }
                            context.startActivity(intent)
                        },
                        onWalkieTalkieMode = {
                            val intent = Intent(context, WalkieTalkieActivity::class.java).apply {
                                putExtra("ENDPOINT_ID", pair.first)
                                putExtra("ENDPOINT_NAME", pair.second.endpointName)
                            }
                            context.startActivity(intent)
                        },
                        onDisconnect = {
                            viewModel.onEvent(MainEvent.Disconnect(pair.first))
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
            info = info,
            onDismissRequest = { viewModel.rejectConnection(id) },
            onConfirmation = { viewModel.acceptConnection(id) },
            onTimeout = { viewModel.rejectConnection(id) }
        )
    }

    incomingCall?.let { signal ->
        val isWalkie = signal.type ==
            NearbyConnectionService.ConnectionSignalsTypes.INCOMING_WALKIE_TALKIE
        IncomingCallDialog(
            endpointName = signal.senderName,
            isWalkieTalkie = isWalkie,
            onDismissRequest = {
                viewModel.sendConnectionSignal(
                    signal.senderId,
                    NearbyConnectionService.ConnectionSignalsTypes.CALL_REJECTED
                )
                viewModel.clearConnectionSignalReceived()
            },
            onConfirmation = {
                val activityClass =
                    if (isWalkie) WalkieTalkieActivity::class.java
                    else CallModeActivity::class.java
                val intent = Intent(context, activityClass).apply {
                    putExtra("ENDPOINT_ID", signal.senderId)
                    putExtra("ENDPOINT_NAME", signal.senderName)
                    putExtra("IS_INCOMING", true)
                }
                context.startActivity(intent)
                viewModel.sendConnectionSignal(
                    signal.senderId,
                    NearbyConnectionService.ConnectionSignalsTypes.CALL_ACCEPTED
                )
                viewModel.clearConnectionSignalReceived()
            }
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
                endpointName = state.endpointName,
                onDeviceClick = {
                    viewModel.onEvent(MainEvent.Connect(it))
                },
                onRefreshButton ={viewModel.onEvent(MainEvent.RestartConnection)}
            )
        }
    }
}
