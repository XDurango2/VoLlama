package com.XDurango.VoLlama

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.*
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.twotone.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
import com.XDurango.VoLlama.ui.theme.VoLlamaTheme
import androidx.activity.compose.setContent
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import jakarta.inject.Inject
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.delay
import java.util.jar.Manifest
@AndroidEntryPoint
class CallModeActivity: ComponentActivity() {

    @Inject
    lateinit var nearbyService: NearbyConnectionService

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoLlamaTheme {
                val endpointId = intent.getStringExtra("ENDPOINT_ID") ?: ""
                val endpointName = intent.getStringExtra("ENDPOINT_NAME") ?: ""
                val viewModel: CallManager = hiltViewModel()

                LaunchedEffect(Unit) {
                    // Conectar CallManager con NearbyService
                    nearbyService.setCallManager(viewModel)

                    viewModel.initialize(endpointId, endpointName)
                    delay(500)
                    viewModel.onCallEvent(CallEvent.StartCall)
                }

                CallScreen(
                    callManager = viewModel,
                    endpointId = endpointId,
                    endpointName = endpointName
                )
            }
        }
    }
}

@Composable
fun CallScreen(callManager: CallManager,
               endpointId:String,
               endpointName:String

) {

    val callUiState by callManager.callUiState.collectAsState()


    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF1E1E1E)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Información del contacto
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 60.dp)
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .background(Color(0xFF3D3D3D), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "JD",
                        fontSize = 48.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Nombre del contacto
                Text(
                    text = endpointName,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = endpointId,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Duración de la llamada
                Text(
                    text = formatDuration(callUiState.callDuration),
                    fontSize = 16.sp,
                    color = Color.Gray
                )
            }

            // Controles de llamada
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                // Botones superiores
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Botón de silencio
                    CallButton(
                        icon = if (callUiState.isMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                        label = "Silenciar",
                        backgroundColor = if (!callUiState.isMuted) Color.White else Color.Red,
                        onClick = { callManager.onCallEvent(CallEvent.ToggleMute) }
                    )

                    // Botón de altavoz
                    CallButton(
                        icon = Icons.Outlined.VolumeUp,
                        label = "Altavoz",
                        backgroundColor = if (!callUiState.isSpeakerOn) Color.White else Color.Green,
                        onClick = { callManager.onCallEvent(CallEvent.ToggleSpeaker) }
                    )

                    // Botón de agregar llamada
                    CallButton(
                        icon = Icons.Outlined.Add,
                        label = "Agregar",
                        backgroundColor = Color.White,
                        onClick = { /* Agregar funcionalidad */ }
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                // Botón de colgar
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color(0xFFE53935), CircleShape)
                        .clickable { callManager.onCallEvent(CallEvent.EndCall) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.TwoTone.CallEnd, contentDescription = "Colgar",modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
fun CallButton(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(backgroundColor, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White
        )
    }
}

// Función helper para formatear la duración
@Composable
private fun formatDuration(durationInMillis: Long): String {
    val seconds = (durationInMillis / 1000) % 60
    val minutes = (durationInMillis / 1000) / 60
    return String.format("%02d:%02d", minutes, seconds)
}