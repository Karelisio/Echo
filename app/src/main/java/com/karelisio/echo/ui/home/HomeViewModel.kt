package com.karelisio.echo.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.echo.AppContainer
import com.karelisio.echo.data.history.EchoEvent
import com.karelisio.echo.data.settings.EchoSettings
import com.karelisio.echo.echo.EchoMessage
import com.karelisio.echo.echo.EchoSender
import com.karelisio.echo.echo.messageRes
import com.karelisio.echo.vibration.VibrationPattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val settings: EchoSettings = EchoSettings(),
        val lastReceived: EchoEvent? = null,
        val lastSent: EchoEvent? = null,
        val lastSentAt: Long = 0L,
        /** Le relais ne connaît plus ce lien (désappairage manqué). */
        val pairMissing: Boolean = false,
    )

    sealed interface SendState {
        data object Idle : SendState
        data object Sending : SendState
        data object Sent : SendState
        data class Message(@param:StringRes val text: Int, val isError: Boolean) : SendState
    }

    private val pairMissing = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        container.settings.settings,
        container.history.lastReceived,
        container.history.lastSent,
        container.settings.lastSentAt,
        pairMissing,
    ) { settings, lastReceived, lastSent, lastSentAt, missing ->
        UiState(settings, lastReceived, lastSent, lastSentAt, missing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    /**
     * Phrase en cours de saisie, `null` tant que les réglages ne sont pas chargés. État Compose
     * (et non un flux) : un champ de texte doit être mis à jour de façon synchrone.
     */
    var messageDraft by mutableStateOf<String?>(null)
        private set
    private var saveMessageJob: Job? = null

    init {
        viewModelScope.launch { pairMissing.value = container.pairing.isKnownByRelay() == false }
        viewModelScope.launch { messageDraft = container.settings.current().message }
    }

    fun onMessageChange(text: String) {
        val draft = EchoMessage.draft(text)
        messageDraft = draft
        saveMessageJob?.cancel()
        // Portée de l'appli : la phrase est enregistrée même si l'on quitte l'écran aussitôt.
        saveMessageJob = container.appScope.launch {
            delay(MESSAGE_SAVE_DELAY_MS)
            container.settings.setMessage(draft)
        }
    }

    fun send() {
        if (_sendState.value == SendState.Sending) return
        _sendState.value = SendState.Sending
        // Phrase tapée juste avant l'envoi, pas encore enregistrée : elle part avec cet écho.
        val unsavedMessage = messageDraft.takeIf { saveMessageJob?.isActive == true }
        saveMessageJob?.cancel()
        // Portée de l'appli : quitter l'écran n'annule pas un envoi en cours.
        container.appScope.launch {
            val result = try {
                if (unsavedMessage != null) container.settings.setMessage(unsavedMessage)
                container.sender.send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EchoSender.Result.Failed(EchoSender.Error.Server)
            }
            val next = when (result) {
                is EchoSender.Result.Sent -> SendState.Sent
                is EchoSender.Result.Cooldown -> SendState.Message(result.messageRes(), isError = false)
                else -> SendState.Message(result.messageRes(), isError = true)
            }
            _sendState.value = next
            if (result is EchoSender.Result.Failed && result.error == EchoSender.Error.PairMissing) pairMissing.value = true
            delay(if (next == SendState.Sent) 1_800 else 3_500)
            if (_sendState.value == next) _sendState.value = SendState.Idle
        }
    }

    fun selectEmoji(emoji: String) {
        viewModelScope.launch { container.settings.setEmoji(emoji) }
    }

    /** Sélectionne un motif et le fait ressentir immédiatement. */
    fun selectPattern(pattern: VibrationPattern) {
        container.vibrator.play(pattern, bypassDnd = true)
        viewModelScope.launch { container.settings.setPattern(pattern) }
    }

    fun forgetPairing() {
        viewModelScope.launch { container.pairing.forget() }
    }

    private companion object {
        const val MESSAGE_SAVE_DELAY_MS = 400L
    }
}
