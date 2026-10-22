package com.karelisio.echo.ui.update

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.echo.BuildConfig
import com.karelisio.echo.R
import com.karelisio.echo.appContainer
import com.karelisio.echo.system.SystemSettings
import com.karelisio.echo.ui.components.BigButton
import com.karelisio.echo.ui.components.ShapedIcon
import com.karelisio.echo.update.UpdateState
import com.karelisio.echo.update.offer

/** Carte « nouvelle version » : téléchargement, installation, erreurs. Rien tant qu'aucune version n'est proposée. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updates = context.appContainer.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val info = state.offer ?: return
    var askPermission by rememberSaveable { mutableStateOf(false) }
    // Retour des réglages d'Android avec l'autorisation accordée : l'installation démarre aussitôt.
    var installAfterPermission by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(installAfterPermission) {
        if (installAfterPermission && updates.canInstall()) {
            installAfterPermission = false
            updates.install(info)
        }
        onPauseOrDispose { }
    }
    val start = { if (updates.canInstall()) updates.install(info) else askPermission = true }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapedIcon(
                    icon = Icons.Rounded.SystemUpdate,
                    shape = MaterialShapes.Cookie9Sided,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_title, info.versionName), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(stringResource(R.string.update_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (info.notes.isNotBlank()) {
                Text(info.notes.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            UpdateProgress(state, onStart = start, onRestart = { updates.install(info) })
        }
    }

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            icon = { Icon(Icons.Rounded.InstallMobile, contentDescription = null) },
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askPermission = false
                    installAfterPermission = true
                    SystemSettings.openInstallPermission(context)
                }) { Text(stringResource(R.string.update_permission_action)) }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun UpdateProgress(state: UpdateState, onStart: () -> Unit, onRestart: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec())) },
        label = "update",
    ) { current ->
        when (current) {
            is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearWavyProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.update_downloading, (current.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is UpdateState.Installing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRestart, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.update_installing_again))
                }
            }
            is UpdateState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(current.reason.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                BigButton(text = stringResource(R.string.update_retry), onClick = onStart, modifier = Modifier.fillMaxWidth())
            }
            else -> BigButton(text = stringResource(R.string.update_action), onClick = onStart, modifier = Modifier.fillMaxWidth())
        }
    }
}

@StringRes
private fun UpdateState.Reason.messageRes(): Int = when (this) {
    UpdateState.Reason.Download -> R.string.update_error_download
    UpdateState.Reason.Integrity -> R.string.update_error_integrity
    UpdateState.Reason.Conflict -> R.string.update_error_conflict
    UpdateState.Reason.Storage -> R.string.update_error_storage
    UpdateState.Reason.Blocked -> R.string.update_error_blocked
    UpdateState.Reason.Install -> R.string.update_error_install
}
