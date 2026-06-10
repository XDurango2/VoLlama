package com.XDurango.VoLlama.CallMode

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.twotone.CallEnd
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import com.XDurango.VoLlama.ui.theme.VoLlamaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class CallModeActivity : ComponentActivity() {

    @Inject
    lateinit var nearbyService: NearbyConnectionService

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoLlamaTheme {
                val endpointId = intent.getStringExtra("ENDPOINT_ID") ?: ""
                val endpointName = intent.getStringExtra("ENDPOINT_NAME") ?: ""
                val viewModel: CallManager = hiltViewModel()
                val callState by viewModel.callState.collectAsState()

                LaunchedEffect(callState) {
                    if (callState == CallState.ENDED) {
                        delay(500)
                        finish()
                    }
                }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        nearbyService.setCallManager(viewModel)
                    }
                    viewModel.initialize(endpointId, endpointName)
                    viewModel.onCallEvent(CallEvent.StartCall)
                }

                CallScreen(
                    viewModel = viewModel,
                    endpointId = endpointId,
                    endpointName = endpointName
                )
            }
        }
    }
}

@Composable
fun CallScreen(
    viewModel: CallManager,
    endpointName: String,
    endpointId: String
) {
    val callUiState by viewModel.callUiState.collectAsState()
    val initials = if (endpointName.isNotEmpty()) endpointName.take(1).uppercase() else "?"

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Sección superior: info del contacto
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Surface(
                    modifier = Modifier.size(140.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = endpointName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Llamada en curso • $endpointId",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = formatDuration(callUiState.callDuration),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            // Sección inferior: controles
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlButton(
                        icon = if (callUiState.isMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                        label = if (callUiState.isMuted) "Silenciado" else "Silenciar",
                        isActive = callUiState.isMuted,
                        activeColor = MaterialTheme.colorScheme.error,
                        onClick = { viewModel.onCallEvent(CallEvent.ToggleMute) }
                    )

                    CallControlButton(
                        icon = Icons.Outlined.VolumeUp,
                        label = "Altavoz",
                        isActive = callUiState.isSpeakerOn,
                        activeColor = Color(0xFF4CAF50),
                        onClick = { viewModel.onCallEvent(CallEvent.ToggleSpeaker) }
                    )

                    CallControlButton(
                        icon = Icons.Outlined.Add,
                        label = "Añadir",
                        isActive = false,
                        onClick = { /* TODO: conferencia */ }
                    )
                }

                // Botón de colgar
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = Color(0xFFF44336),
                    onClick = { viewModel.onCallEvent(CallEvent.EndCall) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.TwoTone.CallEnd,
                            contentDescription = "Finalizar llamada",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (isActive) activeColor else Color.White.copy(alpha = 0.15f),
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

private fun formatDuration(durationInMillis: Long): String {
    val seconds = (durationInMillis / 1000) % 60
    val minutes = (durationInMillis / 1000) / 60
    return String.format("%02d:%02d", minutes, seconds)
}
