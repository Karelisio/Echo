package com.karelisio.echo.ui.pairing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.echo.AppContainer
import com.karelisio.echo.R
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.data.relay.RelayException
import com.karelisio.echo.echo.messageRes
import com.karelisio.echo.echo.toSendError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PairingViewModel(private val container: AppContainer) : ViewModel() {

    enum class Mode { Choice, Join }

    data class UiState(
        val mode: Mode = Mode.Choice,
        val busy: Boolean = false,
        @param:StringRes val error: Int? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val pairing: StateFlow<PairingState?> = container.pairing.state

    fun showJoin() {
        _ui.value = UiState(mode = Mode.Join)
    }

    fun backToChoice() {
        _ui.value = UiState(mode = Mode.Choice)
    }

    fun createCode() = run { container.pairing.createCode() }

    /** Abandonne le code affiché et en demande un nouveau (code expiré). */
    fun renewCode() = run {
        container.pairing.cancelPending()
        container.pairing.createCode()
    }

    fun cancelCode() {
        viewModelScope.launch { container.pairing.cancelPending() }
        _ui.value = UiState()
    }

    fun join(code: String) = run { container.pairing.join(code) }

    /** Téléphone A : vérifie périodiquement si l'autre a rejoint (en plus du message FCM). */
    suspend fun pollPending() {
        runCatching { container.pairing.refreshPending() }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    private fun run(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val error = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                e.toMessage()
            } catch (e: Exception) {
                // Pas de jeton FCM (services Google Play absents ou injoignables).
                R.string.error_fcm_token
            }
            _ui.value = _ui.value.copy(busy = false, error = error)
        }
    }

    @StringRes
    private fun RelayException.toMessage(): Int = when (code) {
        "code_invalid" -> R.string.error_code_invalid
        "code_used" -> R.string.error_code_used
        "same_device" -> R.string.error_same_device
        "rate_limited" -> R.string.error_pairing_rate_limited
        else -> toSendError().messageRes()
    }
}
