package org.freenet.androidnode

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodePolicyInstrumentedTest {
    @Test
    fun unmeteredPolicyUsesCostStatusRatherThanTransportType() {
        for ((type, wifi, vpn) in listOf(
            Triple("Wi-Fi", true, false),
            Triple("Ethernet", false, false),
            Triple("VPN", false, true),
        )) {
            val snapshot = connectivity(type, wifi, metered = false, vpn = vpn)
            assertTrue(type, snapshot.isAllowed(NetworkDataPolicy.UnmeteredOnly))
        }
    }

    @Test
    fun meteredNetworkRequiresAnyValidatedPolicy() {
        val cellular = connectivity("Cellular", wifi = false, metered = true, vpn = false)

        assertFalse(cellular.isAllowed(NetworkDataPolicy.UnmeteredOnly))
        assertTrue(cellular.isAllowed(NetworkDataPolicy.AnyValidated))
    }

    @Test
    fun powerPoliciesModelManualChargingAndAlways() {
        assertTrue(NodePolicyState(power = NodePowerPolicy.Manual).powerEligible(false))
        assertFalse(NodePolicyState(power = NodePowerPolicy.Charging).powerEligible(false))
        assertTrue(NodePolicyState(power = NodePowerPolicy.Charging).powerEligible(true))
        assertTrue(NodePolicyState(power = NodePowerPolicy.Always).powerEligible(false))
    }

    private fun connectivity(
        type: String,
        wifi: Boolean,
        metered: Boolean,
        vpn: Boolean,
    ) = ConnectivitySnapshot(
        available = true,
        validated = true,
        wifi = wifi,
        metered = metered,
        vpn = vpn,
        networkType = type,
        activeNetwork = "policy-test-network",
    )
}
