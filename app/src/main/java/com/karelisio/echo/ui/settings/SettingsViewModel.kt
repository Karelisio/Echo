package com.karelisio.echo.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.echo.AppContainer
import com.karelisio.echo.R
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.data.relay.RelayException
import com.karelisio.echo.data.settings.EchoSettings
import com.karelisio.echo.echo.EchoSender
import com.karelisio.echo.echo.messageRes
import com.karelisio.echo.system.SystemSettings
import com.karelisio.echo.system.SystemStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface TestState {
        data object Idle : TestState
        data object Sending : TestState
        data class Waiting(val arrivesAt: Long) : TestState

        /** L'écho de test est arrivé : on demande si la vibration a été ressentie. */
        data object Received : TestState
        data object Confirmed : TestState
        data class Failed(@param:StringRes val message: Int) : TestState
    }

    enum class UnpairState { Idle, Working, Offline }

    val settings: StateFlow<EchoSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, EchoSettings())

    val pairing: StateFlow<PairingState?> = container.pairing.state

    val relayHost: String get() = container.relay.displayHost

    private val _system = MutableStateFlow(readSystem())
    val system: StateFlow<SystemStatus> = _system.asStateFlow()

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    private val _unpair = MutableStateFlow(UnpairState.Idle)
    val unpair: StateFlow<UnpairState> = _unpair.asStateFlow()

    fun refreshSystem() {
        container.notifications.ensureChannels()
        _system.value = readSystem()
    }

    fun setVibrateInDnd(enabled: Boolean) {
        viewModelScope.launch { container.settings.setVibrateInDnd(enabled) }
    }

    fun setPartnerName(name: String) {
        viewModelScope.launch { container.settings.setPartnerName(name) }
    }

    fun vibrateHere() {
        val current = settings.value
        container.vibrator.play(current.pattern, bypassDnd = current.vibrateInDnd)
    }

    /** Fait renvoyer un écho à ce téléphone par le relais, puis attend sa réception via FCM. */
    fun sendTestEcho(delaySeconds: Int) {
        if (_test.value == TestState.Sending || _test.value is TestState.Waiting) return
        _test.value = TestState.Sending
        val requestedAt = System.currentTimeMillis()
        viewModelScope.launch {
            val result = container.sender.sendTest(delaySeconds)
            if (result !is EchoSender.Result.Sent) {
                _test.value = TestState.Failed(result.messageRes())
                return@launch
            }
            _test.value = TestState.Waiting(arrivesAt = requestedAt + delaySeconds * 1_000L)
            val arrived = withTimeoutOrNull(delaySeconds * 1_000L + TEST_TIMEOUT_MS) {
                container.lastTestEchoAt.first { it != null && it >= requestedAt }
            }
            _test.value = if (arrived != null) TestState.Received else TestState.Failed(R.string.settings_test_timeout)
        }
    }

    fun answerTest(vibrated: Boolean) {
        _test.value = if (vibrated) TestState.Confirmed else TestState.Idle
    }

    fun unpair(force: Boolean) {
        if (_unpair.value == UnpairState.Working) return
        _unpair.value = UnpairState.Working
        viewModelScope.launch {
            _unpair.value = try {
                if (force) container.pairing.forget() else container.pairing.unpair()
                UnpairState.Idle
            } catch (e: RelayException) {
                UnpairState.Offline
            }
        }
    }

    fun dismissUnpairError() {
        _unpair.value = UnpairState.Idle
    }

    private fun readSystem() = SystemSettings.status(container.context, container.notifications)

    private companion object {
        const val TEST_TIMEOUT_MS = 30_000L
    }
}
