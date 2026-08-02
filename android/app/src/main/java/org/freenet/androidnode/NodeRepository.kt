package org.freenet.androidnode

import android.content.Context
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class NodeUiState(
    val state: String = "Stopped",
    val detail: String = "The foreground service is not running",
    val completedStartCycles: Long = 0,
    val serviceActive: Boolean = false,
    val mode: String = "Local",
    val peers: Int = 0,
    val startedAtElapsedRealtimeMs: Long? = null,
    val lastLifecycleResponse: String = "No lifecycle action submitted",
    val taskRemovedWhileRunning: Boolean = false,
    val identityFingerprint: String? = null,
    val connectionAttempts: Long = 0,
    val successfulConnections: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val currentNetworkType: String = "Unavailable",
    val connectivityAvailable: Boolean = false,
    val networkMetered: Boolean = false,
    val vpnActive: Boolean = false,
    val lastNetworkError: String? = null,
) {
    val uptimeMs: Long
        get() = startedAtElapsedRealtimeMs
            ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) }
            ?: 0
}

data class StorageUiState(
    val available: Boolean = false,
    val detail: String = "Storage has not been measured",
    val persistentBytes: Long = 0,
    val temporaryBytes: Long = 0,
    val identityBytes: Long = 0,
    val totalBytes: Long = 0,
    val identityFingerprint: String? = null,
    val identityOwnerOnly: Boolean = false,
    val secretMaterialInLogs: Boolean = false,
    val layoutReady: Boolean = false,
    val prototypeKeySecurityDebt: Boolean = true,
)

object NodeRepository {
    private val mutableState = MutableStateFlow(NodeUiState())
    private val mutableStorageState = MutableStateFlow(StorageUiState())

    val state: StateFlow<NodeUiState> = mutableState.asStateFlow()
    val storageState: StateFlow<StorageUiState> = mutableStorageState.asStateFlow()
    val policies: StateFlow<NodePolicyState> = NodePolicyRepository.state

    fun startLocal(context: Context) {
        val appContext = context.applicationContext
        NodePolicyRepository.stopAutomaticScheduling(appContext)
        mutableState.value = mutableState.value.copy(
            state = "Starting",
            detail = "Submitting the foreground-service start request",
            serviceActive = true,
            mode = "Local",
            peers = 0,
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            lastLifecycleResponse = "Starting local node through NodeService",
            taskRemovedWhileRunning = false,
        )
        appContext.startForegroundService(NodeService.startLocalIntent(appContext))
    }

    fun startNetwork(context: Context) {
        val appContext = context.applicationContext
        NodePolicyRepository.initialize(appContext)
        NodePolicyRepository.setSuspended(appContext, false)
        mutableState.value = mutableState.value.copy(
            state = "Starting",
            detail = "Checking the selected power and network policies",
            serviceActive = true,
            mode = "Network",
            peers = 0,
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            lastLifecycleResponse = "Starting network node through NodeService",
            taskRemovedWhileRunning = false,
        )
        appContext.startForegroundService(NodeService.startNetworkIntent(appContext))
    }

    fun stop(context: Context) {
        NodePolicyRepository.stopAutomaticScheduling(context.applicationContext)
        if (!mutableState.value.serviceActive) {
            val response = NativeBridge.nodeStatus().getOrElse {
                "JNI status error: ${it.message ?: "unknown error"}"
            }
            val status = parseNodeStatus(response)
            if (status.state == "Stopped" || status.state == "Failed") {
                publishStopped(status, response)
                return
            }
        }
        context.applicationContext.startService(NodeService.stopIntent(context.applicationContext))
    }

    fun pause(context: Context) {
        NodePolicyRepository.setSuspended(context.applicationContext, true)
        context.applicationContext.startService(NodeService.pauseIntent(context.applicationContext))
    }

    fun setPowerPolicy(context: Context, policy: NodePowerPolicy) {
        val appContext = context.applicationContext
        NodePolicyRepository.setPower(appContext, policy)
        if (policy == NodePowerPolicy.Manual) {
            if (mutableState.value.serviceActive) {
                appContext.startService(NodeService.reconcilePolicyIntent(appContext))
            }
        } else {
            appContext.startForegroundService(NodeService.reconcilePolicyIntent(appContext))
        }
    }

    fun setNetworkDataPolicy(context: Context, policy: NetworkDataPolicy) {
        val appContext = context.applicationContext
        NodePolicyRepository.setNetworkData(appContext, policy)
        if (mutableState.value.serviceActive) {
            appContext.startService(NodeService.reconcilePolicyIntent(appContext))
        } else if (NodePolicyRepository.state.value.automatic) {
            appContext.startForegroundService(NodeService.reconcilePolicyIntent(appContext))
        }
    }

    fun reportNotificationPermissionRequired() {
        mutableState.value = mutableState.value.copy(
            detail = "Notification permission is required before starting the node",
            lastLifecycleResponse = "Start cancelled because notification permission was denied",
        )
    }

    internal fun publishStarting(startedAtElapsedRealtimeMs: Long, mode: String) {
        mutableState.value = mutableState.value.copy(
            state = "Starting",
            detail = "The foreground service is starting the native node",
            serviceActive = true,
            mode = mode,
            peers = 0,
            startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
            taskRemovedWhileRunning = false,
        )
    }

    internal fun publishWaiting(detail: String, paused: Boolean = false) {
        mutableState.value = mutableState.value.copy(
            state = if (paused) "Paused" else "Waiting",
            detail = detail,
            serviceActive = true,
            mode = "Network",
            peers = 0,
            startedAtElapsedRealtimeMs = null,
            lastLifecycleResponse = detail,
        )
    }

    internal fun publishNativeStatus(
        response: String,
        serviceActive: Boolean,
        startedAtElapsedRealtimeMs: Long?,
    ): NodeUiState {
        val parsed = parseNodeStatus(response)
        val next = mutableState.value.copy(
            state = parsed.state,
            detail = parsed.detail,
            completedStartCycles = parsed.completedStartCycles,
            serviceActive = serviceActive,
            mode = parsed.mode ?: mutableState.value.mode,
            peers = parsed.peerCount,
            startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
            identityFingerprint = parsed.identityFingerprint,
            connectionAttempts = parsed.connectionAttempts,
            successfulConnections = parsed.successfulConnections,
            bytesSent = parsed.bytesSent,
            bytesReceived = parsed.bytesReceived,
            currentNetworkType = parsed.currentNetworkType,
            connectivityAvailable = parsed.connectivityAvailable,
            networkMetered = parsed.networkMetered,
            vpnActive = parsed.vpnActive,
            lastNetworkError = parsed.lastNetworkError,
        )
        mutableState.value = next
        return next
    }

    internal fun publishLifecycleResponse(response: String) {
        mutableState.value = mutableState.value.copy(lastLifecycleResponse = response)
    }

    internal fun publishPaused(status: NodeStatusSnapshot, response: String) {
        mutableState.value = mutableState.value.copy(
            state = "Paused",
            detail = "The node was paused through a graceful native shutdown",
            completedStartCycles = status.completedStartCycles,
            serviceActive = false,
            startedAtElapsedRealtimeMs = null,
            lastLifecycleResponse = response,
        )
    }

    internal fun publishStopped(status: NodeStatusSnapshot, response: String) {
        mutableState.value = mutableState.value.copy(
            state = "Stopped",
            detail = status.detail,
            completedStartCycles = status.completedStartCycles,
            serviceActive = false,
            startedAtElapsedRealtimeMs = null,
            lastLifecycleResponse = response,
        )
    }

    internal fun publishPolicyStopped(
        status: NodeStatusSnapshot,
        response: String,
        reason: String,
    ) {
        mutableState.value = mutableState.value.copy(
            state = "Stopped",
            detail = "Network mode stopped safely: $reason",
            completedStartCycles = status.completedStartCycles,
            serviceActive = false,
            startedAtElapsedRealtimeMs = null,
            lastLifecycleResponse = response,
            lastNetworkError = reason,
        )
    }

    internal fun publishFailure(detail: String, response: String) {
        mutableState.value = mutableState.value.copy(
            state = "Failed",
            detail = detail,
            serviceActive = false,
            startedAtElapsedRealtimeMs = null,
            lastLifecycleResponse = response,
        )
    }

    internal fun publishShutdownTimeout(detail: String, response: String) {
        mutableState.value = mutableState.value.copy(
            state = "Stopping",
            detail = detail,
            serviceActive = true,
            lastLifecycleResponse = response,
        )
    }

    internal fun publishTaskRemoved() {
        mutableState.value = mutableState.value.copy(
            detail = "The Activity task was removed; the foreground node continues running",
            taskRemovedWhileRunning = true,
        )
    }

    internal fun publishUnexpectedServiceDestruction(status: NodeStatusSnapshot) {
        mutableState.value = mutableState.value.copy(
            state = status.state,
            detail = "NodeService was destroyed and requested cooperative native shutdown",
            completedStartCycles = status.completedStartCycles,
            serviceActive = false,
            startedAtElapsedRealtimeMs = null,
        )
    }

    fun refreshStorage(context: Context) {
        val response = NativeBridge.storageStatus(androidNodeConfigJson(context)).getOrElse {
            mutableStorageState.value = StorageUiState(
                detail = "JNI storage error: ${it.message ?: "unknown error"}",
            )
            return
        }
        mutableStorageState.value = parseStorageStatus(response)
    }

    internal fun publishConnectivity(snapshot: ConnectivitySnapshot, response: String) {
        mutableState.value = mutableState.value.copy(
            currentNetworkType = snapshot.networkType,
            connectivityAvailable = snapshot.available,
            networkMetered = snapshot.metered,
            vpnActive = snapshot.vpn,
            lastLifecycleResponse = response,
        )
    }
}

internal data class NodeStatusSnapshot(
    val state: String,
    val detail: String,
    val completedStartCycles: Long,
    val identityFingerprint: String?,
    val mode: String? = null,
    val peerCount: Int = 0,
    val connectionAttempts: Long = 0,
    val successfulConnections: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val currentNetworkType: String = "Unavailable",
    val connectivityAvailable: Boolean = false,
    val networkMetered: Boolean = false,
    val vpnActive: Boolean = false,
    val lastNetworkError: String? = null,
)

internal fun parseNodeStatus(response: String): NodeStatusSnapshot {
    return runCatching {
        val envelope = JSONObject(response)
        if (!envelope.optBoolean("ok")) {
            val error = envelope.optJSONObject("error")
            return@runCatching NodeStatusSnapshot(
                state = "Failed",
                detail = error?.optString("message") ?: "Native status request failed",
                completedStartCycles = 0,
                identityFingerprint = null,
            )
        }
        val data = envelope.getJSONObject("data")
        NodeStatusSnapshot(
            state = data.optString("state", "Unknown"),
            detail = data.optString("detail", "No detail"),
            completedStartCycles = data.optLong("completedStartCycles"),
            identityFingerprint = data.optionalString("identityFingerprint"),
            mode = data.optionalString("mode"),
            peerCount = data.optInt("peerCount"),
            connectionAttempts = data.optLong("connectionAttempts"),
            successfulConnections = data.optLong("successfulConnections"),
            bytesSent = data.optLong("bytesSent"),
            bytesReceived = data.optLong("bytesReceived"),
            currentNetworkType = data.optString("currentNetworkType", "Unavailable"),
            connectivityAvailable = data.optBoolean("connectivityAvailable"),
            networkMetered = data.optBoolean("networkMetered"),
            vpnActive = data.optBoolean("vpnActive"),
            lastNetworkError = data.optionalString("lastNetworkError"),
        )
    }.getOrElse { error ->
        NodeStatusSnapshot("Failed", error.message ?: response, 0, null)
    }
}

private fun parseStorageStatus(response: String): StorageUiState {
    return runCatching {
        val envelope = JSONObject(response)
        if (!envelope.optBoolean("ok")) {
            return@runCatching StorageUiState(
                detail = envelope.optJSONObject("error")?.optString("message")
                    ?: "Native storage measurement failed",
            )
        }
        val data = envelope.getJSONObject("data")
        StorageUiState(
            available = true,
            detail = "Android-private storage measured",
            persistentBytes = data.optLong("persistentBytes"),
            temporaryBytes = data.optLong("temporaryBytes"),
            identityBytes = data.optLong("identityBytes"),
            totalBytes = data.optLong("totalBytes"),
            identityFingerprint = data.optionalString("identityFingerprint"),
            identityOwnerOnly = data.optBoolean("identityOwnerOnly"),
            secretMaterialInLogs = data.optBoolean("secretMaterialInLogs"),
            layoutReady = data.optBoolean("layoutReady"),
            prototypeKeySecurityDebt = data.optBoolean("prototypeKeySecurityDebt", true),
        )
    }.getOrElse { error ->
        StorageUiState(detail = error.message ?: response)
    }
}

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else optString(name)

internal fun androidNodeConfigJson(
    context: Context,
    connectivity: ConnectivitySnapshot? = null,
    networkDataPolicy: NetworkDataPolicy = NetworkDataPolicy.UnmeteredOnly,
): String {
    val persistentRoot = File(context.filesDir, "freenet")
    val config = JSONObject()
        .put("filesDir", context.filesDir.absolutePath)
        .put("cacheDir", context.cacheDir.absolutePath)
        .put("noBackupFilesDir", context.noBackupFilesDir.absolutePath)
        .put("stateDirectory", File(persistentRoot, "state").absolutePath)
        .put("databaseDirectory", File(persistentRoot, "database/local").absolutePath)
        .put("contractDirectory", File(persistentRoot, "contracts/local").absolutePath)
        .put(
            "configurationDirectory",
            File(context.filesDir, "freenet/config").absolutePath,
        )
        .put("logDirectory", File(context.filesDir, "freenet/logs").absolutePath)
        .put(
            "identityDirectory",
            File(context.noBackupFilesDir, "freenet/identity").absolutePath,
        )
        .put("temporaryDirectory", File(context.cacheDir, "freenet/temporary").absolutePath)
        .put("websocketPort", 7509)
    if (connectivity != null) {
        config.put(
            "network",
            JSONObject()
                .put("allowMetered", networkDataPolicy == NetworkDataPolicy.AnyValidated)
                .put("connectivity", JSONObject(connectivity.toJson())),
        )
    }
    return config.toString()
}
