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
    private var statusJob: Job? = null
    private var foregroundStarted = false
    private var shutdownCompleted = false
    private var startedAtElapsedRealtimeMs: Long? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NodeService created")
        nodeNotificationManager = NodeNotificationManager(this)
        nodeNotificationManager.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "NodeService command action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START_LOCAL -> {
                startForegroundImmediately()
                serviceScope.launch {
                    lifecycleMutex.withLock { startLocalNode(startId) }
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

    private fun startForegroundImmediately() {
        if (startedAtElapsedRealtimeMs == null) {
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }
        NodeRepository.publishStarting(startedAtElapsedRealtimeMs!!)
        startForeground(
            NodeNotificationManager.NOTIFICATION_ID,
            nodeNotificationManager.build(NodeRepository.state.value),
        )
        foregroundStarted = true
    }

    private suspend fun startLocalNode(startId: Int) {
        shutdownCompleted = false
        val response = withContext(Dispatchers.IO) {
            NativeBridge.startLocalNode(androidNodeConfigJson(this@NodeService)).getOrElse {
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

    private suspend fun shutDownNode(startId: Int, paused: Boolean) {
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

        fun pauseIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_PAUSE)

        fun stopIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_STOP)
    }
}
