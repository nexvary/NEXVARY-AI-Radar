package com.nexvary.airadar

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

object NotificationSettings {
    private const val PREFS = "radar"
    private const val KEY_ENABLED = "notifications_enabled"
    private const val TOPIC = "ai-radar"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()

        if (!enabled) {
            NotificationManagerCompat.from(context).cancelAll()
        }
        syncFirebaseTopic(context)
    }

    fun syncFirebaseTopic(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.i("NEXVARY-AI-Radar", "Firebase unavailable; notification preference saved locally.")
            return
        }

        val task = if (isEnabled(context)) {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
        } else {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC)
        }

        task.addOnSuccessListener {
            Log.i(
                "NEXVARY-AI-Radar",
                if (isEnabled(context)) "Subscribed to AI Radar notifications." else "Unsubscribed from AI Radar notifications."
            )
        }.addOnFailureListener {
            Log.w("NEXVARY-AI-Radar", "Could not synchronize notification topic.", it)
        }
    }
}
