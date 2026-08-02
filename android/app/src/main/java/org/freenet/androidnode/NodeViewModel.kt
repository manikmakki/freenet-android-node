package org.freenet.androidnode

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class NodeViewModel(application: Application) : AndroidViewModel(application) {
    init {
        NodePolicyRepository.initialize(application)
    }

    val state = NodeRepository.state
    val storageState = NodeRepository.storageState
    val policies = NodeRepository.policies

    fun startLocalNode() {
        NodeRepository.startLocal(getApplication())
    }

    fun startNetworkNode() {
        NodeRepository.startNetwork(getApplication())
    }

    fun stopNode() {
        NodeRepository.stop(getApplication())
    }

    fun pauseNode() {
        NodeRepository.pause(getApplication())
    }

    fun reportNotificationPermissionRequired() {
        NodeRepository.reportNotificationPermissionRequired()
    }

    fun setPowerPolicy(policy: NodePowerPolicy) {
        NodeRepository.setPowerPolicy(getApplication(), policy)
    }

    fun setNetworkDataPolicy(policy: NetworkDataPolicy) {
        NodeRepository.setNetworkDataPolicy(getApplication(), policy)
    }
}
