package com.karelisio.echo.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.karelisio.echo.vibration.VibrationPattern
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class EchoSettings(
    val emoji: String = DEFAULT_EMOJI,
    val pattern: VibrationPattern = VibrationPattern.Default,
    /** Vibrer même en mode silencieux / Ne pas déranger (usage « alarme »). */
    val vibrateInDnd: Boolean = true,
    /** Surnom local de l'autre personne, affiché dans les notifications. */
    val partnerName: String = "",
    val onboardingDone: Boolean = false,
    /** Dernier jeton FCM enregistré auprès du relais, et date de cet enregistrement. */
    val registeredToken: String? = null,
    val tokenSyncedAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_EMOJI = "💜"
    }
}

class SettingsStore(private val store: DataStore<Preferences>) {

    val settings: Flow<EchoSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun current(): EchoSettings = settings.first()

    suspend fun setEmoji(emoji: String) = store.edit { it[EMOJI] = emoji }

    suspend fun setPattern(pattern: VibrationPattern) = store.edit { it[PATTERN] = pattern.id }

    suspend fun setVibrateInDnd(enabled: Boolean) = store.edit { it[VIBRATE_IN_DND] = enabled }

    suspend fun setPartnerName(name: String) = store.edit { it[PARTNER_NAME] = name.trim().take(MAX_NAME_LENGTH) }

    suspend fun setOnboardingDone() = store.edit { it[ONBOARDING_DONE] = true }

    suspend fun setRegisteredToken(token: String?) = store.edit {
        if (token == null) {
            it.remove(REGISTERED_TOKEN)
            it.remove(TOKEN_SYNCED_AT)
        } else {
            it[REGISTERED_TOKEN] = token
            it[TOKEN_SYNCED_AT] = System.currentTimeMillis()
        }
    }

    /**
     * Réserve atomiquement un créneau d'envoi (anti-spam).
     * @return 0 si l'envoi est autorisé, sinon le temps restant avant le prochain envoi, en ms.
     */
    suspend fun tryReserveSend(now: Long, cooldownMs: Long): Long {
        var remaining = 0L
        store.edit { prefs ->
            val elapsed = now - (prefs[LAST_SENT_AT] ?: 0L)
            // elapsed < 0 : l'horloge a reculé, on ne bloque pas l'utilisateur.
            if (elapsed in 0 until cooldownMs) {
                remaining = cooldownMs - elapsed
            } else {
                prefs[LAST_SENT_AT] = now
            }
        }
        return remaining
    }

    /** Libère un créneau réservé par [tryReserveSend] (envoi échoué : on peut réessayer tout de suite). */
    suspend fun releaseSend(reservedAt: Long) = store.edit { prefs ->
        if (prefs[LAST_SENT_AT] == reservedAt) prefs.remove(LAST_SENT_AT)
    }

    val lastSentAt: Flow<Long> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[LAST_SENT_AT] ?: 0L }

    private fun Preferences.toSettings() = EchoSettings(
        emoji = this[EMOJI] ?: EchoSettings.DEFAULT_EMOJI,
        pattern = VibrationPattern.fromId(this[PATTERN]),
        vibrateInDnd = this[VIBRATE_IN_DND] ?: true,
        partnerName = this[PARTNER_NAME].orEmpty(),
        onboardingDone = this[ONBOARDING_DONE] ?: false,
        registeredToken = this[REGISTERED_TOKEN],
        tokenSyncedAt = this[TOKEN_SYNCED_AT] ?: 0L,
    )

    companion object {
        fun create(context: Context) = SettingsStore(context.dataStore)

        private const val MAX_NAME_LENGTH = 30
        private val EMOJI = stringPreferencesKey("emoji")
        private val PATTERN = stringPreferencesKey("pattern")
        private val VIBRATE_IN_DND = booleanPreferencesKey("vibrate_in_dnd")
        private val PARTNER_NAME = stringPreferencesKey("partner_name")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val REGISTERED_TOKEN = stringPreferencesKey("registered_token")
        private val TOKEN_SYNCED_AT = longPreferencesKey("token_synced_at")
        private val LAST_SENT_AT = longPreferencesKey("last_sent_at")
    }
}
