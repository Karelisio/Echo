package com.karelisio.echo.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.echo.R
import com.karelisio.echo.appContainer
import com.karelisio.echo.data.history.EchoEvent
import com.karelisio.echo.echo.EchoSender
import com.karelisio.echo.ui.components.EchoSendButton
import com.karelisio.echo.ui.components.PatternGlyph
import com.karelisio.echo.ui.components.SectionHeader
import com.karelisio.echo.ui.components.ShapedEmoji
import com.karelisio.echo.ui.components.rememberNow
import com.karelisio.echo.util.RelativeTime
import com.karelisio.echo.vibration.VibrationPattern

private val PRESET_EMOJIS = listOf("💜", "❤️", "🧡", "💛", "💚", "💙", "🫶", "😘", "🥰", "🤗", "🌙", "✨")

@Composable
fun HomeScreen(outerPadding: PaddingValues) {
    val container = LocalContext.current.appContainer
    val viewModel = viewModel { HomeViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val now by rememberNow(15_000L)
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showEmojiDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sendState) {
        when (val current = sendState) {
            HomeViewModel.SendState.Sent -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            is HomeViewModel.SendState.Message -> if (current.isError) haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }

    // Anneau de délai entre deux envois (5 s), animé à partir de l'heure du dernier envoi.
    val cooldown = remember { Animatable(0f) }
    LaunchedEffect(state.lastSentAt) {
        val remaining = state.lastSentAt + EchoSender.COOLDOWN_MS - System.currentTimeMillis()
        if (remaining <= 0) {
            cooldown.snapTo(0f)
        } else {
            cooldown.snapTo(remaining.toFloat() / EchoSender.COOLDOWN_MS)
            cooldown.animateTo(0f, tween(remaining.toInt(), easing = LinearEasing))
        }
    }

    val partner = state.settings.partnerName
    Scaffold(
        modifier = Modifier.padding(outerPadding).nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                subtitle = {
                    Text(
                        if (partner.isBlank()) stringResource(R.string.home_subtitle)
                        else stringResource(R.string.home_subtitle_named, partner),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = 24.dp,
            ),
        ) {
            if (state.pairMissing) {
                item { PairMissingCard(onRelink = viewModel::forgetPairing) }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EchoSendButton(
                        emoji = state.settings.emoji,
                        sending = sendState == HomeViewModel.SendState.Sending,
                        celebrate = sendState == HomeViewModel.SendState.Sent,
                        cooldownProgress = { cooldown.value },
                        showCooldown = cooldown.value > 0f,
                        contentDescription = stringResource(R.string.home_send_cd, state.settings.emoji),
                        onClick = viewModel::send,
                    )
                    SendStatus(sendState)
                }
            }
            item { LastEchoCard(state.lastReceived, state.lastSent, now) }
            item {
                SectionHeader(
                    title = stringResource(R.string.home_your_echo),
                    subtitle = stringResource(R.string.home_your_echo_desc),
                )
                EmojiPicker(
                    selected = state.settings.emoji,
                    onSelect = viewModel::selectEmoji,
                    onCustom = { showEmojiDialog = true },
                )
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.home_vibration),
                    subtitle = stringResource(R.string.home_vibration_desc),
                )
                PatternPicker(selected = state.settings.pattern, onSelect = viewModel::selectPattern)
            }
        }
    }

    if (showEmojiDialog) {
        CustomEmojiDialog(
            onDismiss = { showEmojiDialog = false },
            onConfirm = {
                viewModel.selectEmoji(it)
                showEmojiDialog = false
            },
        )
    }
}

@Composable
private fun SendStatus(state: HomeViewModel.SendState) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec())) },
        label = "sendStatus",
    ) { current ->
        val (text, color) = when (current) {
            HomeViewModel.SendState.Idle -> stringResource(R.string.home_tap_to_send) to MaterialTheme.colorScheme.onSurfaceVariant
            HomeViewModel.SendState.Sending -> stringResource(R.string.home_sending) to MaterialTheme.colorScheme.onSurfaceVariant
            HomeViewModel.SendState.Sent -> stringResource(R.string.send_ok) to MaterialTheme.colorScheme.primary
            is HomeViewModel.SendState.Message -> stringResource(current.text) to
                if (current.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.titleMediumEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun LastEchoCard(lastReceived: EchoEvent?, lastSent: EchoEvent?, now: Long) {
    val resources = LocalResources.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapedEmoji(
                emoji = lastReceived?.emoji ?: "🌙",
                shape = MaterialShapes.Sunny,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                size = 60.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.home_last_received),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = lastReceived?.let { RelativeTime.format(resources, it.timestamp, now) }
                        ?: stringResource(R.string.home_no_echo_yet),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                if (lastSent != null) {
                    Text(
                        stringResource(R.string.home_last_sent, RelativeTime.format(resources, lastSent.timestamp, now)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiPicker(selected: String, onSelect: (String) -> Unit, onCustom: () -> Unit) {
    val choices = if (selected in PRESET_EMOJIS) PRESET_EMOJIS else PRESET_EMOJIS + selected
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { emoji ->
            ToggleButton(
                checked = emoji == selected,
                onCheckedChange = { onSelect(emoji) },
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text(emoji, fontSize = 22.sp)
            }
        }
        ToggleButton(checked = false, onCheckedChange = { onCustom() }) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.home_custom_emoji))
        }
    }
}

@Composable
private fun PatternPicker(selected: VibrationPattern, onSelect: (VibrationPattern) -> Unit) {
    val patterns = VibrationPattern.entries
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        patterns.forEachIndexed { index, pattern ->
            val isSelected = pattern == selected
            SegmentedListItem(
                selected = isSelected,
                onClick = { onSelect(pattern) },
                shapes = ListItemDefaults.segmentedShapes(index, patterns.size),
                leadingContent = {
                    PatternGlyph(
                        pattern,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                supportingContent = { Text(stringResource(pattern.description)) },
                trailingContent = { RadioButton(selected = isSelected, onClick = null) },
            ) {
                Text(stringResource(pattern.label))
            }
        }
    }
}

@Composable
private fun PairMissingCard(onRelink: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LinkOff, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_pair_missing), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRelink) { Text(stringResource(R.string.home_pair_missing_action)) }
        }
    }
}

@Composable
private fun CustomEmojiDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    val cleaned = value.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_custom_emoji_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.length <= 16) value = it },
                label = { Text(stringResource(R.string.home_custom_emoji_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(cleaned) }, enabled = cleaned.isNotEmpty()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
