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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.twotone.CallEnd
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import com.XDurango.VoLlama.Audio.CallDebugStats
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
import android.content.Intent
import android.os.PowerManager
import androidx.activity.compose.BackHandler
@AndroidEntryPoint
class CallModeActivity : ComponentActivity() {

    @Inject
    lateinit var nearbyService: NearbyConnectionService

    private var proximityWakeLock: PowerManager.WakeLock? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "VoLlama:CallProximity"
            )
        }

        setContent {
            VoLlamaTheme {
                val endpointId = intent.getStringExtra("ENDPOINT_ID") ?: ""
                val endpointName = intent.getStringExtra("ENDPOINT_NAME") ?: ""
                val isIncoming = intent.getBooleanExtra("IS_INCOMING", false)
                val viewModel: CallManager = hiltViewModel()
                val callState by viewModel.callState.collectAsState()
                val callUiState by viewModel.callUiState.collectAsState()
                val debugStats by viewModel.debugStats.collectAsState()

                BackHandler { viewModel.onCallEvent(CallEvent.EndCall) }

                LaunchedEffect(callState) {
                    when (callState) {
                        CallState.IN_CALL ->
                            proximityWakeLock?.takeIf { !it.isHeld }?.acquire()
                        CallState.ENDED -> {
                            proximityWakeLock?.takeIf { it.isHeld }?.release()
                            stopService(Intent(this@CallModeActivity, CallAudioService::class.java))
                            delay(if (callUiState.endReason == CallEndReason.REJECTED) 2000L else 500L)
                            finish()
                        }
                        else -> {}
                    }
                }

                LaunchedEffect(Unit) {
                    startService(Intent(this@CallModeActivity, CallAudioService::class.java))
                    withContext(Dispatchers.IO) {
                        nearbyService.setCallManager(viewModel)
                    }
                    viewModel.initialize(endpointId, endpointName)
                    if (isIncoming) {
                        viewModel.onCallEvent(CallEvent.AnswerCall)
                    } else {
                        viewModel.onCallEvent(CallEvent.StartCall)
                    }
                }

                CallScreen(
                    viewModel = viewModel,
                    endpointId = endpointId,
                    endpointName = endpointName,
                    callState = callState,
                    callUiState = callUiState,
                    debugStats = debugStats
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        proximityWakeLock?.takeIf { it.isHeld }?.release()
    }
}

@Composable
fun CallScreen(
    viewModel: CallManager,
    endpointName: String,
    endpointId: String,
    callState: CallState = CallState.IN_CALL,
    callUiState: CallUiState = CallUiState(),
    debugStats: CallDebugStats = CallDebugStats()
) {
    CallScreenContent(
        endpointName = endpointName,
        endpointId = endpointId,
        callState = callState,
        callUiState = callUiState,
        onEvent = viewModel::onCallEvent,
        debugStats = debugStats
    )
}

/** Versión sin estado ni ViewModel de [CallScreen]: se puede probar pasándole estado y un callback. */
@Composable
fun CallScreenContent(
    endpointName: String,
    endpointId: String,
    callState: CallState,
    callUiState: CallUiState,
    onEvent: (CallEvent) -> Unit,
    debugStats: CallDebugStats = CallDebugStats()
) {
    var showDebug by remember { mutableStateOf(false) }
    val initials = if (endpointName.isNotEmpty()) endpointName.take(1).uppercase() else "?"

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
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
                    text = when {
                        callState == CallState.CONNECTING                     -> "Llamando..."
                        callUiState.endReason == CallEndReason.REJECTED       -> "Llamada rechazada"
                        else                                                  -> "Llamada en curso • $endpointId"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (callUiState.endReason) {
                        CallEndReason.REJECTED -> MaterialTheme.colorScheme.error
                        else                   -> Color.LightGray.copy(alpha = 0.7f)
                    }
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
                        onClick = { onEvent(CallEvent.ToggleMute) }
                    )

                    CallControlButton(
                        icon = Icons.Outlined.VolumeUp,
                        label = "Altavoz",
                        isActive = callUiState.isSpeakerOn,
                        activeColor = Color(0xFF4CAF50),
                        onClick = { onEvent(CallEvent.ToggleSpeaker) }
                    )

//                    CallControlButton(
//                        icon = Icons.Outlined.Add,
//                        label = "Añadir",
//                        isActive = false,
//                        onClick = { /* TODO: conferencia */ }
//                    )
                }

                // Botón de colgar
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = Color(0xFFF44336),
                    onClick = { onEvent(CallEvent.EndCall) }
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
        IconButton(
            onClick = { showDebug = !showDebug },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(paddingValues)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = "Debug de conexión",
                tint = if (showDebug) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.5f)
            )
        }
        if (showDebug) {
            CallDebugPanel(
                stats = debugStats,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(paddingValues)
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp)
            )
        }
        }
    }
}

@Composable
internal fun CallDebugPanel(stats: CallDebugStats, modifier: Modifier = Modifier) {
    val lastRx = if (stats.msSinceLastRx < 0) "—" else "${stats.msSinceLastRx} ms"
    val latency = if (stats.rttAvgMs < 0f) "—"
        else "%.0f ms (RTT %.0f, último %.0f)".format(stats.oneWayMs, stats.rttAvgMs, stats.rttLastMs)
    val rows = listOf(
        "Latencia ≈ ida" to latency,
        "Enviado" to "%d pkts · %.1f kbps · %.1f KB".format(stats.txPackets, stats.txKbps, stats.txBytes / 1024f),
        "Recibido" to "%d pkts · %.1f kbps · %.1f KB".format(stats.rxPackets, stats.rxKbps, stats.rxBytes / 1024f),
        "Pérdida" to "%.1f %% (%d PLC)".format(stats.lossPercent, stats.concealedFrames),
        "Jitter" to "%.1f ms (gap máx %d ms)".format(stats.jitterMs, stats.maxGapMs),
        "Último paquete" to "hace $lastRx",
        "Decodificados" to stats.decodedPackets.toString(),
        "Resets decoder" to stats.decoderResets.toString(),
        "Tardíos/duplicados" to stats.latePacketsDropped.toString(),
        "Cola desbordada" to stats.queueOverflows.toString()
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.75f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                    Text(
                        value,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace
                    )
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

internal fun formatDuration(durationInMillis: Long): String {
    val seconds = (durationInMillis / 1000) % 60
    val minutes = (durationInMillis / 1000) / 60
    return String.format("%02d:%02d", minutes, seconds)
}
