package com.karelisio.echo.echo

import android.util.Log
import androidx.annotation.StringRes
import com.karelisio.echo.R
import com.karelisio.echo.data.history.EchoDirection
import com.karelisio.echo.data.history.HistoryRepository
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.data.pairing.PairingStore
import com.karelisio.echo.data.relay.RelayClient
import com.karelisio.echo.data.relay.RelayException
import com.karelisio.echo.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Envoi d'un écho, commun à l'appli, au widget et au bouton « Répondre » des notifications. */
class EchoSender(
    private val store: PairingStore,
    private val relay: RelayClient,
    private val settings: SettingsStore,
    private val history: HistoryRepository,
) {

    sealed interface Result {
        data class Sent(val at: Long) : Result
        data class Cooldown(val remainingMs: Long) : Result
        data object NotPaired : Result
        data class Failed(val error: Error) : Result
    }

    enum class Error { Network, RateLimited, PartnerUnreachable, SelfUnreachable, PartnerNotReady, PairMissing, NotConfigured, Server }

    suspend fun send(): Result {
        val state = withContext(Dispatchers.IO) { store.ensureLoaded() }
        val credentials = (state as? PairingState.Paired)?.credentials ?: return Result.NotPaired
        val now = System.currentTimeMillis()
        val remaining = settings.tryReserveSend(now, COOLDOWN_MS)
        if (remaining > 0) return Result.Cooldown(remaining)

        var sent = false
        try {
            val current = settings.current()
            relay.ping(credentials, current.emoji, current.pattern.id)
            sent = true
            try {
                history.record(EchoDirection.SENT, current.emoji, current.pattern.id, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // L'écho est parti : un historique incomplet ne doit pas le transformer en échec.
                Log.w(TAG, "Écho envoyé mais non enregistré dans l'historique", e)
            }
            return Result.Sent(now)
        } catch (e: RelayException) {
            // Le relais a refusé pour cause de débit : on garde le délai d'attente.
            if (e.code == "rate_limited") sent = true
            return Result.Failed(e.toSendError())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Réponse illisible, base de données… : un échec franc plutôt qu'un état bloqué.
            Log.w(TAG, "Envoi impossible", e)
            return Result.Failed(Error.Server)
        } finally {
            if (!sent) withContext(NonCancellable) { settings.releaseSend(now) }
        }
    }

    /** Écho renvoyé à ce téléphone par le relais (test de toute la chaîne de réception). */
    suspend fun sendTest(delaySeconds: Int): Result {
        val state = withContext(Dispatchers.IO) { store.ensureLoaded() }
        val credentials = (state as? PairingState.Paired)?.credentials ?: return Result.NotPaired
        val current = settings.current()
        return try {
            val response = relay.pingSelf(credentials, current.emoji, current.pattern.id, delaySeconds)
            Result.Sent(response.sentAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            Result.Failed(e.toSendError())
        } catch (e: Exception) {
            Log.w(TAG, "Test impossible", e)
            Result.Failed(Error.Server)
        }
    }

    companion object {
        const val COOLDOWN_MS = 5_000L
        private const val TAG = "EchoSender"
    }
}

fun RelayException.toSendError(): EchoSender.Error = when {
    kind == RelayException.Kind.NotConfigured -> EchoSender.Error.NotConfigured
    kind == RelayException.Kind.Network -> EchoSender.Error.Network
    code == "rate_limited" -> EchoSender.Error.RateLimited
    code == "partner_unreachable" -> EchoSender.Error.PartnerUnreachable
    code == "self_unreachable" -> EchoSender.Error.SelfUnreachable
    // Juste après l'appairage, une copie KV pas encore propagée peut encore voir la paire « en attente ».
    code == "not_paired" -> EchoSender.Error.PartnerNotReady
    // Un 401 peut venir d'une copie KV pas encore à jour : erreur passagère, pas un lien perdu.
    code == "pair_not_found" -> EchoSender.Error.PairMissing
    else -> EchoSender.Error.Server
}

@StringRes
fun EchoSender.Result.messageRes(): Int = when (this) {
    is EchoSender.Result.Sent -> R.string.send_ok
    is EchoSender.Result.Cooldown -> R.string.send_cooldown
    EchoSender.Result.NotPaired -> R.string.send_not_paired
    is EchoSender.Result.Failed -> error.messageRes()
}

@StringRes
fun EchoSender.Error.messageRes(): Int = when (this) {
    EchoSender.Error.Network -> R.string.error_network
    EchoSender.Error.RateLimited -> R.string.error_rate_limited
    EchoSender.Error.PartnerUnreachable -> R.string.error_partner_unreachable
    EchoSender.Error.SelfUnreachable -> R.string.error_self_unreachable
    EchoSender.Error.PartnerNotReady -> R.string.error_partner_not_ready
    EchoSender.Error.PairMissing -> R.string.error_pair_missing
    EchoSender.Error.NotConfigured -> R.string.error_not_configured
    EchoSender.Error.Server -> R.string.error_server
}
