package com.XDurango.VoLlama.CallMode

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresPermission
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.twotone.CallEnd
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
class WalkieTalkieActivity : ComponentActivity() {

    @Inject
    lateinit var nearbyService: NearbyConnectionService

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val endpointId = intent.getStringExtra("ENDPOINT_ID") ?: ""
        val endpointName = intent.getStringExtra("ENDPOINT_NAME") ?: ""

        setContent {
            VoLlamaTheme {
                val viewModel: CallManager = hiltViewModel()
                val callState by viewModel.callState.collectAsState()

                LaunchedEffect(callState) {
                    if (callState == CallState.ENDED) {
                        stopService(Intent(this@WalkieTalkieActivity, CallAudioService::class.java))
                        delay(500)
                        finish()
                    }
                }

                LaunchedEffect(Unit) {
                    startService(Intent(this@WalkieTalkieActivity, CallAudioService::class.java))
                    withContext(Dispatchers.IO) {
                        nearbyService.setCallManager(viewModel)
                    }
                    viewModel.initialize(endpointId, endpointName)
                    viewModel.startWalkieTalkieMode()
                }

                WalkieTalkieScreen(
                    viewModel = viewModel,
                    endpointId = endpointId,
                    endpointName = endpointName
                )
            }
        }
    }
}

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
@Composable
fun WalkieTalkieScreen(
    viewModel: CallManager,
    endpointName: String,
    endpointId: String
) {
    val callUiState by viewModel.callUiState.collectAsState()
    val walkieUiState by viewModel.walkieTalkieUiState.collectAsState()
    val initials = if (endpointName.isNotEmpty()) endpointName.take(1).uppercase() else "?"

    val pttColor by animateColorAsState(
        targetValue = when {
            walkieUiState.isTransmitting -> MaterialTheme.colorScheme.primary
            walkieUiState.isReceiving    -> Color(0xFF4CAF50)
            else                         -> Color.White.copy(alpha = 0.15f)
        },
        animationSpec = tween(150),
        label = "ptt_color"
    )
    val pttScale by animateFloatAsState(
        targetValue = if (walkieUiState.isTransmitting) 1.08f else 1f,
        animationSpec = tween(150),
        label = "ptt_scale"
    )

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
            // ── Sección superior ────────────────────────────────────────────
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
                    text = "Walkie-Talkie • $endpointId",
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

            // ── Sección central: PTT ────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = when {
                        walkieUiState.isTransmitting -> "Transmitiendo..."
                        walkieUiState.isReceiving    -> "Recibiendo..."
                        else                         -> "Mantén para hablar"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        walkieUiState.isTransmitting -> MaterialTheme.colorScheme.primary
                        walkieUiState.isReceiving    -> Color(0xFF4CAF50)
                        else                         -> Color.White.copy(alpha = 0.6f)
                    }
                )

                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(pttScale)
                        .clip(CircleShape)
                        .background(pttColor)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    viewModel.onWalkieTalkieEvent(WalkieTalkieEvent.PressToTalk)
                                    tryAwaitRelease()
                                    viewModel.onWalkieTalkieEvent(WalkieTalkieEvent.ReleaseToTalk)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "Hablar",
                        modifier = Modifier.size(72.dp),
                        tint = Color.White
                    )
                }
            }

            // ── Sección inferior: controles ─────────────────────────────────
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
                        icon = Icons.Outlined.VolumeUp,
                        label = "Altavoz",
                        isActive = callUiState.isSpeakerOn,
                        activeColor = Color(0xFF4CAF50),
                        onClick = { viewModel.onCallEvent(CallEvent.ToggleSpeaker) }
                    )
                }

                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = Color(0xFFF44336),
                    onClick = { viewModel.onCallEvent(CallEvent.EndCall) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.TwoTone.CallEnd,
                            contentDescription = "Finalizar",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
