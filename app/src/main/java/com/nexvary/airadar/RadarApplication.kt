package com.nexvary.airadar

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

class RadarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initFirebaseIfConfigured()
    }

    private fun initFirebaseIfConfigured() {
        val appId = BuildConfig.FIREBASE_APP_ID.trim()
        val apiKey = BuildConfig.FIREBASE_API_KEY.trim()
        val projectId = BuildConfig.FIREBASE_PROJECT_ID.trim()
        val senderId = BuildConfig.FIREBASE_SENDER_ID.trim()

        if (appId.isBlank() || apiKey.isBlank() || projectId.isBlank() || senderId.isBlank()) {
            Log.i("NEXVARY-AI-Radar", "Firebase not configured; local polling remains enabled.")
            return
        }

        val options = FirebaseOptions.Builder()
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .setProjectId(projectId)
            .setGcmSenderId(senderId)
            .build()

        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this, options)
        }

        FirebaseMessaging.getInstance()
            .subscribeToTopic("ai-radar")
            .addOnSuccessListener { Log.i("NEXVARY-AI-Radar", "Subscribed to ai-radar push topic.") }
            .addOnFailureListener { Log.w("NEXVARY-AI-Radar", "FCM topic subscription failed.", it) }
    }
}
