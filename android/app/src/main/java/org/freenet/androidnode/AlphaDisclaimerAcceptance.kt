package org.freenet.androidnode

import android.content.Context

object AlphaDisclaimerAcceptance {
    private const val PREFERENCES_NAME = "alpha_disclaimer"
    private const val ACCEPTED_VERSION_CODE_KEY = "accepted_version_code"
    private const val NO_ACCEPTED_VERSION = -1L

    fun isAccepted(
        context: Context,
        versionCode: Long = currentVersionCode(context),
    ): Boolean = acceptedVersionCode(context) == versionCode

    fun accept(
        context: Context,
        versionCode: Long = currentVersionCode(context),
    ) {
        preferences(context)
            .edit()
            .putLong(ACCEPTED_VERSION_CODE_KEY, versionCode)
            .apply()
    }

    fun clear(context: Context) {
        preferences(context).edit().remove(ACCEPTED_VERSION_CODE_KEY).apply()
    }

    private fun acceptedVersionCode(context: Context): Long =
        preferences(context).getLong(ACCEPTED_VERSION_CODE_KEY, NO_ACCEPTED_VERSION)

    private fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
}
