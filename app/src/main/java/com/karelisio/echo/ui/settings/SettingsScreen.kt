package com.karelisio.echo.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.echo.BuildConfig
import com.karelisio.echo.R
import com.karelisio.echo.appContainer
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.system.SystemSettings
import com.karelisio.echo.ui.components.BigButton
import com.karelisio.echo.ui.components.SectionHeader
import com.karelisio.echo.ui.components.ShapedIcon
import com.karelisio.echo.ui.components.rememberNow
import com.karelisio.echo.ui.update.UpdateCard
import com.karelisio.echo.update.UpdateState
import com.karelisio.echo.update.offer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val GUIDE_ITEM_INDEX = 3

@Composable
fun SettingsScreen(outerPadding: PaddingValues, expandGuide: Boolean, onGuideExpandedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel = viewModel { SettingsViewModel(container) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val system by viewModel.system.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val unpair by viewModel.unpair.collectAsStateWithLifecycle()
    val update by container.updates.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshSystem()
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshSystem()
        if (!granted) SystemSettings.openNotificationSettings(context)
    }

    var editName by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(expandGuide) {
        if (expandGuide) listState.animateScrollToItem(GUIDE_ITEM_INDEX)
    }

    Scaffold(
        modifier = Modifier.padding(outerPadding).nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.settings_section_link))
                LinkGroup(
                    partnerName = settings.partnerName,
                    pairedAt = (pairing as? PairingState.Paired)?.pairedAt,
                    onEditName = { editName = true },
                    onUnpair = { confirmUnpair = true },
                )
            }
            item {
                SectionHeader(stringResource(R.string.settings_section_reception))
                ReceptionGroup(
                    vibrateInDnd = settings.vibrateInDnd,
                    onVibrateInDndChange = viewModel::setVibrateInDnd,
                    dndAccess = system.dndAccess,
                    notificationsAllowed = system.notificationsAllowed,
                    ignoringBatteryOptimizations = system.ignoringBatteryOptimizations,
                    onDndAccess = { SystemSettings.openDndAccess(context) },
                    onNotifications = {
                        if (SystemSettings.needsNotificationPermission) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            SystemSettings.openNotificationSettings(context)
                        }
                    },
                    onBattery = { SystemSettings.requestIgnoreBatteryOptimizations(context) },
                )
            }
            item {
                SectionHeader(stringResource(R.string.settings_section_test))
                TestCard(
                    state = test,
                    onVibrateHere = viewModel::vibrateHere,
                    onSendTest = viewModel::sendTestEcho,
                )
            }
            item {
                SectionHeader(stringResource(R.string.settings_section_guide))
                GuideCard(expanded = expandGuide, onExpandedChange = onGuideExpandedChange)
            }
            item {
                SectionHeader(stringResource(R.string.settings_section_about))
                UpdateCard(Modifier.padding(bottom = 8.dp))
                AboutGroup(
                    relayHost = viewModel.relayHost,
                    update = update.takeIf { container.updates.isEnabled },
                    onCheckUpdates = container.updates::checkNow,
                )
            }
        }
    }

    if (editName) {
        PartnerNameDialog(
            initial = settings.partnerName,
            onDismiss = { editName = false },
            onSave = {
                viewModel.setPartnerName(it)
                editName = false
            },
        )
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_unpair_confirm_title)) },
            text = { Text(stringResource(R.string.settings_unpair_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    viewModel.unpair(force = false)
                }) { Text(stringResource(R.string.settings_unpair), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (unpair == SettingsViewModel.UnpairState.Offline) {
        AlertDialog(
            onDismissRequest = viewModel::dismissUnpairError,
            title = { Text(stringResource(R.string.settings_unpair_offline_title)) },
            text = { Text(stringResource(R.string.settings_unpair_offline_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.unpair(force = true) }) {
                    Text(stringResource(R.string.settings_unpair_force), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissUnpairError) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (test == SettingsViewModel.TestState.Received) {
        AlertDialog(
            onDismissRequest = { viewModel.answerTest(vibrated = true) },
            icon = { Icon(Icons.Rounded.Vibration, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_test_dialog_title)) },
            text = { Text(stringResource(R.string.settings_test_dialog_text)) },
            confirmButton = { TextButton(onClick = { viewModel.answerTest(vibrated = true) }) { Text(stringResource(R.string.settings_test_yes)) } },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.answerTest(vibrated = false)
                    onGuideExpandedChange(true)
                }) { Text(stringResource(R.string.settings_test_no)) }
            },
        )
    }
}

@Composable
private fun LinkGroup(partnerName: String, pairedAt: Long?, onEditName: () -> Unit, onUnpair: () -> Unit) {
    val count = if (pairedAt != null && pairedAt > 0) 3 else 2
    var index = 0
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            onClick = onEditName,
            shapes = ListItemDefaults.segmentedShapes(index++, count),
            leadingContent = { Icon(Icons.Rounded.Person, contentDescription = null) },
            supportingContent = { Text(partnerName.ifBlank { stringResource(R.string.settings_partner_name_empty) }) },
        ) { Text(stringResource(R.string.settings_partner_name)) }
        if (pairedAt != null && pairedAt > 0) {
            val date = Instant.ofEpochMilli(pairedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index++, count),
                leadingContent = { Icon(Icons.Rounded.Link, contentDescription = null) },
            ) { Text(stringResource(R.string.settings_paired_since, date)) }
        }
        SegmentedListItem(
            onClick = onUnpair,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            leadingContent = { Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            supportingContent = { Text(stringResource(R.string.settings_unpair_desc)) },
        ) { Text(stringResource(R.string.settings_unpair), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ReceptionGroup(
    vibrateInDnd: Boolean,
    onVibrateInDndChange: (Boolean) -> Unit,
    dndAccess: Boolean,
    notificationsAllowed: Boolean,
    ignoringBatteryOptimizations: Boolean,
    onDndAccess: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
) {
    val count = 4
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            checked = vibrateInDnd,
            onCheckedChange = onVibrateInDndChange,
            shapes = ListItemDefaults.segmentedShapes(0, count),
            leadingContent = { Icon(Icons.Rounded.Vibration, contentDescription = null) },
            supportingContent = { Text(stringResource(R.string.settings_vibrate_dnd_desc)) },
            trailingContent = { Switch(checked = vibrateInDnd, onCheckedChange = null) },
        ) { Text(stringResource(R.string.settings_vibrate_dnd)) }
        StatusItem(
            index = 1,
            count = count,
            icon = Icons.Rounded.DoNotDisturbOn,
            title = stringResource(R.string.settings_dnd_access),
            ok = dndAccess,
            okText = stringResource(R.string.settings_dnd_access_on),
            koText = stringResource(R.string.settings_dnd_access_off),
            onClick = onDndAccess,
        )
        StatusItem(
            index = 2,
            count = count,
            icon = Icons.Rounded.Notifications,
            title = stringResource(R.string.settings_notifications),
            ok = notificationsAllowed,
            okText = stringResource(R.string.settings_notifications_on),
            koText = stringResource(R.string.settings_notifications_off),
            onClick = onNotifications,
        )
        StatusItem(
            index = 3,
            count = count,
            icon = Icons.Rounded.BatteryChargingFull,
            title = stringResource(R.string.settings_battery),
            ok = ignoringBatteryOptimizations,
            okText = stringResource(R.string.settings_battery_on),
            koText = stringResource(R.string.settings_battery_off),
            onClick = onBattery,
        )
    }
}

@Composable
private fun StatusItem(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    ok: Boolean,
    okText: String,
    koText: String,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = {
            Text(if (ok) okText else koText, color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        },
        trailingContent = {
            Icon(
                imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
    ) { Text(title) }
}

@Composable
private fun TestCard(state: SettingsViewModel.TestState, onVibrateHere: () -> Unit, onSendTest: (Int) -> Unit) {
    var delay by rememberSaveable { mutableIntStateOf(10) }
    val busy = state == SettingsViewModel.TestState.Sending || state is SettingsViewModel.TestState.Waiting
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapedIcon(
                    icon = Icons.Rounded.Science,
                    shape = MaterialShapes.Pentagon,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_test_remote), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        stringResource(R.string.settings_test_remote_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                ToggleButton(
                    checked = delay == 0,
                    onCheckedChange = { delay = 0 },
                    enabled = !busy,
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_test_now)) }
                ToggleButton(
                    checked = delay == 10,
                    onCheckedChange = { delay = 10 },
                    enabled = !busy,
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_test_delayed)) }
            }
            BigButton(
                text = stringResource(R.string.settings_test_send),
                onClick = { onSendTest(delay) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            TestStatus(state)
            FilledTonalButton(onClick = onVibrateHere, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Vibration, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_test_local))
            }
        }
    }
}

@Composable
private fun TestStatus(state: SettingsViewModel.TestState) {
    val now by rememberNow(1_000L)
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec())) },
        label = "testStatus",
    ) { current ->
        when (current) {
            SettingsViewModel.TestState.Sending -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            is SettingsViewModel.TestState.Waiting -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                val seconds = ((current.arrivesAt - now) / 1000).coerceAtLeast(0)
                Text(
                    if (seconds > 0) stringResource(R.string.settings_test_waiting_delayed, seconds.toInt())
                    else stringResource(R.string.settings_test_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SettingsViewModel.TestState.Confirmed -> Text(
                stringResource(R.string.settings_test_success),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is SettingsViewModel.TestState.Failed -> Text(
                stringResource(current.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            SettingsViewModel.TestState.Idle, SettingsViewModel.TestState.Received -> Spacer(Modifier)
        }
    }
}

@Composable
private fun GuideCard(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val motion = MaterialTheme.motionScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, motion.defaultSpatialSpec(), label = "chevron")
    val recommended = SystemSettings.isHonorOrHuawei
    Card(
        onClick = { onExpandedChange(!expanded) },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (recommended) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapedIcon(
                    icon = Icons.Rounded.Tune,
                    shape = MaterialShapes.SoftBurst,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        stringResource(if (recommended) R.string.guide_subtitle_recommended else R.string.guide_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyMedium)
                    GuideStep(R.string.guide_step_launch_title, R.string.guide_step_launch_text) { SystemSettings.openLaunchManager(context) }
                    GuideStep(R.string.guide_step_dnd_title, R.string.guide_step_dnd_text) { SystemSettings.openDndSettings(context) }
                    GuideStep(R.string.guide_step_channel_title, R.string.guide_step_channel_text) { SystemSettings.openEchoChannelSettings(context) }
                    GuideStep(R.string.guide_step_lock_title, R.string.guide_step_lock_text, onOpen = null)
                    Text(
                        stringResource(R.string.guide_retest),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideStep(title: Int, text: Int, onOpen: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmallEmphasized)
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
        if (onOpen != null) {
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(R.string.action_open))
            }
        }
    }
}

/** [update] : `null` si les mises à jour in-app sont désactivées (build sans URL). */
@Composable
private fun AboutGroup(relayHost: String, update: UpdateState?, onCheckUpdates: () -> Unit) {
    val count = if (update != null) 3 else 2
    var index = 0
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index++, count),
            leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
            supportingContent = { Text(BuildConfig.VERSION_NAME) },
        ) { Text(stringResource(R.string.settings_version)) }
        if (update != null) {
            val offer = update.offer
            SegmentedListItem(
                onClick = onCheckUpdates,
                shapes = ListItemDefaults.segmentedShapes(index++, count),
                leadingContent = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                supportingContent = {
                    Text(
                        when {
                            offer != null -> stringResource(R.string.settings_updates_available, offer.versionName)
                            update == UpdateState.Checking -> stringResource(R.string.settings_updates_checking)
                            update == UpdateState.UpToDate -> stringResource(R.string.settings_updates_up_to_date)
                            update == UpdateState.CheckFailed -> stringResource(R.string.settings_updates_failed)
                            else -> stringResource(R.string.settings_updates_idle)
                        },
                    )
                },
                trailingContent = {
                    if (update == UpdateState.Checking) {
                        LoadingIndicator(Modifier.size(32.dp))
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                },
            ) { Text(stringResource(R.string.settings_updates)) }
        }
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index, count),
            leadingContent = { Icon(Icons.Rounded.Cloud, contentDescription = null) },
            supportingContent = { Text(relayHost) },
        ) { Text(stringResource(R.string.settings_relay)) }
    }
}

@Composable
private fun PartnerNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Person, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_partner_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_partner_name_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 30) value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
