package com.karelisio.echo.ui.onboarding

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.karelisio.echo.R
import com.karelisio.echo.appContainer
import com.karelisio.echo.system.SystemSettings
import com.karelisio.echo.system.SystemStatus
import com.karelisio.echo.ui.components.BigButton
import com.karelisio.echo.ui.components.BigButtonStyle
import com.karelisio.echo.ui.components.ShapedBadge

private enum class Step { Welcome, Notifications, DoNotDisturb, Battery }

/** Premier lancement : présentation, puis notifications, accès Ne pas déranger et batterie. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val steps = remember {
        buildList {
            add(Step.Welcome)
            if (SystemSettings.needsNotificationPermission) add(Step.Notifications)
            add(Step.DoNotDisturb)
            add(Step.Battery)
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var system by remember { mutableStateOf(SystemSettings.status(context, container.notifications)) }
    fun refresh() {
        container.notifications.ensureChannels()
        system = SystemSettings.status(context, container.notifications)
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    fun next() {
        forward = true
        if (index < steps.lastIndex) index++ else onFinished()
    }
    BackHandler(enabled = index > 0) {
        forward = false
        index--
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        if (granted) next()
    }

    val motion = MaterialTheme.motionScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        StepDots(current = index, count = steps.size, modifier = Modifier.align(Alignment.CenterHorizontally))
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val direction = if (forward) 1 else -1
                (slideInHorizontally(motion.defaultSpatialSpec()) { it / 3 * direction } + fadeIn(motion.defaultEffectsSpec()))
                    .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { -it / 3 * direction } + fadeOut(motion.fastEffectsSpec()))
            },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { position ->
            val step = steps[position]
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (step) {
                    Step.Welcome -> StepPage(
                        illustration = { WelcomeIllustration() },
                        title = stringResource(R.string.app_name),
                        text = stringResource(R.string.onboarding_welcome_text),
                    )
                    Step.Notifications -> StepPage(
                        illustration = { IconIllustration(Icons.Rounded.NotificationsActive, MaterialShapes.Clover8Leaf) },
                        title = stringResource(R.string.onboarding_notifications_title),
                        text = stringResource(R.string.onboarding_notifications_text),
                        granted = system.notificationsAllowed,
                    )
                    Step.DoNotDisturb -> StepPage(
                        illustration = { IconIllustration(Icons.Rounded.DoNotDisturbOn, MaterialShapes.SoftBurst) },
                        title = stringResource(R.string.onboarding_dnd_title),
                        text = stringResource(R.string.onboarding_dnd_text),
                        hint = stringResource(R.string.onboarding_dnd_hint).takeUnless { system.dndAccess },
                        granted = system.dndAccess,
                    )
                    Step.Battery -> StepPage(
                        illustration = { IconIllustration(Icons.Rounded.BatteryChargingFull, MaterialShapes.Sunny) },
                        title = stringResource(R.string.onboarding_battery_title),
                        text = stringResource(R.string.onboarding_battery_text),
                        granted = system.ignoringBatteryOptimizations,
                    )
                }
            }
        }
        StepActions(
            step = steps[index],
            isLast = index == steps.lastIndex,
            system = system,
            onNext = ::next,
            onRequestNotifications = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onRequestDnd = { SystemSettings.openDndAccess(context) },
            onRequestBattery = { SystemSettings.requestIgnoreBatteryOptimizations(context) },
            onOpenLaunchManager = { SystemSettings.openLaunchManager(context) },
        )
    }
}

@Composable
private fun StepActions(
    step: Step,
    isLast: Boolean,
    system: SystemStatus,
    onNext: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestDnd: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenLaunchManager: () -> Unit,
) {
    val nextLabel = stringResource(if (isLast) R.string.onboarding_finish else R.string.onboarding_next)
    val (granted, request, requestLabel) = when (step) {
        Step.Welcome -> Triple(true, onNext, "")
        Step.Notifications -> Triple(system.notificationsAllowed, onRequestNotifications, stringResource(R.string.onboarding_notifications_action))
        Step.DoNotDisturb -> Triple(system.dndAccess, onRequestDnd, stringResource(R.string.onboarding_dnd_action))
        Step.Battery -> Triple(system.ignoringBatteryOptimizations, onRequestBattery, stringResource(R.string.onboarding_battery_action))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            step == Step.Welcome -> BigButton(stringResource(R.string.onboarding_start), onNext, Modifier.fillMaxWidth())
            granted -> BigButton(nextLabel, onNext, Modifier.fillMaxWidth())
            else -> {
                BigButton(requestLabel, request, Modifier.fillMaxWidth())
                if (step == Step.Battery && SystemSettings.isHonorOrHuawei) {
                    BigButton(
                        text = stringResource(R.string.onboarding_launch_action),
                        onClick = onOpenLaunchManager,
                        modifier = Modifier.fillMaxWidth(),
                        style = BigButtonStyle.Tonal,
                        icon = Icons.Rounded.RocketLaunch,
                    )
                }
                TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_later))
                }
            }
        }
    }
}

@Composable
private fun StepPage(
    illustration: @Composable () -> Unit,
    title: String,
    text: String,
    hint: String? = null,
    granted: Boolean = false,
) {
    illustration()
    Spacer(Modifier.height(32.dp))
    Text(title, style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (hint != null) {
        Spacer(Modifier.height(12.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    }
    AnimatedVisibility(visible = granted) {
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_granted), style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WelcomeIllustration() {
    ShapedBadge(MaterialShapes.Cookie12Sided, MaterialTheme.colorScheme.primaryContainer, size = 200.dp) {
        Icon(
            painter = painterResource(R.drawable.ic_stat_echo),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(110.dp),
        )
    }
}

@Composable
private fun IconIllustration(icon: ImageVector, shape: RoundedPolygon) {
    ShapedBadge(shape, MaterialTheme.colorScheme.secondaryContainer, size = 180.dp) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(80.dp))
    }
}

@Composable
private fun StepDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = index == current
            Box(
                Modifier
                    .size(width = if (active) 24.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
