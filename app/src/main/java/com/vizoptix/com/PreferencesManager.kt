package com.vizoptix.com

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("VizOptixPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_API_KEY = "soniox_api_key"
        private const val KEY_SELECTED_APPS = "selected_apps"
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    // Store selected package names as a comma-separated string
    var selectedApps: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SELECTED_APPS, value).apply()

    fun isAppSelected(packageName: String): Boolean {
        return selectedApps.contains(packageName)
    }

    fun addSelectedApp(packageName: String) {
        val current = selectedApps.toMutableSet()
        current.add(packageName)
        selectedApps = current
    }

    fun removeSelectedApp(packageName: String) {
        val current = selectedApps.toMutableSet()
        current.remove(packageName)
        selectedApps = current
    }
}
