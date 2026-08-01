package org.freenet.androidnode

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase4InstrumentedTest {
    @Test
    fun wasmContractRoundTripPersistsAcrossNodeRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            NodeRepository.startLocal(context)
            awaitNodeState("RunningLocal")

            assertEnvelopeOk(NativeBridge.runContractProof().getOrThrow())
            val execution = awaitContractProof(persistenceRequired = false)
            assertEquals("phase4-updated-state", execution.getString("result"))
            assertTrue(execution.getLong("firstExecutionTimeUs") > 0)
            assertTrue(execution.getLong("subsequentExecutionTimeUs") > 0)

            NodeRepository.stop(context)
            awaitNodeState("Stopped")
            NodeRepository.startLocal(context)
            awaitNodeState("RunningLocal")

            assertEnvelopeOk(NativeBridge.verifyContractPersistence().getOrThrow())
            val persisted = awaitContractProof(persistenceRequired = true)
            assertEquals("phase4-updated-state", persisted.getString("result"))
            assertTrue(persisted.getLong("persistenceReadTimeUs") > 0)
            Log.i(EVIDENCE_TAG, "PHASE4_RESULT ${persisted}")
        } finally {
            NodeRepository.stop(context)
            runCatching { awaitNodeState("Stopped") }
        }
    }

    private fun awaitNodeState(expected: String): JSONObject {
        return awaitStatus(NODE_TIMEOUT_MS) {
            val data = envelopeData(NativeBridge.nodeStatus().getOrThrow())
            when (val state = data.getString("state")) {
                expected -> data
                "Failed" -> error("Native node failed: ${data.getString("detail")}")
                else -> {
                    Log.d(EVIDENCE_TAG, "Waiting for node $expected; currently $state")
                    null
                }
            }
        }
    }

    private fun awaitContractProof(persistenceRequired: Boolean): JSONObject {
        return awaitStatus(CONTRACT_TIMEOUT_MS) {
            val data = envelopeData(NativeBridge.contractProofStatus().getOrThrow())
            when (val state = data.getString("state")) {
                "Failed" -> error("Contract proof failed: ${data.getString("detail")}")
                "Succeeded" -> {
                    if (!persistenceRequired || data.getBoolean("persistenceVerified")) data else null
                }
                else -> {
                    Log.d(EVIDENCE_TAG, "Waiting for contract proof; currently $state")
                    null
                }
            }
        }
    }

    private fun awaitStatus(timeoutMs: Long, read: () -> JSONObject?): JSONObject {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("Timed out after ${timeoutMs}ms waiting for native status")
    }

    private fun assertEnvelopeOk(response: String): JSONObject = envelopeData(response)

    private fun envelopeData(response: String): JSONObject {
        val envelope = JSONObject(response)
        assertTrue(
            envelope.optJSONObject("error")?.toString() ?: response,
            envelope.getBoolean("ok"),
        )
        return envelope.getJSONObject("data")
    }

    private companion object {
        const val EVIDENCE_TAG = "FreenetPhase4"
        const val POLL_INTERVAL_MS = 100L
        const val NODE_TIMEOUT_MS = 60_000L
        const val CONTRACT_TIMEOUT_MS = 120_000L
    }
}
