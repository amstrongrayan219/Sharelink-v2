package com.example.utils

import android.content.Context
import com.example.model.DeviceConnection
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("ShareLinkPrefs", Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val deviceListAdapter = moshi.adapter<List<DeviceConnection>>(
        Types.newParameterizedType(List::class.java, DeviceConnection::class.java)
    )

    var deviceName: String
        get() = prefs.getString("device_name", "") ?: ""
        set(value) = prefs.edit().putString("device_name", value).apply()

    fun getHistory(): List<DeviceConnection> {
        val json = prefs.getString("connection_history", null) ?: return emptyList()
        return try {
            deviceListAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addToHistory(device: DeviceConnection) {
        val current = getHistory().toMutableList()
        // Remove existing with same IP to avoid duplicates
        current.removeAll { it.ip == device.ip }
        current.add(0, device) // Add to top
        val trimmed = if (current.size > 10) current.take(10) else current
        try {
            val json = deviceListAdapter.toJson(trimmed)
            prefs.edit().putString("connection_history", json).apply()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
