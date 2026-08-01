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

    fun ping(): Result<String> = withLoadedLibrary(::nativePing)

    fun buildInfo(): Result<String> = withLoadedLibrary(::nativeBuildInfo)

    fun freenetBuildInfo(): Result<String> = withLoadedLibrary(::nativeFreenetBuildInfo)

    private inline fun <T> withLoadedLibrary(block: () -> T): Result<T> {
        return loadResult.fold(
            onSuccess = { runCatching(block) },
            onFailure = { Result.failure(it) },
        )
    }
}
