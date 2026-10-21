package com.karelisio.echo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.karelisio.echo.data.history.EchoDatabase
import com.karelisio.echo.data.history.HistoryRepository
import com.karelisio.echo.data.pairing.PairingRepository
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.data.pairing.PairingStore
import com.karelisio.echo.data.relay.RelayClient
import com.karelisio.echo.data.settings.SettingsStore
import com.karelisio.echo.echo.EchoReceiver
import com.karelisio.echo.echo.EchoSender
import com.karelisio.echo.fcm.TokenSyncWorker
import com.karelisio.echo.notification.EchoNotifications
import com.karelisio.echo.vibration.EchoVibrator
import com.karelisio.echo.widget.WidgetController
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Graphe d'objets de l'appli, sans framework d'injection. Les paramètres optionnels permettent
 * aux tests de remplacer le stockage (pas de Keystore sous Robolectric).
 */
class AppContainer(
    context: Context,
    pairingPreferences: (() -> SharedPreferences)? = null,
    settingsDataStore: DataStore<Preferences>? = null,
    database: (() -> EchoDatabase)? = null,
    relayUrl: String = BuildConfig.RELAY_URL,
) {

    val context: Context = context.applicationContext

    /** Portée qui survit aux écrans : un envoi lancé depuis l'appli n'est pas annulé si on la quitte. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.e("Echo", "Erreur dans une tâche de fond", error)
        },
    )

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val settings by lazy { settingsDataStore?.let(::SettingsStore) ?: SettingsStore.create(this.context) }
    val pairingStore by lazy { pairingPreferences?.let(::PairingStore) ?: PairingStore.create(this.context) }
    val history by lazy { HistoryRepository((database?.invoke() ?: EchoDatabase.build(this.context)).echoDao()) }

    val relay by lazy {
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        RelayClient(relayUrl, http, json)
    }

    val pairing by lazy { PairingRepository(pairingStore, relay, settings) }
    val vibrator by lazy { EchoVibrator(this.context) }
    val notifications by lazy { EchoNotifications(this.context) }
    val widget by lazy { WidgetController(this.context, settings, pairingStore, history) }
    val sender by lazy { EchoSender(pairingStore, relay, settings, history) }

    /** Horodatage du dernier écho de test reçu (pour demander « ça a vibré ? » dans les réglages). */
    val lastTestEchoAt = MutableStateFlow<Long?>(null)

    val receiver by lazy {
        EchoReceiver(pairingStore, settings, history, vibrator, notifications, widget, lastTestEchoAt)
    }

    suspend fun onAppStart() {
        pairingStore.ensureLoaded()
        TokenSyncWorker.enqueue(context)
        widget.refresh()
    }

    /**
     * La session Glance s'arrête ~45 s après le dernier rendu : sans cela, un appairage, un
     * désappairage ou un nouvel emoji ne s'afficheraient sur le widget qu'au prochain écho reçu.
     */
    fun observeWidgetInputs() {
        appScope.launch {
            combine(pairingStore.state, settings.settings) { pairing, settings ->
                (pairing is PairingState.Paired) to settings.emoji
            }
                .distinctUntilChanged()
                .drop(1)
                .collect { widget.refresh() }
        }
    }
}
