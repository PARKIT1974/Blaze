package com.dramaid

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Log
import java.security.MessageDigest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object LicenseClient {
    private const val TAG = "LicenseClient"
    private val SERVER_URL = com.premium.Config.SERVER_URL
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

    private fun getHardwareHash(): String {
        val hwInfo = "${Build.BOARD}${Build.BRAND}${Build.DEVICE}${Build.HARDWARE}${Build.MANUFACTURER}${Build.MODEL}${Build.PRODUCT}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(hwInfo.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(8)
    }

    private fun getDeviceId(): String {
        val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?: return "unknown"
        
        // 1. Check if we already have a persistent device UUID
        var deviceId = prefs.getString("device_uuid", null)
        if (!deviceId.isNullOrEmpty() && deviceId != "unknown") return deviceId
        
        // 2. Build deterministic Device ID based on Hardware Hash and Android ID
        var finalAndroidId = "unknown"
        try {
            val aId = Settings.Secure.getString(appContext?.contentResolver, Settings.Secure.ANDROID_ID)
            if (!aId.isNullOrEmpty() && aId != "unknown" && aId.length >= 8) {
                finalAndroidId = aId
            }
        } catch (_: Exception) {}
        
        val hwHash = getHardwareHash()
        deviceId = "$finalAndroidId-$hwHash"
        
        prefs.edit().putString("device_uuid", deviceId).apply()
        
        Log.i(TAG, "Generated persistent hardware device ID: $deviceId")
        return deviceId
    }

    private fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }.take(100)
    }

    /**
     * Auto-discover license key from server using Cookie session.
     * Called when no key is stored locally.
     */
    private suspend fun discoverKey(): String? {
        return try {
            val deviceId = getDeviceId()
            val response = app.get("$SERVER_URL/api/discover?device_id=$deviceId").text
            val json = tryParseJson<KeyByIpResponse>(response)
            if (json?.status == "active" && !json.key.isNullOrEmpty()) {
                appContext?.let { setLicenseKey(it, json.key) }
                Log.i(TAG, "Auto-discovered license key via device lookup")
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

        // Get key: try stored key first, then auto-discover
        var key = getLicenseKey()
        if (key.isNullOrEmpty()) {
            key = discoverKey()
        }

        if (key.isNullOrEmpty()) {
            licenseBlocked = true
            blockMessage = "Lisensi tidak ditemukan. Pastikan repo URL premium sudah ditambahkan."
            Log.w(TAG, "No license key available")
            return false
        }

        return try {
            val deviceId = getDeviceId()
            val deviceModel = getDeviceModel()
            
            val cleanPlugin = pluginName.replace("\"", "")
            val cleanAction = action.replace("\"", "")
            val cleanData = (data ?: "").replace("\"", "")
            
            val jsonPayload = """{"key":"$key","device_id":"$deviceId","device_model":"${deviceModel.replace("\"", "")}","plugin_name":"$cleanPlugin","action":"$cleanAction","data":"$cleanData"}"""

            val url = "$SERVER_URL/api/verify_activity"
            val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post(
                url,
                requestBody = body
            ).text
            
            val json = tryParseJson<CheckResponse>(response)

            if (json?.status == "active" || json?.status == "success") {
                cachedStatus = "active"
                cacheExpiry = now + 300_000L // 5 minutes cache
                licenseBlocked = false
                blockMessage = ""
                true
            } else {
                cachedStatus = "error"
                licenseBlocked = true
                blockMessage = json?.message ?: "Lisensi tidak valid atau perangkat diblokir"
                Log.w(TAG, "License check failed for $pluginName: $blockMessage")

                val reason = json?.reason ?: ""
                if (reason == "not_found" || reason == "revoked") {
                    appContext?.let { 
                        it.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit().remove(PREF_KEY).apply()
                    }
                    Log.i(TAG, "License invalidated by server. Cleared local key to force discovery.")
                }
                
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "License check network error: ${e.message}")
            if (cachedStatus == "active" && now < cacheExpiry + 600_000L) {
                true // Grace period of 10 extra minutes if previously active
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
        val deviceId = getDeviceId()
        val deviceModel = getDeviceModel()
        GlobalScope.launch {
            try {
                val cleanPlugin = pluginName.replace("\"", "\\\"")
                val cleanAction = action.replace("\"", "\\\"")
                val cleanData = data?.replace("\"", "\\\"") ?: ""
                
                val jsonPayload = """
                    {
                        "key": "$key",
                        "device_id": "$deviceId",
                        "device_model": "$deviceModel",
                        "plugin_name": "$cleanPlugin",
                        "action": "$cleanAction",
                        "data": "$cleanData"
                    }
                """.trimIndent()
                
                val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
                app.post(
                    "$SERVER_URL/api/verify_activity",
                    requestBody = body
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log action async: ${e.message}")
            }
        }
    }

    fun resetCache() {
        cachedStatus = null
        cacheExpiry = 0L
        licenseBlocked = false
        blockMessage = ""
        actionThrottle.clear()
    }

    data class CheckResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("message") val message: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("reason") val reason: String? = null
    )

    data class KeyByIpResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("key") val key: String? = null
    )
}
