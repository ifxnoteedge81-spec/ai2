package com.personal.aicomputer.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.personal.aicomputer.MainActivity
import com.personal.aicomputer.R
import android.app.PendingIntent

/**
 * Foreground service that keeps the process alive (with a visible notification) while an agent task runs.
 * Android may still stop it (battery limits, user force-stop); the task is then marked as interrupted.
 */
class AgentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.agent_channel_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(getString(R.string.agent_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "agent_tasks"
        private const val NOTIF_ID = 4101

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java)); true
        } catch (e: Exception) { false }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
