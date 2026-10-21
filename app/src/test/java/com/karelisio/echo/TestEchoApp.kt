package com.karelisio.echo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.karelisio.echo.data.history.EchoDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application de test : stockage non chiffré (pas de Keystore sous Robolectric), base en mémoire. */
class TestEchoApp : EchoApp() {

    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        super.onCreate()
    }

    override fun createContainer() = AppContainer(
        context = this,
        pairingPreferences = { getSharedPreferences("pairing-test", MODE_PRIVATE) },
        settingsDataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(filesDir, "settings-${System.nanoTime()}.preferences_pb")
        },
        database = { Room.inMemoryDatabaseBuilder(this, EchoDatabase::class.java).build() },
        // Jamais de vrai réseau dans les tests, quelles que soient les URL configurées pour le build.
        relayUrl = "",
        updateUrl = "",
    )

    override val runsStartupWork = false
}
