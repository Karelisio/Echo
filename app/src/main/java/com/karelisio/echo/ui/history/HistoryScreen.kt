package com.karelisio.echo.ui.history

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.echo.AppContainer
import com.karelisio.echo.R
import com.karelisio.echo.appContainer
import com.karelisio.echo.data.history.EchoDirection
import com.karelisio.echo.data.history.EchoEvent
import com.karelisio.echo.ui.components.ShapedEmoji
import com.karelisio.echo.ui.components.ShapedIcon
import com.karelisio.echo.ui.components.rememberNow
import com.karelisio.echo.vibration.VibrationPattern
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    /** `null` pendant le premier chargement. */
    val events: StateFlow<List<EchoEvent>?> =
        container.history.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() {
        viewModelScope.launch {
            container.history.clear()
            container.widget.refresh()
        }
    }
}

@Composable
fun HistoryScreen(outerPadding: PaddingValues) {
    val container = LocalContext.current.appContainer
    val viewModel = viewModel { HistoryViewModel(container) }
    val events by viewModel.events.collectAsStateWithLifecycle()
    val now by rememberNow(60_000L)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val list = events.orEmpty()
    val days = remember(list) { list.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() } }
    val todayEvents = days[today].orEmpty()
    val receivedToday = todayEvents.count { it.direction == EchoDirection.RECEIVED }
    val sentToday = todayEvents.size - receivedToday

    Scaffold(
        modifier = Modifier.padding(outerPadding).nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                subtitle = {
                    Text(
                        stringResource(
                            R.string.history_summary,
                            pluralStringResource(R.plurals.history_received, receivedToday, receivedToday),
                            pluralStringResource(R.plurals.history_sent, sentToday, sentToday),
                        ),
                    )
                },
                actions = {
                    if (list.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.history_clear))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        when {
            events == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            list.isEmpty() -> EmptyHistory(Modifier.padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
            ) {
                days.forEach { (date, dayEvents) ->
                    item(key = "day-$date") { DayHeader(date, today) }
                    itemsIndexed(dayEvents, key = { _, event -> event.id }) { index, event ->
                        HistoryItem(event, index, dayEvents.size, Modifier.padding(bottom = ListItemDefaults.SegmentedGap))
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.history_clear_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text(stringResource(R.string.history_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    val label = when (date) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> {
            val pattern = if (date.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy"
            // L'appli n'existe qu'en français : dates en français quelle que soit la langue du système.
            date.format(DateTimeFormatter.ofPattern(pattern, Locale.FRENCH))
                .replaceFirstChar { it.titlecase(Locale.FRENCH) }
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun HistoryItem(event: EchoEvent, index: Int, count: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val received = event.direction == EchoDirection.RECEIVED
    val time = remember(event.timestamp) { DateFormat.getTimeFormat(context).format(Date(event.timestamp)) }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = modifier,
        leadingContent = {
            ShapedEmoji(
                emoji = event.emoji,
                shape = if (received) MaterialShapes.Cookie6Sided else MaterialShapes.Circle,
                containerColor = if (received) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                size = 44.dp,
            )
        },
        supportingContent = { Text(stringResource(VibrationPattern.fromId(event.pattern).label)) },
        trailingContent = { Text(time, style = MaterialTheme.typography.labelLarge) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (received) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast,
                contentDescription = null,
                tint = if (received) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (received) R.string.history_received_label else R.string.history_sent_label))
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ShapedIcon(
            icon = Icons.Rounded.History,
            shape = MaterialShapes.Clover4Leaf,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            size = 128.dp,
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.headlineSmallEmphasized)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.history_empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
