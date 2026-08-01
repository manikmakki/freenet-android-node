package org.freenet.androidnode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NativeBridgeScreen()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun NativeBridgeScreen() {
    val buildInfo = remember {
        NativeBridge.buildInfo().fold(
            onSuccess = { it },
            onFailure = { "Unavailable: ${it.message ?: "unknown error"}" },
        )
    }
    var testResult by remember { mutableStateOf("Not run") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Freenet Android Node",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = if (NativeBridge.isLoaded) {
                "Native bridge: Loaded"
            } else {
                "Native bridge: Not loaded (${NativeBridge.loadError ?: "unknown error"})"
            },
        )
        Text(text = "Native version: $buildInfo")
        Text(text = "Native test: $testResult")
        Button(
            enabled = NativeBridge.isLoaded,
            onClick = {
                testResult = NativeBridge.ping().fold(
                    onSuccess = { it },
                    onFailure = { "Error: ${it.message ?: "unknown error"}" },
                )
            },
        ) {
            Text("Run native test")
        }
    }
}
