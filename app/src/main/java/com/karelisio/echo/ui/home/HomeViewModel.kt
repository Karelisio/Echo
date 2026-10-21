package com.karelisio.echo.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.echo.AppContainer
import com.karelisio.echo.data.history.EchoEvent
import com.karelisio.echo.data.settings.EchoSettings
import com.karelisio.echo.echo.EchoSender
import com.karelisio.echo.echo.messageRes
import com.karelisio.echo.vibration.VibrationPattern
import kotlinx.coroutines.CancellationException
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

    init {
        viewModelScope.launch { pairMissing.value = container.pairing.isKnownByRelay() == false }
    }

    fun send() {
        if (_sendState.value == SendState.Sending) return
        _sendState.value = SendState.Sending
        // Portée de l'appli : quitter l'écran n'annule pas un envoi en cours.
        container.appScope.launch {
            val result = try {
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
}
