package org.freenet.androidnode

import android.os.BatteryManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodePolicyLifecycleInstrumentedTest {
    @Test
    fun chargingPauseResumeAlwaysAndStopFollowPersistedPolicies() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = AndroidConnectivityMonitor(context) {}.currentSnapshot()
        val battery = context.getSystemService(BatteryManager::class.java)
        assertTrue(connectivity.policyBlockReason(), connectivity.networkModeAllowed)
        assertTrue("This device proof requires the plugged-in device to be charging", battery.isCharging)

        NodeRepository.stop(context)
        awaitUiState("Stopped", SHORT_TIMEOUT_MS)
        NodeRepository.setNetworkDataPolicy(context, NetworkDataPolicy.UnmeteredOnly)

        try {
            NodeRepository.setPowerPolicy(context, NodePowerPolicy.Charging)
            awaitUiState("RunningNetwork", START_TIMEOUT_MS)
            assertEquals(NodePowerPolicy.Charging, NodePolicyRepository.state.value.power)

            NodeRepository.pause(context)
            awaitUiState("Paused", SHUTDOWN_TIMEOUT_MS)
            assertTrue(NodePolicyRepository.state.value.suspendedByUser)
            assertEquals("Stopped", nativeState())

            NodeRepository.startNetwork(context)
            awaitUiState("RunningNetwork", START_TIMEOUT_MS)
            assertFalse(NodePolicyRepository.state.value.suspendedByUser)

            NodeRepository.setPowerPolicy(context, NodePowerPolicy.Always)
            awaitUiState("RunningNetwork", SHORT_TIMEOUT_MS)
            NodeRepository.setNetworkDataPolicy(context, NetworkDataPolicy.AnyValidated)
            assertEquals(
                NetworkDataPolicy.AnyValidated,
                NodePolicyRepository.state.value.networkData,
            )
        } finally {
            NodeRepository.stop(context)
            awaitUiState("Stopped", SHUTDOWN_TIMEOUT_MS)
            NodeRepository.setNetworkDataPolicy(context, NetworkDataPolicy.UnmeteredOnly)
        }

        assertEquals(NodePowerPolicy.Manual, NodePolicyRepository.state.value.power)
        assertFalse(NodePolicyRepository.state.value.suspendedByUser)
        assertEquals("Stopped", nativeState())
    }

    private fun awaitUiState(expected: String, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = NodeRepository.state.value
            if (state.state == expected) return
            if (state.state == "Failed") error(state.detail)
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Timed out waiting for $expected; current=${NodeRepository.state.value}")
    }

    private fun nativeState(): String = parseNodeStatus(
        NativeBridge.nodeStatus().getOrThrow(),
    ).state

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val SHORT_TIMEOUT_MS = 10_000L
        private const val START_TIMEOUT_MS = 90_000L
        private const val SHUTDOWN_TIMEOUT_MS = 30_000L
    }
}
