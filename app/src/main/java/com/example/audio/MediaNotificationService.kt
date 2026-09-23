package com.example.audio

import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaNotificationService : NotificationListenerService() {

    companion object {
        const val TAG = "MediaNotificationService"
        const val ACTION_PLAYBACK_DETECTED = "com.example.action.MEDIA_PLAYBACK_DETECTED"
        
        // Static state to let the UI read the current playing track directly
        @Volatile
        var currentTrackTitle: String = ""
            private set

        @Volatile
        var currentTrackPackage: String = ""
            private set

        @Volatile
        var isServiceConnected: Boolean = false
            private set

        val isServiceConnectedFlow = MutableStateFlow(false)
        val currentTrackTitleFlow = MutableStateFlow("")
        val currentTrackPackageFlow = MutableStateFlow("")

        fun clearTrackInfo() {
            currentTrackTitle = ""
            currentTrackPackage = ""
            currentTrackTitleFlow.value = ""
            currentTrackPackageFlow.value = ""
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        isServiceConnected = true
        isServiceConnectedFlow.value = true
        Log.d(TAG, "Notification Listener bound")
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        isServiceConnectedFlow.value = false
        Log.d(TAG, "Notification Listener unbound")
        return super.onUnbind(intent)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        isServiceConnectedFlow.value = true
        Log.d(TAG, "Notification Listener connected successfully")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected = false
        isServiceConnectedFlow.value = false
        Log.d(TAG, "Notification Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        
        // We target major media and video streaming players
        val isTargetApp = packageName == "com.google.android.youtube" || 
                packageName == "com.disney.disneyplus" ||
                packageName == "com.netflix.mediaclient" ||
                packageName == "com.spotify.music" ||
                packageName == "org.videolan.vlc" ||
                packageName == "com.google.android.apps.youtube.music" ||
                packageName == "com.amazon.firetv.youtube" ||
                packageName.contains("video", ignoreCase = true) || 
                packageName.contains("music", ignoreCase = true) || 
                packageName.contains("player", ignoreCase = true)

        if (isTargetApp) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            // Ensure the notification represents actual playback metadata
            if (title.isNotBlank() && title != currentTrackTitle) {
                currentTrackTitle = title
                currentTrackPackage = packageName
                currentTrackTitleFlow.value = title
                currentTrackPackageFlow.value = packageName
                Log.d(TAG, "Detected playback on $packageName: $title ($text)")

                // Broadcast this title to trigger the automated Gemini Acoustic Scene Brain
                val intent = Intent(ACTION_PLAYBACK_DETECTED).apply {
                    putExtra("package", packageName)
                    putExtra("title", title)
                    putExtra("text", text)
                }
                sendBroadcast(intent)
            }
        }
    }
}
