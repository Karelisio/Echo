package com.karelisio.echo.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.karelisio.echo.appContainer
import com.karelisio.echo.echo.EchoSender
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Tap sur le widget : envoie un écho et affiche le résultat quelques secondes. */
class SendEchoAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = context.appContainer
        val widget = container.widget
        if (widget.currentStatus == WidgetStatus.Sending) return

        // Un nouveau tap pendant l'affichage du résultat précédent prend la main sur l'affichage.
        val generation = widget.startSending()
        try {
            val result = withTimeoutOrNull(SEND_TIMEOUT_MS) { container.sender.send() }
                ?: EchoSender.Result.Failed(EchoSender.Error.Network)
            val status = when (result) {
                is EchoSender.Result.Sent -> WidgetStatus.Sent
                is EchoSender.Result.Cooldown -> WidgetStatus.Cooldown
                is EchoSender.Result.Failed -> WidgetStatus.Failed
                EchoSender.Result.NotPaired -> WidgetStatus.Idle
            }
            widget.setStatus(status, generation)
            if (status != WidgetStatus.Idle) {
                delay(if (status == WidgetStatus.Sent) SENT_FEEDBACK_MS else ERROR_FEEDBACK_MS)
            }
        } finally {
            // Jamais d'état « Envoi… » bloqué, même après une exception ou une annulation.
            withContext(NonCancellable) { widget.setStatus(WidgetStatus.Idle, generation) }
        }
    }

    private companion object {
        // Le traitement d'un clic de widget dispose d'une dizaine de secondes au total.
        const val SEND_TIMEOUT_MS = 7_000L
        const val SENT_FEEDBACK_MS = 2_000L
        const val ERROR_FEEDBACK_MS = 2_500L
    }
}
