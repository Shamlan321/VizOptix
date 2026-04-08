package com.vizoptix.com

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class AppNotificationListener : NotificationListenerService() {

    private lateinit var prefs: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        Log.d("AppNotificationListener", "Service created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // Filter out our own notifications to avoid loops
        if (packageName == this.packageName) return
        
        if (prefs.isAppSelected(packageName)) {
            val extras = sbn.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            
            if (title.isNotBlank() || text.isNotBlank()) {
                val appName = getAppName(packageName)
                Log.d("AppNotificationListener", "Forwarding notification from $appName: $title - $text")
                
                // Forward to TranslationService
                // Using the singleton instance for simplicity as they run in the same process
                TranslationService.instance?.showNotificationOnEsp(appName, "$title: $text")
            }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
