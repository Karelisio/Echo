package com.karelisio.echo.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.karelisio.echo.appContainer
import com.karelisio.echo.echo.EchoSender
import com.karelisio.echo.echo.messageRes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Bouton « Répondre » de la notification : renvoie un écho sans ouvrir l'appli. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.appContainer
        container.appScope.launch {
            try {
                val result = withTimeoutOrNull(SEND_TIMEOUT_MS) { container.sender.send() }
                    ?: EchoSender.Result.Failed(EchoSender.Error.Network)
                container.notifications.showReplyResult(context.getString(result.messageRes()))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val SEND_TIMEOUT_MS = 8_000L

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReplyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
