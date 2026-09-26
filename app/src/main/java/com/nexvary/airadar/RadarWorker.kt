package com.nexvary.airadar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RadarWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val projects = RadarRepository(applicationContext).fetchAll()
        val prefs = applicationContext.getSharedPreferences("radar", Context.MODE_PRIVATE)
        val old = prefs.getStringSet("seen", emptySet()).orEmpty()
        val current = projects.take(120).map { it.id }.toSet()
        val fresh = projects.filter { it.id !in old }.take(5)
        prefs.edit().putStringSet("seen", current).apply()
        if (NotificationSettings.isEnabled(applicationContext) && old.isNotEmpty() && fresh.isNotEmpty()) {
            notifyNew(fresh)
        }
        Result.success()
    }.getOrElse { Result.retry() }

    private fun notifyNew(items: List<RadarProject>) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("new_ai", "New AI projects", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(applicationContext, "new_ai")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NEXVARY AI Radar • ${items.size} new")
            .setContentText(items.first().name)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(701, n)
    }
}
