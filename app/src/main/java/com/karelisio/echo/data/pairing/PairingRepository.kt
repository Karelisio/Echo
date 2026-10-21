package com.karelisio.echo.data.pairing

import com.google.firebase.messaging.FirebaseMessaging
import com.karelisio.echo.data.relay.RelayClient
import com.karelisio.echo.data.relay.RelayException
import com.karelisio.echo.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Appairage sans compte : un code à 6 caractères créé sur un téléphone, saisi sur l'autre. */
class PairingRepository(
    private val store: PairingStore,
    private val relay: RelayClient,
    private val settings: SettingsStore,
) {

    val state: StateFlow<PairingState?> = store.state

    /** Téléphone A : demande un code au relais. Les identifiants sont reçus immédiatement. */
    suspend fun createCode(): PairingState.Pending {
        val token = currentFcmToken()
        val response = relay.createPair(token)
        settings.setRegisteredToken(token)
        return io {
            store.savePending(PairCredentials(response.pairId, response.secret, response.role), response.code, response.expiresAt)
        }
    }

    /** Téléphone B : rejoint la paire avec le code saisi. */
    suspend fun join(code: String) {
        val token = currentFcmToken()
        val response = relay.joinPair(code, token)
        settings.setRegisteredToken(token)
        io { store.savePaired(PairCredentials(response.pairId, response.secret, response.role), System.currentTimeMillis()) }
    }

    /** Téléphone A : vérifie si le second téléphone a rejoint (en plus du message FCM « paired »). */
    suspend fun refreshPending(): Boolean {
        val pending = io { store.ensureLoaded() } as? PairingState.Pending ?: return false
        val status = try {
            relay.status(pending.credentials)
        } catch (e: RelayException) {
            // Code expiré : la paire en attente a disparu du relais.
            if (e.isPairMissing) io { store.clear(pending.credentials.pairId) }
            return false
        }
        if (!status.paired) return false
        return io { store.markPaired(pending.credentials.pairId, status.pairedAt ?: System.currentTimeMillis()) }
    }

    /**
     * Vérifie que la paire existe toujours côté relais (désappairage manqué pendant une absence de
     * réseau…). `null` si on ne peut pas conclure. Un `paired: false` juste après l'appairage peut
     * venir d'une copie KV pas encore propagée : seule une paire introuvable compte comme absente.
     */
    suspend fun isKnownByRelay(): Boolean? {
        val credentials = (io { store.ensureLoaded() } as? PairingState.Paired)?.credentials ?: return null
        return try {
            relay.status(credentials)
            true
        } catch (e: RelayException) {
            if (e.isPairMissing) false else null
        }
    }

    suspend fun cancelPending() {
        val pending = io { store.ensureLoaded() } as? PairingState.Pending ?: return
        runCatching { relay.leave(pending.credentials) }
        io { store.clear(pending.credentials.pairId) }
    }

    /**
     * Désappairage : prévient le relais (qui prévient l'autre téléphone), puis oublie les identifiants.
     * Lève une [RelayException] réseau si le relais est injoignable, pour laisser le choix à l'utilisateur.
     */
    suspend fun unpair() {
        val credentials = io { store.ensureLoaded() }.credentials ?: return
        try {
            relay.leave(credentials)
        } catch (e: RelayException) {
            if (!e.isPairMissing && e.code != "unauthorized") throw e
        }
        forget()
    }

    suspend fun forget() {
        io { store.clear() }
        settings.setRegisteredToken(null)
    }

    /** Message FCM « paired » reçu par le téléphone A. */
    suspend fun onRemotePaired(pairId: String?) {
        if (pairId != null) io { store.markPaired(pairId, System.currentTimeMillis()) }
    }

    /** Message FCM « unpaired » : l'autre téléphone a rompu le lien. */
    suspend fun onRemoteUnpaired(pairId: String?): Boolean {
        if (pairId == null) return false
        val cleared = io { store.clear(pairId) }
        if (cleared) settings.setRegisteredToken(null)
        return cleared
    }

    /**
     * Envoie au relais le jeton FCM courant s'il a changé, et au moins une fois par jour : le relais
     * n'écrit dans KV que si son jeton diffère, ce qui répare sans frais une donnée périmée.
     */
    suspend fun syncToken(knownToken: String? = null) {
        val credentials = io { store.ensureLoaded() }.credentials ?: return
        val token = knownToken ?: currentFcmToken()
        val current = settings.current()
        val recent = System.currentTimeMillis() - current.tokenSyncedAt in 0 until TOKEN_RESYNC_MS
        if (token == current.registeredToken && recent) return
        relay.updateToken(credentials, token)
        settings.setRegisteredToken(token)
    }

    // firebase-messaging 26 déprécie getToken() au profit de register()/onRegistered(), mais cette
    // nouvelle API est encore optionnelle (meta-data « firebase_messaging_installation_id_enabled »)
    // et ne renvoie pas le jeton de façon synchrone : l'API historique reste celle activée par défaut.
    @Suppress("DEPRECATION")
    private suspend fun currentFcmToken(): String = FirebaseMessaging.getInstance().token.await()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val TOKEN_RESYNC_MS = 24 * 3_600_000L
    }
}
