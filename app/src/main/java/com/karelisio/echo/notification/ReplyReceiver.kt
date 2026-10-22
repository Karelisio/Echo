package com.karelisio.echo.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput
import com.karelisio.echo.R
import com.karelisio.echo.appContainer
import com.karelisio.echo.echo.EchoSender
import com.karelisio.echo.echo.messageRes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Actions de la notification : « Renvoyer un écho » (phrase des réglages) ou « Répondre » avec une
 * phrase saisie ou choisie directement dans la notification, sans ouvrir l'appli.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.appContainer
        // Phrase de cette réponse uniquement : celle des réglages ne change pas.
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
        container.appScope.launch {
            try {
                val result = withTimeoutOrNull(SEND_TIMEOUT_MS) { container.sender.send(text) }
                    ?: EchoSender.Result.Failed(EchoSender.Error.Network)
                container.notifications.showReplyResult(context.getString(result.messageRes()))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val SEND_TIMEOUT_MS = 8_000L
        private const val KEY_TEXT = "echo_reply_text"

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReplyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Saisie directe, avec les suggestions de l'appli en réponses rapides. */
        fun remoteInput(context: Context): RemoteInput = RemoteInput.Builder(KEY_TEXT)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .setChoices(context.resources.getStringArray(R.array.message_suggestions))
            .build()

        fun textPendingIntent(context: Context): PendingIntent {
            // Modifiable : le système y ajoute le texte saisi.
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, ReplyReceiver::class.java).setPackage(context.packageName),
                mutable or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
