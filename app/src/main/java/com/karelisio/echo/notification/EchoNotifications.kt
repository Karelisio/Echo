package com.karelisio.echo.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.karelisio.echo.MainActivity
import com.karelisio.echo.R

class EchoNotifications(private val context: Context) {

    private val system = context.getSystemService(NotificationManager::class.java)
    private val compat = NotificationManagerCompat.from(context)

    /**
     * Canal dédié aux échos : silencieux (la vibration est jouée directement avec le motif reçu)
     * et autorisé à passer Ne pas déranger. Android n'applique setBypassDnd() que si l'appli a
     * l'accès « Ne pas déranger » : on recrée donc le canal à chaque reprise de l'appli pour
     * prendre en compte un accès accordé entre-temps (sans effet si l'utilisateur a modifié le canal).
     */
    fun ensureChannels() {
        val echoes = NotificationChannel(
            CHANNEL_ECHOES,
            context.getString(R.string.channel_echoes),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_echoes_desc)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val info = NotificationChannel(
            CHANNEL_INFO,
            context.getString(R.string.channel_info),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_info_desc) }
        system.createNotificationChannels(listOf(echoes, info))
    }

    fun echoChannelBypassesDnd(): Boolean = system.getNotificationChannel(CHANNEL_ECHOES)?.canBypassDnd() == true

    fun showEcho(emoji: String, partnerName: String, at: Long) {
        val title = if (partnerName.isBlank()) {
            context.getString(R.string.notif_echo_title, emoji)
        } else {
            context.getString(R.string.notif_echo_title_named, emoji, partnerName)
        }
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_echo,
            context.getString(R.string.notif_action_reply),
            ReplyReceiver.pendingIntent(context),
        ).build()
        val notification = baseBuilder(CHANNEL_ECHOES)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_echo_text))
            .setWhen(at)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .addAction(reply)
            .build()
        post(ID_ECHO, notification)
    }

    fun showTestEcho(at: Long) {
        val notification = baseBuilder(CHANNEL_ECHOES)
            .setContentTitle(context.getString(R.string.notif_test_title))
            .setContentText(context.getString(R.string.notif_test_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_test_text)))
            .setWhen(at)
            .setShowWhen(true)
            .setContentIntent(openAppIntent(MainActivity.DESTINATION_GUIDE))
            .build()
        post(ID_TEST, notification)
    }

    fun showReplyResult(text: String) {
        val notification = baseBuilder(CHANNEL_INFO)
            .setContentTitle(text)
            .setTimeoutAfter(REPLY_FEEDBACK_TIMEOUT_MS)
            .build()
        compat.cancel(ID_ECHO)
        post(ID_REPLY, notification)
    }

    fun showUnpaired() {
        val notification = baseBuilder(CHANNEL_INFO)
            .setContentTitle(context.getString(R.string.notif_unpaired_title))
            .setContentText(context.getString(R.string.notif_unpaired_text))
            .build()
        post(ID_INFO, notification)
    }

    private fun baseBuilder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_echo)
        .setColor(ContextCompat.getColor(context, R.color.echo_violet))
        .setContentIntent(openAppIntent(null))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)

    private fun openAppIntent(destination: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DESTINATION, destination)
        return PendingIntent.getActivity(
            context,
            destination?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun post(id: Int, notification: Notification) {
        // Avant Android 13 la permission n'existe pas (checkSelfPermission répondrait « refusée »).
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted && compat.areNotificationsEnabled()) compat.notify(id, notification)
    }

    companion object {
        const val CHANNEL_ECHOES = "echoes"
        const val CHANNEL_INFO = "info"
        private const val ID_ECHO = 1
        private const val ID_TEST = 2
        private const val ID_REPLY = 3
        private const val ID_INFO = 4
        private const val REPLY_FEEDBACK_TIMEOUT_MS = 4_000L
    }
}
