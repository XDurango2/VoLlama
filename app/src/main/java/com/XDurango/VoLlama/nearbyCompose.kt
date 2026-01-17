package com.XDurango.VoLlama

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.XDurango.VoLlama.ui.theme.VoLlamaTheme
import com.google.android.gms.nearby.connection.ConnectionInfo
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.material3.SegmentedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.collections.emptyList
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.DisposableEffect
import org.intellij.lang.annotations.JdkConstants

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
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
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
                    text = "Dispositivos disponibles",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (discoveredDevices.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp)
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
                            key = { it.endpointId }
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
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
fun mainMenu() {
    val context = LocalContext.current
    val viewmodel: ViewModelApp = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    // Estados del BottomSheet
    val sheetState = rememberModalBottomSheetState()
    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(0) }

    // Observables
    val toastMessage by viewmodel.toastMessage.observeAsState()
    val discoveredDevices by viewmodel.discoveredEndpoints.observeAsState(emptyList())
    val connectedDevices by viewmodel.connectedEndpoints.observeAsState(emptySet())
    val connectionDialog by viewmodel.showConnectionDialog.observeAsState(null)
    val isStreamingAudio by viewmodel.isStreamingAudio.observeAsState(false)
    val isReceivingAudio by viewmodel.isReceivingAudio.observeAsState(false)

    // Snackbar effect
    LaunchedEffect(toastMessage) {
        toastMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewmodel.clearToastMessage()
        }
    }
    //  Cerrar el sheet cuando hay dispositivos conectados
    LaunchedEffect(connectedDevices) {
        if (connectedDevices.isNotEmpty() && showBottomSheet) {
            showBottomSheet = false
            viewmodel.stopDiscovery()
            viewmodel.stopAdvertising()
        }
    }

    // Permisos
    val nearbyPermissions = remember {
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

    // Función de utilidad estable
    val hasPermissions = remember(nearbyPermissions) {
        {
            nearbyPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    // Launcher de permisos
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                context,
                "Permisos requeridos para Nearby Connections",
                Toast.LENGTH_LONG
            ).show()
        }
    }



    // Manejar discovery/advertising - SOLO cuando el sheet está visible
    LaunchedEffect(showBottomSheet, selectedIndex) {
        if (showBottomSheet) {
            if (selectedIndex == 0) {
                viewmodel.startDiscovery()
            } else {
                viewmodel.startAdvertising()
            }
        }
    }

    // Cleanup cuando se cierra el sheet
    DisposableEffect(showBottomSheet) {
        onDispose {
            if (!showBottomSheet) {
                viewmodel.stopDiscovery()
                viewmodel.stopAdvertising()
            }
        }
    }

    // 🔥 Box principal que contiene todo
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("VoLlama - Voice Chat") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text("Iniciar conexión") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = "") },
                    onClick = {
                        if (hasPermissions()) {
                            showBottomSheet = true
                        } else {
                            permissionLauncher.launch(nearbyPermissions)
                        }
                    }
                )
            }
        ) { paddingValues ->

            if (connectedDevices.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
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
                        items = connectedDevices.toList(),
                        key = { it }
                    ) { endpointId ->
                        ConnectedDeviceCard(
                            endpointId = endpointId,
                            isStreaming = isStreamingAudio,
                            isReceiving = isReceivingAudio,
                            onStartStreaming = { viewmodel.startVoiceStreaming(endpointId) },
                            onStopStreaming = { viewmodel.stopVoiceStreaming() },
                            onDisconnect = { viewmodel.disconnectAll() }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "No hay dispositivos conectados",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "Presiona el botón + para comenzar",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Diálogo de confirmación
            connectionDialog?.let { (endpointId, info) ->
                OnConnectionInitiatedDialog(
                    endpointId = endpointId,
                    info = info,
                    onDismissRequest = {
                        viewmodel.rejectConnection(endpointId)
                    },
                    onConfirmation = {
                        viewmodel.acceptConnection(endpointId)
                    }
                )
            }
        }

        // 🔥 Modal Bottom Sheet FUERA del Scaffold
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showBottomSheet = false
                    viewmodel.stopDiscovery()
                    viewmodel.stopAdvertising()
                },
                sheetState = sheetState
            ) {
                ModalBottom(
                    selectedIndex = selectedIndex,
                    onIndexChange = { newIndex ->
                        if (selectedIndex == 0) {
                            viewmodel.stopDiscovery()
                        } else {
                            viewmodel.stopAdvertising()
                        }

                        selectedIndex = newIndex
                    },
                    discoveredDevices = discoveredDevices,
                    onDeviceClick = { endpointId ->
                        viewmodel.connectToEndpoint(endpointId)
                    }
                )
            }
        }

        // 🔥 SnackbarHost al FINAL (arriba de todo)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}






