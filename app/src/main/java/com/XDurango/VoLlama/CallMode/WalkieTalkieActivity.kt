package com.XDurango.VoLlama.CallMode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.XDurango.VoLlama.ui.theme.VoLlamaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WalkieTalkieActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val endpointId = intent.getStringExtra("ENDPOINT_ID") ?: ""
        val endpointName = intent.getStringExtra("ENDPOINT_NAME") ?: ""

        setContent {
            VoLlamaTheme {
                WalkieTalkieScreen(
                    endpointName = endpointName,
                    onHangUp = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieScreen(
    endpointName: String,
    onHangUp: () -> Unit
) {
    // TODO: conectar con CallManager/AudioEngine
    var isTransmitting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Walkie-Talkie · $endpointName") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isTransmitting) "Transmitiendo..." else "Mantén para hablar",
                style = MaterialTheme.typography.titleMedium,
                color = if (isTransmitting) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Botón PTT (Push-To-Talk)
            Surface(
                modifier = Modifier.size(160.dp),
                shape = CircleShape,
                color = if (isTransmitting) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                onClick = { /* interacciones manejadas por gestos abajo */ }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "Hablar",
                        modifier = Modifier.size(64.dp),
                        tint = if (isTransmitting) Color.White
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "PTT",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}