package com.sflix

import android.content.Context
import android.provider.Settings
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Log

object LicenseClient {
    private const val TAG = "LicenseClient"
    private const val SERVER_URL = "http://159.223.82.116:3000"
    private const val PREF_NAME = "cs_premium"
    private const val PREF_KEY = "license_key"

    private var cachedStatus: String? = null
    private var cacheExpiry: Long = 0L
    private val actionThrottle = mutableMapOf<String, Long>()
    private var licenseBlocked = false
    private var blockMessage = ""
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setLicenseKey(context: Context, key: String) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, key.trim()).apply()
        resetCache()
    }

    fun getLicenseKey(): String? {
        return appContext
            ?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(
                appContext?.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    /**
     * Auto-discover license key from server using IP session.
     * Called when no key is stored locally.
     */
    private suspend fun discoverKey(): String? {
        return try {
            val response = app.get("$SERVER_URL/api/key-by-ip").text
            val json = tryParseJson<KeyByIpResponse>(response)
            if (json?.status == "active" && !json.key.isNullOrEmpty()) {
                appContext?.let { setLicenseKey(it, json.key) }
                Log.i(TAG, "Auto-discovered license key")
                json.key
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Key discovery failed: ${e.message}")
            null
        }
    }

    suspend fun checkLicense(
        pluginName: String,
        action: String = "OPEN",
        data: String? = null
    ): Boolean {
        val now = System.currentTimeMillis()

        val throttleKey = "$pluginName|$action"
        val throttleMs = when (action.uppercase()) {
            "HOME" -> 60_000L
            "SEARCH" -> 10_000L
            else -> 5_000L
        }
        val lastCheck = actionThrottle[throttleKey] ?: 0L
        if (now - lastCheck < throttleMs && cachedStatus == "active") return true
        actionThrottle[throttleKey] = now

        if (cachedStatus == "active" && now < cacheExpiry && action.uppercase() != "PLAY") {
            logActionAsync(pluginName, action, data)
            return true
        }

        // Get key  try stored key first, then auto-discover
        var key = getLicenseKey()
        if (key.isNullOrEmpty()) {
            key = discoverKey()
        }

        if (key.isNullOrEmpty()) {
            licenseBlocked = true
            blockMessage = "License key tidak ditemukan. Pastikan repo URL sudah ditambahkan di CloudStream."
            Log.w(TAG, "No license key available")
            return false
        }

        return try {
            val deviceId = getDeviceId()
            val encodedPlugin = java.net.URLEncoder.encode(pluginName, "UTF-8")
            val encodedAction = java.net.URLEncoder.encode(action, "UTF-8")
            val encodedData = java.net.URLEncoder.encode(data ?: "", "UTF-8")
            val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
            val encodedDevice = java.net.URLEncoder.encode(deviceId, "UTF-8")

            val url = "$SERVER_URL/api/check-ip?key=$encodedKey&device_id=$encodedDevice&plugin=$encodedPlugin&action=$encodedAction&data=$encodedData"
            val response = app.get(url).text
            val json = tryParseJson<CheckResponse>(response)

            if (json?.status == "active") {
                cachedStatus = "active"
                cacheExpiry = now + 300_000L
                licenseBlocked = false
                blockMessage = ""
                true
            } else {
                cachedStatus = "error"
                licenseBlocked = true
                blockMessage = json?.message ?: "License tidak valid"
                Log.w(TAG, "License check failed for $pluginName: $blockMessage")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "License check network error: ${e.message}")
            if (cachedStatus == "active" && now < cacheExpiry + 600_000L) {
                true
            } else {
                licenseBlocked = true
                blockMessage = "Tidak dapat memverifikasi lisensi. Periksa koneksi internet."
                false
            }
        }
    }

    suspend fun requireLicense(
        pluginName: String,
        action: String = "OPEN",
        data: String? = null
    ) {
        if (!checkLicense(pluginName, action, data)) {
            throw RuntimeException("[PREMIUM] $blockMessage")
        }
    }

    suspend fun checkPlay(pluginName: String, title: String): Boolean {
        return checkLicense(pluginName, "PLAY", title)
    }

    suspend fun requirePlay(pluginName: String, title: String) {
        requireLicense(pluginName, "PLAY", title)
    }

    suspend fun trackDownload(pluginName: String, title: String): Boolean {
        return checkLicense(pluginName, "DOWNLOAD", title)
    }

    fun isBlocked(): Boolean = licenseBlocked
    fun getBlockMessage(): String = blockMessage

    private fun logActionAsync(pluginName: String, action: String, data: String?) {
        val key = getLicenseKey() ?: return
        try {
            val encodedData = java.net.URLEncoder.encode(data ?: "", "UTF-8")
            val encodedPlugin = java.net.URLEncoder.encode(pluginName, "UTF-8")
            val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
            Thread {
                try {
                    val url = "$SERVER_URL/api/check-ip?key=$encodedKey&plugin=$encodedPlugin&action=$action&data=$encodedData"
                    java.net.URL(url).readText()
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }

    fun resetCache() {
        cachedStatus = null
        cacheExpiry = 0L
        licenseBlocked = false
        blockMessage = ""
        actionThrottle.clear()
    }

    data class CheckResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("status") val status: String,
        @com.fasterxml.jackson.annotation.JsonProperty("message") val message: String = ""
    )

    data class KeyByIpResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("status") val status: String,
        @com.fasterxml.jackson.annotation.JsonProperty("key") val key: String? = null
    )
}