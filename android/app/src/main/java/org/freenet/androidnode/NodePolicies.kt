package org.freenet.androidnode

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NodePowerPolicy(val displayName: String) {
    Manual("Manual"),
    Charging("Charging"),
    Always("Always (best effort)"),
}

enum class NetworkDataPolicy(val displayName: String) {
    UnmeteredOnly("Unmetered only"),
    AnyValidated("Any validated network"),
}

data class NodePolicyState(
    val power: NodePowerPolicy = NodePowerPolicy.Manual,
    val networkData: NetworkDataPolicy = NetworkDataPolicy.UnmeteredOnly,
    val suspendedByUser: Boolean = false,
) {
    val automatic: Boolean
        get() = power != NodePowerPolicy.Manual

    fun powerEligible(charging: Boolean): Boolean = when (power) {
        NodePowerPolicy.Manual -> true
        NodePowerPolicy.Charging -> charging
        NodePowerPolicy.Always -> true
    }

    internal fun networkEligible(connectivity: ConnectivitySnapshot): Boolean =
        connectivity.available &&
            connectivity.validated &&
            (networkData == NetworkDataPolicy.AnyValidated || !connectivity.metered)
}

object NodePolicyRepository {
    private const val PREFERENCES_NAME = "node_policies"
    private const val POWER_KEY = "power_policy"
    private const val NETWORK_DATA_KEY = "network_data_policy"
    private const val SUSPENDED_KEY = "suspended_by_user"

    private val mutableState = MutableStateFlow(NodePolicyState())
    val state: StateFlow<NodePolicyState> = mutableState.asStateFlow()

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        mutableState.value = NodePolicyState(
            power = preferences.getString(POWER_KEY, null)
                ?.let { stored -> enumValues<NodePowerPolicy>().find { it.name == stored } }
                ?: NodePowerPolicy.Manual,
            networkData = preferences.getString(NETWORK_DATA_KEY, null)
                ?.let { stored -> enumValues<NetworkDataPolicy>().find { it.name == stored } }
                ?: NetworkDataPolicy.UnmeteredOnly,
            suspendedByUser = preferences.getBoolean(SUSPENDED_KEY, false),
        )
        initialized = true
    }

    fun setPower(context: Context, power: NodePowerPolicy) {
        initialize(context)
        persist(context, mutableState.value.copy(power = power, suspendedByUser = false))
    }

    fun setNetworkData(context: Context, networkData: NetworkDataPolicy) {
        initialize(context)
        persist(context, mutableState.value.copy(networkData = networkData))
    }

    fun setSuspended(context: Context, suspended: Boolean) {
        initialize(context)
        persist(context, mutableState.value.copy(suspendedByUser = suspended))
    }

    fun stopAutomaticScheduling(context: Context) {
        initialize(context)
        persist(
            context,
            mutableState.value.copy(
                power = NodePowerPolicy.Manual,
                suspendedByUser = false,
            ),
        )
    }

    private fun persist(context: Context, next: NodePolicyState) {
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(POWER_KEY, next.power.name)
            .putString(NETWORK_DATA_KEY, next.networkData.name)
            .putBoolean(SUSPENDED_KEY, next.suspendedByUser)
            .apply()
        mutableState.value = next
    }
}
