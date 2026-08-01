package org.freenet.androidnode

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.delay
import org.json.JSONObject

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
    val context = LocalContext.current
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
    var nodeState by remember { mutableStateOf("Unknown") }
    var nodeDetail by remember { mutableStateOf("Waiting for native status") }
    var completedStartCycles by remember { mutableStateOf(0L) }
    var actionResult by remember { mutableStateOf("No lifecycle action submitted") }
    var recentLogs by remember { mutableStateOf("No logs requested") }
    var contractProof by remember { mutableStateOf(ContractUiStatus.idle()) }
    var contractActionResult by remember { mutableStateOf("No contract proof action submitted") }

    LaunchedEffect(Unit) {
        while (true) {
            NativeBridge.nodeStatus().fold(
                onSuccess = { response ->
                    parseStatus(response).also { status ->
                        nodeState = status.state
                        nodeDetail = status.detail
                        completedStartCycles = status.completedStartCycles
                    }
                },
                onFailure = { error ->
                    nodeState = "Unavailable"
                    nodeDetail = error.message ?: "unknown status error"
                },
            )
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
        Text(text = "Node state: $nodeState")
        Text(text = "Node detail: $nodeDetail")
        Text(text = "Completed start cycles: $completedStartCycles")
        Text(text = "Contract proof state: ${contractProof.state}")
        Text(text = "Contract proof detail: ${contractProof.detail}")
        Text(text = "Contract fixture: ${contractProof.fixtureName}")
        Text(text = "Contract key: ${contractProof.contractKey ?: "Not created"}")
        Text(text = "Contract result: ${contractProof.result ?: "Not available"}")
        Text(text = "Persistence verified: ${contractProof.persistenceVerified}")
        Text(text = contractProof.metricsText())
        Text(text = "Last lifecycle response: $actionResult")
        Button(
            enabled = NativeBridge.isLoaded,
            onClick = {
                actionResult = NativeBridge.startLocalNode(androidNodeConfigJson(context)).fold(
                    onSuccess = { it },
                    onFailure = { "JNI error: ${it.message ?: "unknown error"}" },
                )
            },
        ) {
            Text("Start local node")
        }
        Button(
            enabled = NativeBridge.isLoaded,
            onClick = {
                actionResult = NativeBridge.stopNode().fold(
                    onSuccess = { it },
                    onFailure = { "JNI error: ${it.message ?: "unknown error"}" },
                )
            },
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

private data class NodeUiStatus(
    val state: String,
    val detail: String,
    val completedStartCycles: Long,
)

private fun parseStatus(response: String): NodeUiStatus {
    return runCatching {
        val envelope = JSONObject(response)
        if (!envelope.optBoolean("ok")) {
            val error = envelope.optJSONObject("error")
            return@runCatching NodeUiStatus(
                state = "Error",
                detail = error?.optString("message") ?: "Native status request failed",
                completedStartCycles = 0,
            )
        }
        val data = envelope.getJSONObject("data")
        NodeUiStatus(
            state = data.optString("state", "Unknown"),
            detail = data.optString("detail", "No detail"),
            completedStartCycles = data.optLong("completedStartCycles"),
        )
    }.getOrElse { error ->
        NodeUiStatus("Invalid response", error.message ?: response, 0)
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

private fun androidNodeConfigJson(context: Context): String {
    val dataRoot = File(context.noBackupFilesDir, "freenet")
    return JSONObject()
        .put("filesDir", context.filesDir.absolutePath)
        .put("cacheDir", context.cacheDir.absolutePath)
        .put("noBackupFilesDir", context.noBackupFilesDir.absolutePath)
        .put("databaseDirectory", File(dataRoot, "db/local").absolutePath)
        .put("contractDirectory", File(dataRoot, "contracts/local").absolutePath)
        .put(
            "configurationDirectory",
            File(context.filesDir, "freenet/config").absolutePath,
        )
        .put("logDirectory", File(context.filesDir, "freenet/logs").absolutePath)
        .put("websocketPort", 17509)
        .toString()
}
