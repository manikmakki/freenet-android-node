package org.freenet.androidnode

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.BatteryManager
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
    private lateinit var batteryManager: BatteryManager
    private var statusJob: Job? = null
    private var foregroundStarted = false
    private var shutdownCompleted = false
    private var startedAtElapsedRealtimeMs: Long? = null
    private var runningMode = "Local"
    private var latestStartId = 0

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_POWER_CONNECTED ||
                intent?.action == Intent.ACTION_POWER_DISCONNECTED ||
                intent?.action == BatteryManager.ACTION_CHARGING ||
                intent?.action == BatteryManager.ACTION_DISCHARGING
            ) {
                serviceScope.launch {
                    lifecycleMutex.withLock { reconcilePolicy(latestStartId, explicitStart = false) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NodeService created")
        NodePolicyRepository.initialize(this)
        nodeNotificationManager = NodeNotificationManager(this)
        nodeNotificationManager.ensureChannel()
        batteryManager = getSystemService(BatteryManager::class.java)
        connectivityMonitor = AndroidConnectivityMonitor(this) { snapshot ->
            serviceScope.launch { handleConnectivityChanged(snapshot) }
        }
        connectivityMonitor.register()
        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BatteryManager.ACTION_CHARGING)
            addAction(BatteryManager.ACTION_DISCHARGING)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, powerFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(powerReceiver, powerFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        Log.i(TAG, "NodeService command action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START_LOCAL -> {
                NodePolicyRepository.stopAutomaticScheduling(this)
                runningMode = "Local"
                startForegroundImmediately(runningMode)
                serviceScope.launch {
                    lifecycleMutex.withLock { startNode(startId, networkMode = false) }
                }
            }

            ACTION_START_NETWORK -> {
                NodePolicyRepository.setSuspended(this, false)
                runningMode = "Network"
                startForegroundController("Evaluating node policies")
                serviceScope.launch {
                    lifecycleMutex.withLock { reconcilePolicy(startId, explicitStart = true) }
                }
            }

            ACTION_RECONCILE_POLICY -> {
                runningMode = "Network"
                startForegroundController("Evaluating node policies")
                serviceScope.launch {
                    lifecycleMutex.withLock { reconcilePolicy(startId, explicitStart = false) }
                }
            }

            ACTION_PAUSE -> serviceScope.launch {
                NodePolicyRepository.setSuspended(this@NodeService, true)
                lifecycleMutex.withLock {
                    val automatic = NodePolicyRepository.state.value.automatic
                    shutDownNode(
                        startId = startId,
                        paused = true,
                        keepController = automatic,
                        waitingDetail = "Node paused by the user",
                    )
                }
            }

            ACTION_STOP -> serviceScope.launch {
                NodePolicyRepository.stopAutomaticScheduling(this@NodeService)
                lifecycleMutex.withLock { shutDownNode(startId, paused = false) }
            }

            else -> {
                if (NodePolicyRepository.state.value.automatic) {
                    runningMode = "Network"
                    startForegroundController("Restoring the automatic node schedule")
                    serviceScope.launch {
                        lifecycleMutex.withLock { reconcilePolicy(startId, explicitStart = false) }
                    }
                } else {
                    shutdownCompleted = true
                    stopSelfResult(startId)
                }
            }
        }
        return if (NodePolicyRepository.state.value.automatic) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        NodeRepository.publishTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "NodeService destroyed shutdownCompleted=$shutdownCompleted")
        connectivityMonitor.unregister()
        runCatching { unregisterReceiver(powerReceiver) }
        statusJob?.cancel()
        if (!shutdownCompleted) {
            val response = NativeBridge.stopNode().getOrElse {
                "JNI error while destroying NodeService: ${it.message ?: "unknown error"}"
            }
            NodeRepository.publishLifecycleResponse(response)
            val status = nativeStatus(response)
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
        ensureForeground()
    }

    private fun startForegroundController(detail: String) {
        if (foregroundStarted) return
        startedAtElapsedRealtimeMs = null
        NodeRepository.publishWaiting(detail)
        ensureForeground()
    }

    private fun ensureForeground() {
        startForeground(
            NodeNotificationManager.NOTIFICATION_ID,
            nodeNotificationManager.build(NodeRepository.state.value),
        )
        foregroundStarted = true
    }

    private suspend fun reconcilePolicy(startId: Int, explicitStart: Boolean) {
        val policy = NodePolicyRepository.state.value
        val connectivity = connectivityMonitor.currentSnapshot()
        val active = nativeIsActive()

        if (policy.suspendedByUser) {
            if (active) {
                shutDownNode(
                    startId = startId,
                    paused = true,
                    keepController = policy.automatic,
                    waitingDetail = "Node paused by the user",
                )
            } else if (policy.automatic) {
                publishControllerState("Node paused by the user", paused = true)
            } else {
                finishService(startId)
            }
            return
        }

        if (policy.automatic && !policy.powerEligible(batteryManager.isCharging)) {
            val reason = "Waiting for the device to charge"
            if (active) {
                shutDownNode(startId, keepController = true, waitingDetail = reason)
            } else {
                publishControllerState(reason)
            }
            return
        }

        if (!policy.networkEligible(connectivity)) {
            val reason = connectivity.policyBlockReason(policy.networkData)
            if (policy.automatic) {
                if (active) {
                    shutDownNode(startId, keepController = true, waitingDetail = reason)
                } else {
                    publishControllerState(reason)
                }
            } else if (active) {
                shutDownNode(startId, policyReason = reason)
            } else if (explicitStart) {
                NodeRepository.publishFailure(reason, "NETWORK_POLICY_BLOCKED: $reason")
                shutdownCompleted = true
                finishService(startId)
            } else {
                finishService(startId)
            }
            return
        }

        if (active) return
        if (policy.power == NodePowerPolicy.Manual && !explicitStart) {
            shutdownCompleted = true
            finishService(startId)
            return
        }
        startNode(startId, networkMode = true, policy = policy)
    }

    private suspend fun startNode(
        startId: Int,
        networkMode: Boolean,
        policy: NodePolicyState = NodePolicyState(),
    ) {
        shutdownCompleted = false
        val connectivity = connectivityMonitor.currentSnapshot()
        if (networkMode && !connectivity.isAllowed(policy.networkData)) {
            val detail = connectivity.policyBlockReason(policy.networkData)
            if (policy.automatic) {
                publishControllerState(detail)
            } else {
                NodeRepository.publishFailure(detail, "NETWORK_POLICY_BLOCKED: $detail")
                shutdownCompleted = true
                finishService(startId)
            }
            return
        }
        startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        NodeRepository.publishStarting(startedAtElapsedRealtimeMs!!, runningMode)
        nodeNotificationManager.update(NodeRepository.state.value)
        val response = withContext(Dispatchers.IO) {
            val configJson = androidNodeConfigJson(
                this@NodeService,
                connectivity.takeIf { networkMode },
                policy.networkData,
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
            val nativeStatus = nativeStatus(response)
            if (nativeStatus.state !in ACTIVE_NATIVE_STATES) {
                if (networkMode && policy.automatic) {
                    publishControllerState(nativeStatus.detail)
                } else {
                    NodeRepository.publishFailure(nativeStatus.detail, response)
                    shutdownCompleted = true
                    finishService(startId)
                }
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
        if (runningMode == "Network") {
            lifecycleMutex.withLock { reconcilePolicy(latestStartId, explicitStart = false) }
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
        paused: Boolean = false,
        policyReason: String? = null,
        keepController: Boolean = false,
        waitingDetail: String? = null,
    ) {
        statusJob?.cancelAndJoin()
        statusJob = null
        if (!nativeIsActive()) {
            shutdownCompleted = true
            if (keepController) {
                publishControllerState(waitingDetail ?: "Waiting for node policy", paused)
            } else {
                val status = nativeStatus("Native node was already stopped")
                if (paused) {
                    NodeRepository.publishPaused(status, "Native node was already stopped")
                } else {
                    NodeRepository.publishStopped(status, "Native node was already stopped")
                }
                finishService(startId)
            }
            return
        }

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
                startedAtElapsedRealtimeMs = null
                shutdownCompleted = true
                if (keepController && finalStatus.state == "Stopped") {
                    publishControllerState(waitingDetail ?: "Waiting for node policy", paused)
                } else {
                    if (paused && finalStatus.state == "Stopped") {
                        NodeRepository.publishPaused(finalStatus, response)
                    } else if (policyReason != null && finalStatus.state == "Stopped") {
                        NodeRepository.publishPolicyStopped(finalStatus, response, policyReason)
                    } else {
                        NodeRepository.publishStopped(finalStatus, response)
                    }
                    finishService(startId)
                }
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

    private fun publishControllerState(detail: String, paused: Boolean = false) {
        startedAtElapsedRealtimeMs = null
        shutdownCompleted = true
        NodeRepository.publishWaiting(detail, paused)
        nodeNotificationManager.update(NodeRepository.state.value)
    }

    private fun nativeStatus(fallback: String): NodeStatusSnapshot = NativeBridge.nodeStatus()
        .map(::parseNodeStatus)
        .getOrElse { NodeStatusSnapshot("Failed", it.message ?: fallback, 0, null) }

    private fun nativeIsActive(): Boolean = nativeStatus("Native status unavailable").state in
        ACTIVE_NATIVE_STATES

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
        const val ACTION_RECONCILE_POLICY = "org.freenet.androidnode.action.RECONCILE_POLICY"
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

        fun reconcilePolicyIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_RECONCILE_POLICY)

        fun pauseIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_PAUSE)

        fun stopIntent(context: Context): Intent =
            Intent(context, NodeService::class.java).setAction(ACTION_STOP)
    }
}
