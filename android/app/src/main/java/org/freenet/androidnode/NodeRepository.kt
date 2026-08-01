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

    fun startLocal(context: Context) {
        val appContext = context.applicationContext
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

    fun stop(context: Context) {
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
        context.applicationContext.startService(NodeService.pauseIntent(context.applicationContext))
    }

    fun reportNotificationPermissionRequired() {
        mutableState.value = mutableState.value.copy(
            detail = "Notification permission is required before starting the node",
            lastLifecycleResponse = "Start cancelled because notification permission was denied",
        )
    }

    internal fun publishStarting(startedAtElapsedRealtimeMs: Long) {
        mutableState.value = mutableState.value.copy(
            state = "Starting",
            detail = "The foreground service is starting the native node",
            serviceActive = true,
            mode = "Local",
            peers = 0,
            startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
            taskRemovedWhileRunning = false,
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
            mode = if (parsed.state == "RunningNetwork") "Network" else "Local",
            peers = 0,
            startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
            identityFingerprint = parsed.identityFingerprint,
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
}

internal data class NodeStatusSnapshot(
    val state: String,
    val detail: String,
    val completedStartCycles: Long,
    val identityFingerprint: String?,
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

internal fun androidNodeConfigJson(context: Context): String {
    val persistentRoot = File(context.filesDir, "freenet")
    return JSONObject()
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
        .toString()
}
