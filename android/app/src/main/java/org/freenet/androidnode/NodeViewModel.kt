package org.freenet.androidnode

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class NodeViewModel(application: Application) : AndroidViewModel(application) {
    val state = NodeRepository.state

    fun startLocalNode() {
        NodeRepository.startLocal(getApplication())
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

