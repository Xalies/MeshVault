package com.xalies.meshvault

import com.xalies.meshvault.BuildConfig

object AdManager {

    /**
     * Checks if the current app version is 1.0 or higher.
     * This is intended for debug or review builds to simulate an ad-free state.
     * @return True if the major version is 1 or greater, false otherwise.
     */
    fun isVersionReviewMode(): Boolean {
        return try {
            // Use the newly generated buildConfigField for reliability
            val versionName = BuildConfig.VERSION_NAME_VALUE
            // Extracts the first number (major version) from the version string.
            val majorVersion = versionName.split('.').firstOrNull()?.toIntOrNull() ?: 0
            majorVersion >= 1
        } catch (e: Exception) {
            // In case of any parsing error, default to false.
            false
        }
    }
}