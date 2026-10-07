package org.saathi.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Phone notifications for new messages, and the service that keeps Swarm listening nearby in the background. */
object SwarmAlerts {
    private const val MESSAGES = "messages"
    private const val LISTENING = "listening"
    const val LISTENING_ID = 1

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(LISTENING, "Listening nearby", NotificationManager.IMPORTANCE_LOW),
        ))
    }
    private fun open(context: Context) = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    /** One notification per conversation, replaced as more messages arrive. */
    fun message(context: Context, conversationId: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(conversationId, 2, NotificationCompat.Builder(context, MESSAGES)
                .setSmallIcon(R.drawable.ic_saathi).setContentTitle(title).setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).setContentIntent(open(context)).build())
        } catch (_: SecurityException) {}
    }
    fun clear(context: Context, conversationId: String) = NotificationManagerCompat.from(context).cancel(conversationId, 2)

    fun listening(context: Context) = NotificationCompat.Builder(context, LISTENING)
        .setSmallIcon(R.drawable.ic_saathi).setContentTitle("Swarm is listening nearby")
        .setContentText("Messages from people nearby keep arriving while Swarm is in the background.")
        .setOngoing(true).setContentIntent(open(context)).build()
}

/** Keeps the nearby connection (and search) alive while Swarm is in the background. Started while Swarm is open. */
class ListeningService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            ServiceCompat.startForeground(this, SwarmAlerts.LISTENING_ID, SwarmAlerts.listening(this),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        }.onFailure { stopSelf() }
        return START_STICKY
    }
}
