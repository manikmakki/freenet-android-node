package org.freenet.androidnode

object NativeBridge {
    private val loadResult = runCatching {
        System.loadLibrary("freenet_android")
    }

    val isLoaded: Boolean
        get() = loadResult.isSuccess

    val loadError: String?
        get() = loadResult.exceptionOrNull()?.message

    external fun nativePing(): String

    external fun nativeBuildInfo(): String

    external fun nativeFreenetBuildInfo(): String

    external fun nativeStartLocalNode(configJson: String): String

    external fun nativeStopNode(): String

    external fun nativeGetNodeStatus(): String

    external fun nativeGetRecentLogs(maxEntries: Int): String

    external fun nativeRunContractProof(): String

    external fun nativeVerifyContractPersistence(): String

    external fun nativeGetContractProofStatus(): String

    fun ping(): Result<String> = withLoadedLibrary(::nativePing)

    fun buildInfo(): Result<String> = withLoadedLibrary(::nativeBuildInfo)

    fun freenetBuildInfo(): Result<String> = withLoadedLibrary(::nativeFreenetBuildInfo)

    fun startLocalNode(configJson: String): Result<String> =
        withLoadedLibrary { nativeStartLocalNode(configJson) }

    fun stopNode(): Result<String> = withLoadedLibrary(::nativeStopNode)

    fun nodeStatus(): Result<String> = withLoadedLibrary(::nativeGetNodeStatus)

    fun recentLogs(maxEntries: Int): Result<String> =
        withLoadedLibrary { nativeGetRecentLogs(maxEntries) }

    fun runContractProof(): Result<String> = withLoadedLibrary(::nativeRunContractProof)

    fun verifyContractPersistence(): Result<String> =
        withLoadedLibrary(::nativeVerifyContractPersistence)

    fun contractProofStatus(): Result<String> =
        withLoadedLibrary(::nativeGetContractProofStatus)

    private inline fun <T> withLoadedLibrary(block: () -> T): Result<T> {
        return loadResult.fold(
            onSuccess = { runCatching(block) },
            onFailure = { Result.failure(it) },
        )
    }
}
