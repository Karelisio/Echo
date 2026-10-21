package com.karelisio.echo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.karelisio.echo.data.history.EchoEvent
import com.karelisio.echo.data.history.HistoryRepository
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.data.pairing.PairingStore
import com.karelisio.echo.data.settings.SettingsStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

enum class WidgetStatus { Idle, Sending, Sent, Cooldown, Failed }

data class WidgetUiState(
    val paired: Boolean,
    val emoji: String,
    val lastReceived: EchoEvent?,
    val status: WidgetStatus,
    /** Instant de référence pour « il y a 5 min » (change à chaque rafraîchissement planifié). */
    val now: Long,
)

/** État affiché par les widgets et déclenchement de leurs mises à jour. */
class WidgetController(
    private val context: Context,
    settings: SettingsStore,
    pairingStore: PairingStore,
    private val history: HistoryRepository,
) {

    private val status = MutableStateFlow(WidgetStatus.Idle)
    private val clock = MutableStateFlow(System.currentTimeMillis())

    val currentStatus: WidgetStatus get() = status.value

    val state: Flow<WidgetUiState> = combine(
        settings.settings,
        pairingStore.state,
        history.lastReceived,
        status,
        clock,
    ) { settings, pairing, lastReceived, status, now ->
        WidgetUiState(
            paired = pairing is PairingState.Paired,
            emoji = settings.emoji,
            lastReceived = lastReceived,
            status = status,
            now = now,
        )
    }

    private val generation = AtomicInteger()

    /** Passe en « Envoi… » et renvoie le numéro de cet envoi (voir [setStatus]). */
    suspend fun startSending(): Int {
        val current = generation.incrementAndGet()
        publish(WidgetStatus.Sending)
        return current
    }

    /** N'applique [value] que si aucun envoi plus récent n'a démarré entre-temps. */
    suspend fun setStatus(value: WidgetStatus, forGeneration: Int) {
        if (generation.get() == forGeneration) publish(value)
    }

    private suspend fun publish(value: WidgetStatus) {
        status.value = value
        clock.value = System.currentTimeMillis()
        if (hasWidgets()) EchoWidget().updateAll(context)
    }

    /** Redessine les widgets (nouvel écho, temps écoulé…) et planifie le prochain rafraîchissement. */
    suspend fun refresh() {
        if (!hasWidgets()) {
            WidgetRefreshWorker.cancel(context)
            return
        }
        clock.value = System.currentTimeMillis()
        EchoWidget().updateAll(context)
        WidgetRefreshWorker.schedule(context, history.lastReceivedNow()?.timestamp)
    }

    private suspend fun hasWidgets(): Boolean =
        GlanceAppWidgetManager(context).getGlanceIds(EchoWidget::class.java).isNotEmpty()
}
