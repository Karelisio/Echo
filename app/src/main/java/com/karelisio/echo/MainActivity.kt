package com.karelisio.echo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.karelisio.echo.ui.EchoRoot
import com.karelisio.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Écran demandé par une notification (ex. le guide « mon téléphone ne vibre pas »). */
    private val destination = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) destination.value = intent?.getStringExtra(EXTRA_DESTINATION)
        val container = appContainer
        setContent {
            EchoTheme {
                val requested by destination.collectAsStateWithLifecycle()
                EchoRoot(
                    container = container,
                    destination = requested,
                    onDestinationHandled = { destination.value = null },
                )
            }
        }
        // Mise à jour : la fenêtre de confirmation d'Android s'ouvre quand l'appli est visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.updates.confirmation.filterNotNull().collect { confirmation ->
                    container.updates.onConfirmationShown()
                    try {
                        startActivity(confirmation)
                    } catch (_: ActivityNotFoundException) {
                        container.updates.onInstallStatus(PackageInstaller.STATUS_FAILURE, null)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destination.value = intent.getStringExtra(EXTRA_DESTINATION)
    }

    override fun onResume() {
        super.onResume()
        // Prend en compte un accès « Ne pas déranger » accordé pendant que l'appli était en arrière-plan.
        appContainer.notifications.ensureChannels()
        appContainer.updates.checkIfStale()
    }

    companion object {
        const val EXTRA_DESTINATION = "com.karelisio.echo.DESTINATION"
        const val DESTINATION_GUIDE = "guide"
    }
}
