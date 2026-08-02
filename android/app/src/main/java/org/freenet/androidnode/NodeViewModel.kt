package org.freenet.androidnode

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class NodeViewModel(application: Application) : AndroidViewModel(application) {
    val state = NodeRepository.state
    val storageState = NodeRepository.storageState

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
}
