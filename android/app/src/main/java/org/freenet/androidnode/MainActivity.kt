package org.freenet.androidnode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val nodeViewModel: NodeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NativeBridgeScreen(nodeViewModel)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun NativeBridgeScreen(nodeViewModel: NodeViewModel) {
    val context = LocalContext.current
    val serviceState by nodeViewModel.state.collectAsState()
    val storageState by nodeViewModel.storageState.collectAsState()
    var pendingNetworkStart by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (pendingNetworkStart) {
                nodeViewModel.startNetworkNode()
            } else {
                nodeViewModel.startLocalNode()
            }
        } else {
            nodeViewModel.reportNotificationPermissionRequired()
        }
    }
    val buildInfo = remember {
        NativeBridge.buildInfo().fold(
            onSuccess = { it },
            onFailure = { "Unavailable: ${it.message ?: "unknown error"}" },
        )
    }
    val freenetBuildInfo = remember {
        NativeBridge.freenetBuildInfo().fold(
            onSuccess = { it },
            onFailure = { "Unavailable: ${it.message ?: "unknown error"}" },
        )
    }
    var testResult by remember { mutableStateOf("Not run") }
    var recentLogs by remember { mutableStateOf("No logs requested") }
    var contractProof by remember { mutableStateOf(ContractUiStatus.idle()) }
    var contractActionResult by remember { mutableStateOf("No contract proof action submitted") }

    LaunchedEffect(Unit) {
        while (true) {
            NativeBridge.contractProofStatus().fold(
                onSuccess = { response -> contractProof = parseContractProofStatus(response) },
                onFailure = { error ->
                    contractProof = ContractUiStatus.unavailable(
                        error.message ?: "unknown contract-proof status error",
                    )
                },
            )
            delay(250)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                NodeRepository.refreshStorage(context.applicationContext)
            }
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
        Text(text = freenetBuildInfo)
        Text(text = "Node state: ${serviceState.state}")
        Text(text = "Node mode: ${serviceState.mode}")
        Text(text = "Node detail: ${serviceState.detail}")
        Text(text = "Service active: ${serviceState.serviceActive}")
        Text(text = "Completed start cycles: ${serviceState.completedStartCycles}")
        Text(
            text = "Network: ${serviceState.currentNetworkType}; " +
                "available=${serviceState.connectivityAvailable}; " +
                "metered=${serviceState.networkMetered}; VPN=${serviceState.vpnActive}",
        )
        Text(
            text = "Peers: ${serviceState.peers}; attempts: ${serviceState.connectionAttempts}; " +
                "successful connections: ${serviceState.successfulConnections}",
        )
        Text(
            text = "Network bytes: sent ${serviceState.bytesSent.displayBytes()}, " +
                "received ${serviceState.bytesReceived.displayBytes()}",
        )
        Text(text = "Last network error: ${serviceState.lastNetworkError ?: "None"}")
        Text(text = "Identity fingerprint: ${storageState.identityFingerprint ?: "Not created"}")
        Text(text = "Storage status: ${storageState.detail}")
        Text(text = "Identity files owner-only: ${storageState.identityOwnerOnly}")
        Text(text = "Storage layout ready: ${storageState.layoutReady}")
        Text(
            text = "Storage used: ${storageState.totalBytes.displayBytes()} " +
                "(persistent ${storageState.persistentBytes.displayBytes()}, " +
                "temporary ${storageState.temporaryBytes.displayBytes()}, " +
                "identity ${storageState.identityBytes.displayBytes()})",
        )
        Text(text = "Secret material detected in adapter logs: ${storageState.secretMaterialInLogs}")
        Text(text = "Key protection: Prototype file-backed identity; Keystore wrapping pending")
        Text(text = "Contract proof state: ${contractProof.state}")
        Text(text = "Contract proof detail: ${contractProof.detail}")
        Text(text = "Contract fixture: ${contractProof.fixtureName}")
        Text(text = "Contract key: ${contractProof.contractKey ?: "Not created"}")
        Text(text = "Contract result: ${contractProof.result ?: "Not available"}")
        Text(text = "Persistence verified: ${contractProof.persistenceVerified}")
        Text(text = contractProof.metricsText())
        Text(text = "Last lifecycle response: ${serviceState.lastLifecycleResponse}")
        Button(
            enabled = NativeBridge.isLoaded,
            onClick = {
                pendingNetworkStart = false
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    nodeViewModel.startLocalNode()
                }
            },
        ) {
            Text("Start local node")
        }
        Button(
            enabled = NativeBridge.isLoaded && !serviceState.serviceActive,
            onClick = {
                pendingNetworkStart = true
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    nodeViewModel.startNetworkNode()
                }
            },
        ) {
            Text("Start network node (unmetered Wi-Fi)")
        }
        Button(
            enabled = NativeBridge.isLoaded && serviceState.serviceActive,
            onClick = nodeViewModel::pauseNode,
        ) {
            Text("Pause node")
        }
        Button(
            enabled = NativeBridge.isLoaded,
            onClick = nodeViewModel::stopNode,
        ) {
            Text("Stop node")
        }
        Button(
            enabled = NativeBridge.isLoaded,
            onClick = {
                contractActionResult = NativeBridge.runContractProof().fold(
                    onSuccess = { it },
                    onFailure = { "JNI error: ${it.message ?: "unknown error"}" },
                )
            },
        ) {
            Text("Run WASM contract proof")
        }
        Button(
            enabled = NativeBridge.isLoaded,
            onClick = {
                contractActionResult = NativeBridge.verifyContractPersistence().fold(
                    onSuccess = { it },
                    onFailure = { "JNI error: ${it.message ?: "unknown error"}" },
                )
            },
        ) {
            Text("Verify contract persistence")
        }
        Text(text = "Last contract response: $contractActionResult")
        Button(
            enabled = NativeBridge.isLoaded,
            onClick = {
                recentLogs = NativeBridge.recentLogs(12).fold(
                    onSuccess = { it },
                    onFailure = { "JNI error: ${it.message ?: "unknown error"}" },
                )
            },
        ) {
            Text("Refresh native logs")
        }
        Text(text = "Recent native logs: $recentLogs")
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

private data class ContractUiStatus(
    val state: String,
    val detail: String,
    val fixtureName: String,
    val contractKey: String?,
    val result: String?,
    val persistenceVerified: Boolean,
    val contractLoadTimeUs: Long?,
    val firstExecutionTimeUs: Long?,
    val subsequentExecutionTimeUs: Long?,
    val persistenceReadTimeUs: Long?,
    val peakResidentSetKb: Long?,
) {
    fun metricsText(): String =
        "Contract metrics: load=${contractLoadTimeUs.displayMicros()}, " +
            "first=${firstExecutionTimeUs.displayMicros()}, " +
            "subsequent=${subsequentExecutionTimeUs.displayMicros()}, " +
            "restart read=${persistenceReadTimeUs.displayMicros()}, " +
            "peak RSS=${peakResidentSetKb?.let { "$it KiB" } ?: "pending"}"

    companion object {
        fun idle(): ContractUiStatus = ContractUiStatus(
            state = "Unknown",
            detail = "Waiting for native contract-proof status",
            fixtureName = "Unknown",
            contractKey = null,
            result = null,
            persistenceVerified = false,
            contractLoadTimeUs = null,
            firstExecutionTimeUs = null,
            subsequentExecutionTimeUs = null,
            persistenceReadTimeUs = null,
            peakResidentSetKb = null,
        )

        fun unavailable(detail: String): ContractUiStatus = idle().copy(
            state = "Unavailable",
            detail = detail,
        )
    }
}

private fun parseContractProofStatus(response: String): ContractUiStatus {
    return runCatching {
        val envelope = JSONObject(response)
        if (!envelope.optBoolean("ok")) {
            return@runCatching ContractUiStatus.unavailable(
                envelope.optJSONObject("error")?.optString("message")
                    ?: "Native contract-proof status request failed",
            )
        }
        val data = envelope.getJSONObject("data")
        ContractUiStatus(
            state = data.optString("state", "Unknown"),
            detail = data.optString("detail", "No detail"),
            fixtureName = data.optString("fixtureName", "Unknown"),
            contractKey = data.optionalString("contractKey"),
            result = data.optionalString("result"),
            persistenceVerified = data.optBoolean("persistenceVerified"),
            contractLoadTimeUs = data.optionalLong("contractLoadTimeUs"),
            firstExecutionTimeUs = data.optionalLong("firstExecutionTimeUs"),
            subsequentExecutionTimeUs = data.optionalLong("subsequentExecutionTimeUs"),
            persistenceReadTimeUs = data.optionalLong("persistenceReadTimeUs"),
            peakResidentSetKb = data.optionalLong("peakResidentSetKb"),
        )
    }.getOrElse { error ->
        ContractUiStatus.unavailable(error.message ?: response)
    }
}

private fun JSONObject.optionalLong(name: String): Long? =
    if (isNull(name)) null else optLong(name)

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else optString(name)

private fun Long?.displayMicros(): String = this?.let { "$it µs" } ?: "pending"

private fun Long.displayBytes(): String = when {
    this >= 1_048_576 -> "%.1f MiB".format(this / 1_048_576.0)
    this >= 1_024 -> "%.1f KiB".format(this / 1_024.0)
    else -> "$this B"
}
