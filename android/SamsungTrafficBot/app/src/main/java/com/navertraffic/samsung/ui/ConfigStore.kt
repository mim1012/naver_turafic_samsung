package com.navertraffic.samsung.ui

import android.content.Context

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("bot_config", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString("device_name", "").orEmpty()
        set(v) { prefs.edit().putString("device_name", v).apply() }

    var loopCount: Int
        get() = prefs.getInt("loop_count", 200)
        set(v) { prefs.edit().putInt("loop_count", v).apply() }

    var serverUrl: String
        get() = prefs.getString("server_url", "").orEmpty()
        set(v) { prefs.edit().putString("server_url", v).apply() }

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) { prefs.edit().putString("api_key", v).apply() }

    fun isConfigured(): Boolean = deviceName.isNotBlank()

    fun save(deviceName: String, loopCount: Int, serverUrl: String = this.serverUrl) {
        prefs.edit()
            .putString("device_name", deviceName)
            .putInt("loop_count", loopCount)
            .putString("server_url", serverUrl)
            .apply()
    }
}
