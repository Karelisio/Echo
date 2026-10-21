package com.karelisio.echo.echo

import com.karelisio.echo.data.history.EchoDirection
import com.karelisio.echo.data.history.HistoryRepository
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.data.pairing.PairingStore
import com.karelisio.echo.data.pairing.credentials
import com.karelisio.echo.data.settings.EchoSettings
import com.karelisio.echo.data.settings.SettingsStore
import com.karelisio.echo.notification.EchoNotifications
import com.karelisio.echo.vibration.EchoVibrator
import com.karelisio.echo.vibration.VibrationPattern
import com.karelisio.echo.widget.WidgetController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** Traitement d'un écho reçu par FCM : vibration, historique, notification, widget. */
class EchoReceiver(
    private val store: PairingStore,
    private val settings: SettingsStore,
    private val history: HistoryRepository,
    private val vibrator: EchoVibrator,
    private val notifications: EchoNotifications,
    private val widget: WidgetController,
    private val lastTestEchoAt: MutableStateFlow<Long?>,
) {

    /**
     * Appelé depuis le thread de travail de FirebaseMessagingService (jamais le thread principal) :
     * on peut bloquer, mais le système ne laisse que quelques secondes.
     */
    fun onEcho(data: Map<String, String>) = runBlocking {
        val state = store.ensureLoaded()
        val credentials = state.credentials ?: return@runBlocking
        val pairId = data["pairId"]
        // Écho d'un ancien appairage (jeton FCM réutilisé) : ignoré.
        if (pairId != null && pairId != credentials.pairId) return@runBlocking

        val now = System.currentTimeMillis()
        val sentAt = data["sentAt"]?.toLongOrNull()?.coerceAtMost(now) ?: now
        val emoji = sanitizeEmoji(data["emoji"])
        val pattern = VibrationPattern.fromId(data["pattern"])
        val current = settings.current()

        // La vibration d'abord : c'est l'essentiel, et elle doit partir même si la suite échoue.
        if (now - sentAt <= FRESHNESS_MS) vibrator.play(pattern, bypassDnd = current.vibrateInDnd)

        if (data["test"] == "1") {
            lastTestEchoAt.value = now
            notifications.showTestEcho(now)
            return@runBlocking
        }

        // L'autre a déjà envoyé un écho : l'appairage est forcément finalisé côté relais.
        if (state is PairingState.Pending) store.markPaired(credentials.pairId, now)

        history.record(EchoDirection.RECEIVED, emoji, pattern.id, sentAt)
        notifications.showEcho(emoji, current.partnerName, sentAt)
        widget.refresh()
    }

    companion object {
        /** Au-delà, l'écho est seulement noté (pas de vibration pour un message arrivé très en retard). */
        const val FRESHNESS_MS = 5 * 60_000L
        private const val MAX_EMOJI_LENGTH = 32

        fun sanitizeEmoji(raw: String?): String {
            val cleaned = raw.orEmpty().filterNot { it.isISOControl() }.trim().take(MAX_EMOJI_LENGTH)
            return cleaned.ifEmpty { EchoSettings.DEFAULT_EMOJI }
        }
    }
}
