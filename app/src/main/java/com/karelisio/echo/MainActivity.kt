package com.karelisio.echo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.echo.ui.EchoRoot
import com.karelisio.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.MutableStateFlow

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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destination.value = intent.getStringExtra(EXTRA_DESTINATION)
    }

    override fun onResume() {
        super.onResume()
        // Prend en compte un accès « Ne pas déranger » accordé pendant que l'appli était en arrière-plan.
        appContainer.notifications.ensureChannels()
    }

    companion object {
        const val EXTRA_DESTINATION = "com.karelisio.echo.DESTINATION"
        const val DESTINATION_GUIDE = "guide"
    }
}
