package org.freenet.androidnode

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase6InstrumentedTest {
    @Test
    fun identityAndContractStatePersistAcrossOrdinaryRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NodeRepository.stop(context)
        awaitNodeState("Stopped")

        try {
            NodeRepository.startLocal(context)
            val firstRun = awaitNodeState("RunningLocal")
            val firstFingerprint = firstRun.getString("identityFingerprint")
            assertEquals(32, firstFingerprint.length)

            val firstStorage = storageStatus(context)
            assertEquals(firstFingerprint, firstStorage.getString("identityFingerprint"))
            assertTrue(firstStorage.getBoolean("identityOwnerOnly"))
            assertTrue(firstStorage.getBoolean("layoutReady"))
            assertFalse(firstStorage.getBoolean("secretMaterialInLogs"))
            assertTrue(firstStorage.getLong("persistentBytes") > 0)
            assertTrue(firstStorage.getLong("identityBytes") > 0)

            assertEnvelopeOk(NativeBridge.runContractProof().getOrThrow())
            val contract = awaitContractProof(persistenceRequired = false)
            assertEquals("phase4-updated-state", contract.getString("result"))

            NodeRepository.stop(context)
            awaitNodeState("Stopped")
            NodeRepository.startLocal(context)
            val secondRun = awaitNodeState("RunningLocal")
            assertEquals(firstFingerprint, secondRun.getString("identityFingerprint"))

            assertEnvelopeOk(NativeBridge.verifyContractPersistence().getOrThrow())
            val persisted = awaitContractProof(persistenceRequired = true)
            assertTrue(persisted.getBoolean("persistenceVerified"))

            val invalidConfig = JSONObject(androidNodeConfigJson(context))
                .put(
                    "databaseDirectory",
                    context.filesDir.resolve("freenet/outside-layout").absolutePath,
                )
                .toString()
            val rejected = JSONObject(NativeBridge.storageStatus(invalidConfig).getOrThrow())
            assertFalse(rejected.getBoolean("ok"))
            assertEquals("INVALID_PATH_LAYOUT", rejected.getJSONObject("error").getString("code"))
            assertNotEquals("", rejected.getJSONObject("error").getString("message"))

            Log.i(
                EVIDENCE_TAG,
                "PHASE6_RESULT identityFingerprint=$firstFingerprint " +
                    "identityOwnerOnly=true secretMaterialInLogs=false " +
                    "persistentBytes=${firstStorage.getLong("persistentBytes")} " +
                    "temporaryBytes=${firstStorage.getLong("temporaryBytes")} " +
                    "contractPersistence=true",
            )
        } finally {
            NodeRepository.stop(context)
            runCatching { awaitNodeState("Stopped") }
        }
    }

    private fun storageStatus(context: Context): JSONObject =
        envelopeData(NativeBridge.storageStatus(androidNodeConfigJson(context)).getOrThrow())

    private fun awaitNodeState(expected: String): JSONObject = awaitStatus(NODE_TIMEOUT_MS) {
        val data = envelopeData(NativeBridge.nodeStatus().getOrThrow())
        when (val state = data.getString("state")) {
            expected -> data
            "Failed" -> error("Native node failed: ${data.getString("detail")}")
            else -> null
        }
    }

    private fun awaitContractProof(persistenceRequired: Boolean): JSONObject =
        awaitStatus(CONTRACT_TIMEOUT_MS) {
            val data = envelopeData(NativeBridge.contractProofStatus().getOrThrow())
            when (val state = data.getString("state")) {
                "Failed" -> error("Contract proof failed: ${data.getString("detail")}")
                "Succeeded" -> {
                    if (!persistenceRequired || data.getBoolean("persistenceVerified")) data else null
                }
                else -> null
            }
        }

    private fun awaitStatus(timeoutMs: Long, read: () -> JSONObject?): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            read()?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
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
        const val EVIDENCE_TAG = "FreenetPhase6"
        const val POLL_INTERVAL_MS = 100L
        const val NODE_TIMEOUT_MS = 60_000L
        const val CONTRACT_TIMEOUT_MS = 120_000L
    }
}
