package org.freenet.androidnode

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.service.notification.StatusBarNotification
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
class Phase5InstrumentedTest {
    @Test
    fun foregroundServiceSurvivesBackgroundAndNotificationActionsStopGracefully() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        stopAnyExistingNode(context)
        try {
            NodeRepository.startLocal(context)
            awaitNodeState("RunningLocal")
            val firstNotification = awaitNotification(context, present = true)
            assertTrue(
                firstNotification!!.notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            )
            assertEquals(
                setOf("Pause", "Stop"),
                firstNotification.notification.actions.map { it.title.toString() }.toSet(),
            )

            val activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            activity.runOnUiThread(activity::finishAndRemoveTask)
            awaitCondition(NODE_TIMEOUT_MS, "task removal callback") {
                NodeRepository.state.value.taskRemovedWhileRunning
            }
            assertEquals("RunningLocal", currentNodeState())

            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            SystemClock.sleep(SCREEN_OFF_PROBE_MS)
            assertEquals("RunningLocal", currentNodeState())
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()

            notificationAction(firstNotification, "Pause").actionIntent.send()
            awaitNodeState("Stopped")
            awaitRepositoryState("Paused")
            awaitNotification(context, present = false)

            NodeRepository.startLocal(context)
            awaitNodeState("RunningLocal")
            val secondNotification = awaitNotification(context, present = true)!!
            notificationAction(secondNotification, "Stop").actionIntent.send()
            awaitNodeState("Stopped")
            awaitRepositoryState("Stopped")
            awaitNotification(context, present = false)

            SystemClock.sleep(NO_RESTART_PROBE_MS)
            assertEquals("Stopped", currentNodeState())
            assertFalse(NodeRepository.state.value.serviceActive)
            Log.i(
                EVIDENCE_TAG,
                "PHASE5_RESULT foreground=true screenOff=true pause=true stop=true " +
                    "startNotSticky=true state=${NodeRepository.state.value.state}",
            )
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            stopAnyExistingNode(context)
        }
    }

    private fun stopAnyExistingNode(context: Context) {
        NodeRepository.stop(context)
        runCatching { awaitNodeState("Stopped") }
    }

    private fun awaitNodeState(expected: String) {
        awaitCondition(NODE_TIMEOUT_MS, "native node state $expected") {
            when (val state = currentNodeState()) {
                expected -> true
                "Failed" -> error("Native node failed: ${currentNodeDetail()}")
                else -> false
            }
        }
    }

    private fun awaitRepositoryState(expected: String) {
        awaitCondition(NODE_TIMEOUT_MS, "repository state $expected") {
            NodeRepository.state.value.state == expected
        }
    }

    private fun awaitNotification(context: Context, present: Boolean): StatusBarNotification? {
        var found: StatusBarNotification? = null
        awaitCondition(NODE_TIMEOUT_MS, "foreground notification present=$present") {
            found = context.getSystemService(NotificationManager::class.java)
                .activeNotifications
                .firstOrNull { it.id == NodeNotificationManager.NOTIFICATION_ID }
            (found != null) == present
        }
        return found
    }

    private fun notificationAction(
        notification: StatusBarNotification,
        title: String,
    ) = notification.notification.actions.first { it.title.toString() == title }

    private fun currentNodeState(): String = nativeStatus().getString("state")

    private fun currentNodeDetail(): String = nativeStatus().getString("detail")

    private fun nativeStatus(): JSONObject {
        val response = NativeBridge.nodeStatus().getOrThrow()
        val envelope = JSONObject(response)
        assertTrue(envelope.optJSONObject("error")?.toString() ?: response, envelope.getBoolean("ok"))
        return envelope.getJSONObject("data")
    }

    private fun awaitCondition(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Timed out after ${timeoutMs}ms waiting for $description")
    }

    private companion object {
        const val EVIDENCE_TAG = "FreenetPhase5"
        const val POLL_INTERVAL_MS = 100L
        const val NODE_TIMEOUT_MS = 60_000L
        const val SCREEN_OFF_PROBE_MS = 1_500L
        const val NO_RESTART_PROBE_MS = 1_500L
    }
}
