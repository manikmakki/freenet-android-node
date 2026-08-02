package org.freenet.androidnode

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NodeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleMutex = Mutex()

    private lateinit var nodeNotificationManager: NodeNotificationManager
    private lateinit var connectivityMonitor: AndroidConnectivityMonitor
    private var statusJob: Job? = null
    private var foregroundStarted = false
    private var shutdownCompleted = false
    private var startedAtElapsedRealtimeMs: Long? = null
    private var runningMode = "Local"
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NodeService created")
        nodeNotificationManager = NodeNotificationManager(this)
        nodeNotificationManager.ensureChannel()
        connectivityMonitor = AndroidConnectivityMonitor(this) { snapshot ->
            serviceScope.launch { handleConnectivityChanged(snapshot) }
        }
        connectivityMonitor.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        Log.i(TAG, "NodeService command action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START_LOCAL -> {
                runningMode = "Local"
                startForegroundImmediately(runningMode)
                serviceScope.launch {
                    lifecycleMutex.withLock { startNode(startId, networkMode = false) }
                }
            }

            ACTION_START_NETWORK -> {
                runningMode = "Network"
                startForegroundImmediately(runningMode)
                serviceScope.launch {
                    lifecycleMutex.withLock { startNode(startId, networkMode = true) }
                }
            }

            ACTION_PAUSE -> serviceScope.launch {
                lifecycleMutex.withLock { shutDownNode(startId, paused = true) }
            }

            ACTION_STOP -> serviceScope.launch {
                lifecycleMutex.withLock { shutDownNode(startId, paused = false) }
            }

            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        NodeRepository.publishTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "NodeService destroyed shutdownCompleted=$shutdownCompleted")
        connectivityMonitor.unregister()
        statusJob?.cancel()
        if (!shutdownCompleted) {
            val response = NativeBridge.stopNode().getOrElse {
                "JNI error while destroying NodeService: ${it.message ?: "unknown error"}"
            }
            NodeRepository.publishLifecycleResponse(response)
            val status = NativeBridge.nodeStatus()
                .map(::parseNodeStatus)
                .getOrElse { NodeStatusSnapshot("Failed", it.message ?: response, 0, null) }
            NodeRepository.publishUnexpectedServiceDestruction(status)
        }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        nodeNotificationManager.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundImmediately(mode: String) {
        if (startedAtElapsedRealtimeMs == null) {
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }
        NodeRepository.publishStarting(startedAtElapsedRealtimeMs!!, mode)
        startForeground(
            NodeNotificationManager.NOTIFICATION_ID,
            nodeNotificationManager.build(NodeRepository.state.value),
        )
        foregroundStarted = true
    }

    private suspend fun startNode(startId: Int, networkMode: Boolean) {
        shutdownCompleted = false
        val connectivity = connectivityMonitor.currentSnapshot()
        if (networkMode && !connectivity.networkModeAllowed) {
            val detail = connectivity.policyBlockReason()
            NodeRepository.publishFailure(detail, "NETWORK_POLICY_BLOCKED: $detail")
            shutdownCompleted = true
            finishService(startId)
            return
        }
        val response = withContext(Dispatchers.IO) {
            val configJson = androidNodeConfigJson(
                this@NodeService,
                connectivity.takeIf { networkMode },
            )
            val result = if (networkMode) {
                NativeBridge.startNetworkNode(configJson)
            } else {
                NativeBridge.startLocalNode(configJson)
            }
            result.getOrElse {
                "JNI error: ${it.message ?: "unknown error"}"
            }
        }
        NodeRepository.publishLifecycleResponse(response)
        if (!responseIsSuccessful(response)) {
            val nativeStatus = NativeBridge.nodeStatus()
                .map(::parseNodeStatus)
                .getOrElse { NodeStatusSnapshot("Failed", it.message ?: response, 0, null) }
            if (nativeStatus.state !in ACTIVE_NATIVE_STATES) {
                NodeRepository.publishFailure(nativeStatus.detail, response)
                shutdownCompleted = true
                finishService(startId)
                return
            }
        }
        beginStatusUpdates()
    }

    private suspend fun handleConnectivityChanged(snapshot: ConnectivitySnapshot) {
        val response = withContext(Dispatchers.IO) {
            NativeBridge.updateConnectivity(snapshot.toJson()).getOrElse {
                "JNI connectivity error: ${it.message ?: "unknown error"}"
            }
        }
        NodeRepository.publishConnectivity(snapshot, response)

        val policyProhibitsActiveNetwork = snapshot.available &&
            (snapshot.vpn || !snapshot.wifi || snapshot.metered)
        if (
            runningMode == "Network" &&
            policyProhibitsActiveNetwork &&
            NodeRepository.state.value.state in ACTIVE_NATIVE_STATES
        ) {
            lifecycleMutex.withLock {
                shutDownNode(
                    latestStartId,
                    paused = false,
                    policyReason = snapshot.policyBlockReason(),
                )
            }
        }
    }

    private fun beginStatusUpdates() {
        statusJob?.cancel()
        statusJob = serviceScope.launch(Dispatchers.IO) {
            var lastNotificationSecond = -1L
            while (isActive) {
                val response = NativeBridge.nodeStatus().getOrElse {
                    "JNI error: ${it.message ?: "unknown error"}"
                }
                val state = NodeRepository.publishNativeStatus(
                    response = response,
                    serviceActive = true,
                    startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
                )
                val uptimeSecond = state.uptimeMs / 1_000
                if (uptimeSecond != lastNotificationSecond) {
                    nodeNotificationManager.update(state)
                    lastNotificationSecond = uptimeSecond
                }
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun shutDownNode(
        startId: Int,
        paused: Boolean,
        policyReason: String? = null,
    ) {
        statusJob?.cancelAndJoin()
        statusJob = null
        val response = withContext(Dispatchers.IO) {
            NativeBridge.stopNode().getOrElse {
                "JNI error: ${it.message ?: "unknown error"}"
            }
        }
        NodeRepository.publishLifecycleResponse(response)

        val deadline = SystemClock.elapsedRealtime() + SHUTDOWN_TIMEOUT_MS
        var finalStatus = NodeStatusSnapshot("Stopping", "Waiting for native shutdown", 0, null)
        while (SystemClock.elapsedRealtime() < deadline) {
            val statusResponse = NativeBridge.nodeStatus().getOrElse { response }
            finalStatus = runCatching { parseNodeStatus(statusResponse) }
                .getOrElse { NodeStatusSnapshot("Failed", it.message ?: statusResponse, 0, null) }
            NodeRepository.publishNativeStatus(
                response = statusResponse,
                serviceActive = true,
                startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
            )
            nodeNotificationManager.update(NodeRepository.state.value)
            if (finalStatus.state == "Stopped" || finalStatus.state == "Failed") {
                if (paused && finalStatus.state == "Stopped") {
                    NodeRepository.publishPaused(finalStatus, response)
                } else if (policyReason != null && finalStatus.state == "Stopped") {
                    NodeRepository.publishPolicyStopped(finalStatus, response, policyReason)
                } else {
                    NodeRepository.publishStopped(finalStatus, response)
                }
                shutdownCompleted = true
                finishService(startId)
                return
            }
            delay(STATUS_POLL_INTERVAL_MS)
        }

        NodeRepository.publishShutdownTimeout(
            "Timed out waiting for cooperative native shutdown; service remains foreground",
            response,
        )
        beginStatusUpdates()
    }

    private fun finishService(startId: Int) {
        if (!stopSelfResult(startId)) {
            Log.i(TAG, "Keeping NodeService for a newer command after startId=$startId")
            return
        }
        Log.i(TAG, "Stopping NodeService after native shutdown startId=$startId")
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        nodeNotificationManager.cancel()
    }

    private fun responseIsSuccessful(response: String): Boolean =
        runCatching { org.json.JSONObject(response).optBoolean("ok") }.getOrDefault(false)

    companion object {
        const val ACTION_START_LOCAL = "org.freenet.androidnode.action.START_LOCAL"
        const val ACTION_START_NETWORK = "org.freenet.androidnode.action.START_NETWORK"
        const val ACTION_PAUSE = "org.freenet.androidnode.action.PAUSE"
        const val ACTION_STOP = "org.freenet.androidnode.action.STOP"

        private const val STATUS_POLL_INTERVAL_MS = 250L
        private const val SHUTDOWN_TIMEOUT_MS = 30_000L
        private const val TAG = "FreenetNodeService"
        private val ACTIVE_NATIVE_STATES = setOf(
            "Starting",
            "RunningLocal",
            "RunningNetwork",
            "Stopping",
        )

        fun startLocalIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_START_LOCAL)

        fun startNetworkIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_START_NETWORK)

        fun pauseIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_PAUSE)

        fun stopIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_STOP)
    }
}
