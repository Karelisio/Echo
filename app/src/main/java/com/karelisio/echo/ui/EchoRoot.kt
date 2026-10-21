package com.karelisio.echo.ui

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.echo.AppContainer
import com.karelisio.echo.MainActivity
import com.karelisio.echo.R
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.ui.history.HistoryScreen
import com.karelisio.echo.ui.home.HomeScreen
import com.karelisio.echo.ui.onboarding.OnboardingScreen
import com.karelisio.echo.ui.pairing.PairingScreen
import com.karelisio.echo.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RootScreen { Loading, Onboarding, Pairing, Main }

@Composable
fun EchoRoot(container: AppContainer, destination: String?, onDestinationHandled: () -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val pairing by container.pairingStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { container.pairingStore.ensureLoaded() } }

    val screen = when {
        settings == null || pairing == null -> RootScreen.Loading
        settings?.onboardingDone != true -> RootScreen.Onboarding
        pairing !is PairingState.Paired -> RootScreen.Pairing
        else -> RootScreen.Main
    }
    val motion = MaterialTheme.motionScheme

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
            },
            label = "root",
        ) { target ->
            when (target) {
                RootScreen.Loading -> Box(Modifier.fillMaxSize())
                RootScreen.Onboarding -> OnboardingScreen(
                    onFinished = { scope.launch { container.settings.setOnboardingDone() } },
                )
                RootScreen.Pairing -> PairingScreen()
                RootScreen.Main -> MainScreen(destination, onDestinationHandled)
            }
        }
    }
}

private enum class Tab(@param:StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    Echo(R.string.tab_echo, Icons.Outlined.Vibration, Icons.Rounded.Vibration),
    History(R.string.tab_history, Icons.Outlined.History, Icons.Rounded.History),
    Settings(R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@Composable
private fun MainScreen(destination: String?, onDestinationHandled: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Echo) }
    var expandGuide by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(destination) {
        if (destination == MainActivity.DESTINATION_GUIDE) {
            tab = Tab.Settings
            expandGuide = true
            onDestinationHandled()
        }
    }
    BackHandler(enabled = tab != Tab.Echo) { tab = Tab.Echo }
    val motion = MaterialTheme.motionScheme

    Scaffold(
        bottomBar = {
            ShortNavigationBar {
                Tab.entries.forEach { item ->
                    ShortNavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(if (tab == item) item.selectedIcon else item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec())) },
            label = "tabs",
        ) { current ->
            when (current) {
                Tab.Echo -> HomeScreen(bottom)
                Tab.History -> HistoryScreen(bottom)
                Tab.Settings -> SettingsScreen(bottom, expandGuide = expandGuide, onGuideExpandedChange = { expandGuide = it })
            }
        }
    }
}
