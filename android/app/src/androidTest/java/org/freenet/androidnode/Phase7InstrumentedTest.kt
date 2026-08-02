package org.freenet.androidnode

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase7InstrumentedTest {
    @Test
    fun rejectsCellularBeforeStartingNativeNetworkNode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NodeRepository.stop(context)
        awaitNodeState("Stopped", SHORT_TIMEOUT_MS)
        val cellular = ConnectivitySnapshot(
            available = true,
            validated = true,
            wifi = false,
            metered = true,
            vpn = false,
            networkType = "Cellular",
            activeNetwork = "policy-test-cellular",
        )

        val response = JSONObject(
            NativeBridge.startNetworkNode(androidNodeConfigJson(context, cellular)).getOrThrow(),
        )
        assertFalse(response.getBoolean("ok"))
        assertEquals("NETWORK_POLICY_BLOCKED", response.getJSONObject("error").getString("code"))
        assertEquals("Stopped", nodeStatus().state)
    }

    @Test
    fun connectsToDocumentedNetworkPeerAndStopsGracefully() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = AndroidConnectivityMonitor(context) {}.currentSnapshot()
        assertTrue(connectivity.policyBlockReason(), connectivity.networkModeAllowed)
        NodeRepository.stop(context)
        awaitNodeState("Stopped", SHORT_TIMEOUT_MS)

        try {
            NodeRepository.startNetwork(context)
            awaitNodeState("RunningNetwork", START_TIMEOUT_MS)
            val connected = awaitStatus(PEER_TIMEOUT_MS) {
                nodeStatus().takeIf { it.state == "RunningNetwork" && it.peerCount > 0 }
            }
            assertEquals("Network", connected.mode)
            assertTrue(connected.connectionAttempts >= 1)
            assertTrue(connected.successfulConnections >= 1)
            assertTrue(connected.bytesSent > 0)
            assertEquals("Wi-Fi", connected.currentNetworkType)
            assertFalse(connected.networkMetered)
            assertFalse(connected.vpnActive)

            Log.i(
                EVIDENCE_TAG,
                "PHASE7_CONNECTED peers=${connected.peerCount} " +
                    "attempts=${connected.connectionAttempts} " +
                    "successful=${connected.successfulConnections} " +
                    "bytesSent=${connected.bytesSent} bytesReceived=${connected.bytesReceived} " +
                    "network=${connected.currentNetworkType}",
            )
        } finally {
            NodeRepository.stop(context)
            awaitNodeState("Stopped", SHUTDOWN_TIMEOUT_MS)
        }
    }

    @Test
    fun maintainsPeerForThirtyMinutesOnWifi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = AndroidConnectivityMonitor(context) {}.currentSnapshot()
        assertTrue(connectivity.policyBlockReason(), connectivity.networkModeAllowed)
        NodeRepository.stop(context)
        awaitNodeState("Stopped", SHORT_TIMEOUT_MS)

        try {
            NodeRepository.startNetwork(context)
            awaitNodeState("RunningNetwork", START_TIMEOUT_MS)
            val first = awaitStatus(PEER_TIMEOUT_MS) { nodeStatus().takeIf { it.peerCount > 0 } }
            val deadline = SystemClock.elapsedRealtime() + STABILITY_DURATION_MS
            var samples = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                val status = nodeStatus()
                assertEquals(status.detail, "RunningNetwork", status.state)
                assertTrue("Peer connection dropped during stability window", status.peerCount > 0)
                assertEquals("Wi-Fi", status.currentNetworkType)
                samples += 1
                SystemClock.sleep(STABILITY_SAMPLE_INTERVAL_MS)
            }
            val final = nodeStatus()
            assertTrue(final.bytesSent >= first.bytesSent)
            assertTrue(final.bytesReceived >= first.bytesReceived)
            Log.i(
                EVIDENCE_TAG,
                "PHASE7_STABILITY durationMs=$STABILITY_DURATION_MS samples=$samples " +
                    "peers=${final.peerCount} bytesSent=${final.bytesSent} " +
                    "bytesReceived=${final.bytesReceived}",
            )
        } finally {
            NodeRepository.stop(context)
            awaitNodeState("Stopped", SHUTDOWN_TIMEOUT_MS)
        }
    }

    private fun awaitNodeState(expected: String, timeoutMs: Long): NodeStatusSnapshot =
        awaitStatus(timeoutMs) {
            val status = nodeStatus()
            when (status.state) {
                expected -> status
                "Failed" -> error("Native node failed: ${status.detail}")
                else -> null
            }
        }

    private fun nodeStatus(): NodeStatusSnapshot =
        parseNodeStatus(NativeBridge.nodeStatus().getOrThrow())

    private fun <T> awaitStatus(timeoutMs: Long, read: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            read()?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Timed out after ${timeoutMs}ms waiting for Phase 7 status")
    }

    private companion object {
        const val EVIDENCE_TAG = "FreenetPhase7"
        const val POLL_INTERVAL_MS = 250L
        const val SHORT_TIMEOUT_MS = 30_000L
        const val START_TIMEOUT_MS = 180_000L
        const val PEER_TIMEOUT_MS = 300_000L
        const val SHUTDOWN_TIMEOUT_MS = 45_000L
        const val STABILITY_DURATION_MS = 30L * 60L * 1_000L
        const val STABILITY_SAMPLE_INTERVAL_MS = 5_000L
    }
}
