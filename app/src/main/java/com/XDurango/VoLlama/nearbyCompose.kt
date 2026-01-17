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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun OnConnectionInitiatedDialog(
    endpointId: String,
    info: ConnectionInfo,
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit
) {
    AlertDialog(
        title = { Text("Confirme Conexión a $endpointId") },
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
fun deviceCard(endpointId: String,info: ConnectionInfo,onClick:()-> Unit){
    ElevatedCard(elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier.size(width = 100.dp, height = 40.dp)
    ) {
        Text(endpointId, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Text(info.endpointName, modifier = Modifier.padding(10.dp), textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottom(){
    //val discoveredDevices:
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }

    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(
            text = {Text("iniciar conexión")},
            icon = {Icon(Icons.Filled.Add, contentDescription="")},
            onClick = {
                showBottomSheet =true
            }
        )
    }) { contentPadding ->
        if (showBottomSheet){
            ModalBottomSheet(
                onDismissRequest = {
                    showBottomSheet =false
                },
                sheetState = sheetState
            ) {
                var selectedIndex by remember { mutableIntStateOf(0) }
                val options = listOf("Quiero conectarme!", "Espero una conexion")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalAlignment = Alignment.Start,
                ) {


                    Text(
                        "Estas listo para conectarte con otros para hablar!",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow (){
                        options.forEachIndexed { index, label ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = options.size
                                ),
                                onClick = { selectedIndex = index },
                                selected = index == selectedIndex,
                                label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                    if (selectedIndex==0){
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Dispositivos disponibles",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        //LazyColumn(items(discoveredDevices.toList())) { }

                    }

                }

            }

        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun mainMenu(){
    val context = LocalContext.current.applicationContext
    val viewmodel: ViewModelApp = viewModel()
    val discoveredDevices by viewmodel.discoveredEndpoints.observeAsState(emptyList())
    val connectedDevices by viewmodel.connectedEndpoints.observeAsState(emptySet())
    val connectionDialog by viewmodel.showConnectionDialog.observeAsState(null)
   // val isStreamingAudio by viewModel.isStreamingAudio.observeAsState(false)
    //val isReceivingAudio by viewModel.isReceivingAudio.observeAsState(false)




    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU){
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT< Build.VERSION_CODES.S){
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {results ->
        val granted = results.values.all {it}
        if(!granted){
            Toast.makeText(context,"Permisos Denegados!", Toast.LENGTH_SHORT).show()

        }else{
            Toast.makeText(context,"Permisos concedidos!",Toast.LENGTH_LONG).show()
        }
    }
        val permissionGranted = permissions.all {
            ContextCompat.checkSelfPermission(
                context,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }

    // Estados locales para el BottomSheet
    val sheetState = rememberModalBottomSheetState()
    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(0) }

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
                    if (!permissionGranted) {
                        Toast.makeText(context, "Necesitas otorgar permisos primero", Toast.LENGTH_SHORT).show()
                        launcher.launch(permissions)
                    } else {
                        showBottomSheet = true
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Mostrar dispositivos conectados
            if (connectedDevices.isNotEmpty()) {
                Text(
                    "Dispositivos Conectados",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

//                connectedDevices.forEach { endpointId ->
//                    ConnectedDeviceCard(
//                        endpointId = endpointId,
//                        isStreaming = isStreamingAudio,
//                        isReceiving = isReceivingAudio,
//                        onStartStreaming = { viewModel.startVoiceStreaming(endpointId) },
//                        onStopStreaming = { viewModel.stopVoiceStreaming() },
//                        onDisconnect = { viewModel.disconnectAll() }
//                    )
//                }
            } else {
                // Mensaje cuando no hay conexiones
                Box(
                    modifier = Modifier.fillMaxSize(),
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
//            // Modal Bottom Sheet
//            if (showBottomSheet) {
//                ModalBottomSheet(
//                    onDismissRequest = {
//                        showBottomSheet = false
//                        // Detener discovery/advertising al cerrar
//                        if (selectedIndex == 0) viewModel.stopDiscovery()
//                        else viewModel.stopAdvertising()
//                    },
//                    sheetState = sheetState
//                ) {
//                    ConnectionBottomSheet(
//                        selectedIndex = selectedIndex,
//                        onIndexChange = { newIndex ->
//                            // Detener el modo anterior
//                            if (selectedIndex == 0) viewModel.stopDiscovery()
//                            else viewModel.stopAdvertising()
//
//                            selectedIndex = newIndex
//
//                            // Iniciar el nuevo modo
//                            if (newIndex == 0) viewModel.startDiscovery()
//                            else viewModel.startAdvertising()
//                        },
//                        discoveredDevices = discoveredDevices,
//                        onDeviceClick = { endpointId ->
//                            viewModel.connectToEndpoint(endpointId)
//                        }
//                    )
//                }
            }
        }
    }








