package com.karelisio.echo.data.pairing

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PairCredentials(val pairId: String, val secret: String, val role: String) {
    override fun toString() = "PairCredentials(pairId=$pairId, role=$role)" // jamais le secret dans les logs
}

sealed interface PairingState {
    data object Unpaired : PairingState

    /** Téléphone A : code affiché, en attente du second téléphone. */
    data class Pending(val credentials: PairCredentials, val code: String, val expiresAt: Long) : PairingState

    data class Paired(val credentials: PairCredentials, val pairedAt: Long) : PairingState
}

val PairingState.credentials: PairCredentials?
    get() = when (this) {
        is PairingState.Pending -> credentials
        is PairingState.Paired -> credentials
        PairingState.Unpaired -> null
    }

/**
 * Identifiants d'appairage (pairId + secret) dans des EncryptedSharedPreferences,
 * chiffrées par une clé du Keystore Android.
 */
class PairingStore(openPreferences: () -> SharedPreferences) {

    private val prefs: SharedPreferences by lazy(openPreferences)

    private val _state = MutableStateFlow<PairingState?>(null)

    /** `null` tant que les préférences chiffrées n'ont pas été lues (lecture lente, hors thread principal). */
    val state: StateFlow<PairingState?> = _state.asStateFlow()

    @Synchronized
    fun ensureLoaded(): PairingState = _state.value ?: read().also { _state.value = it }

    fun current(): PairingState = ensureLoaded()

    @Synchronized
    fun savePending(credentials: PairCredentials, code: String, expiresAt: Long): PairingState.Pending {
        val state = PairingState.Pending(credentials, code, expiresAt)
        write(credentials, STATUS_PENDING) {
            putString(KEY_CODE, code)
            putLong(KEY_EXPIRES_AT, expiresAt)
        }
        _state.value = state
        return state
    }

    @Synchronized
    fun savePaired(credentials: PairCredentials, pairedAt: Long) {
        write(credentials, STATUS_PAIRED) { putLong(KEY_PAIRED_AT, pairedAt) }
        _state.value = PairingState.Paired(credentials, pairedAt)
    }

    /** Passe la paire en attente à l'état « reliée » si c'est bien celle-ci. */
    @Synchronized
    fun markPaired(pairId: String, pairedAt: Long): Boolean {
        val pending = ensureLoaded() as? PairingState.Pending ?: return false
        if (pending.credentials.pairId != pairId) return false
        savePaired(pending.credentials, pairedAt)
        return true
    }

    /** Efface l'appairage ; si [onlyPairId] est fourni, seulement s'il correspond à la paire courante. */
    @Synchronized
    fun clear(onlyPairId: String? = null): Boolean {
        val current = ensureLoaded().credentials ?: return false
        if (onlyPairId != null && current.pairId != onlyPairId) return false
        prefs.edit(commit = true) { clear() }
        _state.value = PairingState.Unpaired
        return true
    }

    private inline fun write(
        credentials: PairCredentials,
        status: String,
        crossinline extra: SharedPreferences.Editor.() -> Unit,
    ) {
        prefs.edit(commit = true) {
            clear()
            putString(KEY_STATUS, status)
            putString(KEY_PAIR_ID, credentials.pairId)
            putString(KEY_SECRET, credentials.secret)
            putString(KEY_ROLE, credentials.role)
            extra()
        }
    }

    private fun read(): PairingState {
        val pairId = prefs.getString(KEY_PAIR_ID, null)
        val secret = prefs.getString(KEY_SECRET, null)
        val role = prefs.getString(KEY_ROLE, null)
        if (pairId == null || secret == null || role == null) return PairingState.Unpaired
        val credentials = PairCredentials(pairId, secret, role)
        return when (prefs.getString(KEY_STATUS, null)) {
            STATUS_PAIRED -> PairingState.Paired(credentials, prefs.getLong(KEY_PAIRED_AT, 0L))
            STATUS_PENDING -> PairingState.Pending(
                credentials,
                prefs.getString(KEY_CODE, null).orEmpty(),
                prefs.getLong(KEY_EXPIRES_AT, 0L),
            )
            else -> PairingState.Unpaired
        }
    }

    companion object {
        fun create(context: Context) = PairingStore { openEncryptedPrefs(context) }

        private const val TAG = "PairingStore"
        private const val FILE_NAME = "echo_pairing"
        private const val KEY_STATUS = "status"
        private const val KEY_PAIR_ID = "pair_id"
        private const val KEY_SECRET = "secret"
        private const val KEY_ROLE = "role"
        private const val KEY_CODE = "code"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_PAIRED_AT = "paired_at"
        private const val STATUS_PENDING = "pending"
        private const val STATUS_PAIRED = "paired"

        // EncryptedSharedPreferences est dépréciée par androidx mais reste fonctionnelle et
        // correspond exactement au besoin (stockage chiffré clé/valeur adossé au Keystore).
        @Suppress("DEPRECATION")
        private fun openEncryptedPrefs(context: Context): SharedPreferences {
            fun open(): SharedPreferences = EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return try {
                open()
            } catch (e: Exception) {
                // Clé du Keystore perdue ou fichier illisible (restauration, réinitialisation…) :
                // on repart d'un appairage vierge plutôt que de planter.
                Log.w(TAG, "Préférences chiffrées illisibles, réinitialisation", e)
                context.deleteSharedPreferences(FILE_NAME)
                runCatching {
                    KeyStore.getInstance("AndroidKeyStore").apply {
                        load(null)
                        deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    }
                }
                open()
            }
        }
    }
}
