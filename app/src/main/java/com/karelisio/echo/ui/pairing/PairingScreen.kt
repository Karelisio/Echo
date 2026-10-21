package com.karelisio.echo.ui.pairing

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.echo.R
import com.karelisio.echo.appContainer
import com.karelisio.echo.data.pairing.PairingState
import com.karelisio.echo.ui.components.BigButton
import com.karelisio.echo.ui.components.BigButtonStyle
import com.karelisio.echo.ui.components.ShapedIcon
import com.karelisio.echo.ui.components.rememberNow
import kotlinx.coroutines.delay

private const val CODE_LENGTH = 6
private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val CODE_LIFETIME_MS = 10 * 60_000L
private const val POLL_INTERVAL_MS = 3_000L

private sealed interface Content {
    data object Choice : Content
    data object Join : Content
    data class Code(val pending: PairingState.Pending) : Content
}

@Composable
fun PairingScreen() {
    val container = LocalContext.current.appContainer
    val viewModel = viewModel { PairingViewModel(container) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val pending = pairing as? PairingState.Pending

    // Téléphone A : tant que le code est affiché (et l'écran visible), on demande au relais si l'autre a rejoint.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(pending?.credentials?.pairId) {
        if (pending == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                viewModel.pollPending()
            }
        }
    }

    val content = when {
        pending != null -> Content.Code(pending)
        ui.mode == PairingViewModel.Mode.Join -> Content.Join
        else -> Content.Choice
    }
    BackHandler(enabled = content == Content.Join) { viewModel.backToChoice() }

    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = content,
        contentKey = { it::class },
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.94f))
                .togetherWith(fadeOut(motion.fastEffectsSpec()))
        },
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        label = "pairing",
    ) { current ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (current) {
                Content.Choice -> ChoiceContent(
                    busy = ui.busy,
                    onCreate = viewModel::createCode,
                    onJoin = viewModel::showJoin,
                )
                Content.Join -> JoinContent(
                    busy = ui.busy,
                    onJoin = viewModel::join,
                    onBack = viewModel::backToChoice,
                )
                is Content.Code -> CodeContent(
                    pending = current.pending,
                    busy = ui.busy,
                    onRenew = viewModel::renewCode,
                    onCancel = viewModel::cancelCode,
                )
            }
            AnimatedVisibility(visible = ui.error != null) {
                Text(
                    text = ui.error?.let { stringResource(it) }.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ChoiceContent(busy: Boolean, onCreate: () -> Unit, onJoin: () -> Unit) {
    ShapedIcon(
        icon = Icons.Rounded.Link,
        shape = MaterialShapes.Flower,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        size = 160.dp,
    )
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.pairing_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(32.dp))
    ChoiceCard(
        icon = Icons.Rounded.Pin,
        title = stringResource(R.string.pairing_create),
        description = stringResource(R.string.pairing_create_desc),
        busy = busy,
        onClick = onCreate,
        primary = true,
    )
    Spacer(Modifier.height(12.dp))
    ChoiceCard(
        icon = Icons.Rounded.Keyboard,
        title = stringResource(R.string.pairing_join),
        description = stringResource(R.string.pairing_join_desc),
        busy = false,
        onClick = onJoin,
        primary = false,
    )
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, description: String, busy: Boolean, onClick: () -> Unit, primary: Boolean) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (primary) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (primary) colors.onPrimaryContainer else colors.onSurface,
            disabledContainerColor = if (primary) colors.primaryContainer else colors.surfaceContainerHigh,
            disabledContentColor = if (primary) colors.onPrimaryContainer else colors.onSurface,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (busy) LoadingIndicator(Modifier.size(48.dp)) else Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CodeContent(pending: PairingState.Pending, busy: Boolean, onRenew: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val now by rememberNow(1_000L)
    val remaining = (pending.expiresAt - now).coerceAtLeast(0)
    val expired = remaining == 0L

    Text(stringResource(R.string.pairing_code_title), style = MaterialTheme.typography.headlineMediumEmphasized)
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pending.code.forEach { char ->
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (expired) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    char.toString(),
                    style = MaterialTheme.typography.headlineLargeEmphasized,
                    color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    if (expired) {
        Text(stringResource(R.string.pairing_expired), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(24.dp))
        BigButton(stringResource(R.string.pairing_new_code), onRenew, Modifier.fillMaxWidth(), enabled = !busy)
    } else {
        Text(
            stringResource(R.string.pairing_code_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.pairing_waiting), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        LinearWavyProgressIndicator(
            progress = { remaining.toFloat() / CODE_LIFETIME_MS },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val seconds = remaining / 1000
        Text(
            stringResource(R.string.pairing_expires_in, "%d:%02d".format(seconds / 60, seconds % 60)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        val shareText = stringResource(R.string.pairing_share_text, pending.code)
        BigButton(
            text = stringResource(R.string.pairing_share),
            onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, shareText)
                context.startActivity(Intent.createChooser(send, null))
            },
            modifier = Modifier.fillMaxWidth(),
            style = BigButtonStyle.Tonal,
            icon = Icons.Rounded.Share,
        )
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun JoinContent(busy: Boolean, onJoin: (String) -> Unit, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val complete = code.length == CODE_LENGTH

    ShapedIcon(
        icon = Icons.Rounded.Keyboard,
        shape = MaterialShapes.Cookie7Sided,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        size = 140.dp,
    )
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.pairing_join), style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.pairing_join_desc),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { input ->
            code = input.uppercase().filter { it in CODE_ALPHABET }.take(CODE_LENGTH)
        },
        label = { Text(stringResource(R.string.pairing_join_label)) },
        singleLine = true,
        enabled = !busy,
        textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 8.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (complete && !busy) onJoin(code) }),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    if (busy) {
        LoadingIndicator(Modifier.size(56.dp))
    } else {
        BigButton(stringResource(R.string.pairing_join_action), { onJoin(code) }, Modifier.fillMaxWidth(), enabled = complete)
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_back))
    }
}
