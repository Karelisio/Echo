package com.karelisio.echo.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.karelisio.echo.MainActivity
import com.karelisio.echo.R
import com.karelisio.echo.appContainer
import com.karelisio.echo.ui.theme.EchoDarkColors
import com.karelisio.echo.ui.theme.EchoLightColors
import com.karelisio.echo.util.RelativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Widget d'écran d'accueil : un tap envoie un écho, et il affiche le dernier écho reçu.
 * Couleurs dynamiques (Material You) via GlanceTheme, coins arrondis du système, redimensionnable.
 */
class EchoWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, SQUARE, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.appContainer
        withContext(Dispatchers.IO) { container.pairingStore.ensureLoaded() }
        val controller = container.widget
        val initial = controller.state.first()
        provideContent {
            val state by controller.state.collectAsState(initial)
            GlanceTheme(colors = if (Build.VERSION.SDK_INT >= 31) GlanceTheme.colors else FallbackColors) {
                WidgetContent(state)
            }
        }
    }

    companion object {
        val SMALL = DpSize(60.dp, 60.dp)
        val SQUARE = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(180.dp, 60.dp)
        val LARGE = DpSize(250.dp, 110.dp)

        /** Avant Android 12 (pas de couleurs dynamiques) : la palette violette de l'appli. */
        private val FallbackColors: ColorProviders = ColorProviders(light = EchoLightColors, dark = EchoDarkColors)
    }
}

@Composable
internal fun WidgetContent(state: WidgetUiState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val action = if (state.paired) actionRunCallback<SendEchoAction>() else actionStartActivity<MainActivity>()
    val description = context.getString(if (state.paired) R.string.widget_send_cd else R.string.widget_open_cd)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .widgetBackground()
            .clickable(action)
            .semantics { contentDescription = description }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !state.paired -> NotPaired(compact = size == SMALL_SIZE)
            size == SMALL_SIZE -> EchoBubble(state, bubbleSize = 46.dp, emojiSize = 24.sp)
            size == EchoWidget.WIDE || size == EchoWidget.LARGE -> Row(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val large = size == EchoWidget.LARGE
                EchoBubble(state, bubbleSize = if (large) 76.dp else 44.dp, emojiSize = if (large) 38.sp else 22.sp)
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = context.getString(R.string.widget_last_echo),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        maxLines = 1,
                    )
                    StatusLine(state, fontSize = if (large) 18.sp else 15.sp, alignment = TextAlign.Start)
                    val message = state.lastReceived?.message
                    if (large && message != null && state.status == WidgetStatus.Idle) {
                        Text(
                            text = context.getString(R.string.quoted, message),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                            maxLines = 2,
                        )
                    }
                }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EchoBubble(state, bubbleSize = 64.dp, emojiSize = 32.sp)
                Spacer(GlanceModifier.height(8.dp))
                StatusLine(state, fontSize = 13.sp, alignment = TextAlign.Center)
            }
        }
    }
}

private val SMALL_SIZE = EchoWidget.SMALL

@Composable
private fun EchoBubble(state: WidgetUiState, bubbleSize: Dp, emojiSize: TextUnit) {
    Box(
        modifier = GlanceModifier
            .size(bubbleSize)
            .background(
                imageProvider = ImageProvider(R.drawable.widget_circle),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primaryContainer),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state.status == WidgetStatus.Sending) {
            CircularProgressIndicator(
                modifier = GlanceModifier.size(bubbleSize * 0.5f),
                color = GlanceTheme.colors.onPrimaryContainer,
            )
        } else {
            Text(
                text = if (state.status == WidgetStatus.Sent) "✓" else state.emoji,
                style = TextStyle(
                    fontSize = emojiSize,
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun StatusLine(state: WidgetUiState, fontSize: TextUnit, alignment: TextAlign) {
    val context = LocalContext.current
    val (text, color) = when (state.status) {
        WidgetStatus.Sending -> context.getString(R.string.widget_sending) to GlanceTheme.colors.onSurface
        WidgetStatus.Sent -> context.getString(R.string.widget_sent) to GlanceTheme.colors.primary
        WidgetStatus.Cooldown -> context.getString(R.string.widget_cooldown) to GlanceTheme.colors.onSurfaceVariant
        WidgetStatus.Failed -> context.getString(R.string.widget_failed) to GlanceTheme.colors.error
        WidgetStatus.Idle -> lastReceivedLabel(context, state) to GlanceTheme.colors.onSurface
    }
    Text(
        text = text,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = FontWeight.Medium, textAlign = alignment),
        maxLines = 2,
    )
}

private fun lastReceivedLabel(context: Context, state: WidgetUiState): String {
    val last = state.lastReceived ?: return context.getString(R.string.widget_no_echo)
    val elapsed = RelativeTime.format(context.resources, last.timestamp, state.now, coarse = true)
    return "${last.emoji} $elapsed"
}

@Composable
private fun NotPaired(compact: Boolean) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            provider = ImageProvider(R.drawable.ic_stat_echo),
            contentDescription = null,
            modifier = GlanceModifier.size(if (compact) 28.dp else 36.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
        if (!compact) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = context.getString(R.string.widget_not_paired),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, textAlign = TextAlign.Center),
                maxLines = 3,
            )
        }
    }
}

/** Coins arrondis du système (Android 12+), ou forme arrondie équivalente avant. */
@Composable
private fun GlanceModifier.widgetBackground(): GlanceModifier {
    val color: ColorProvider = GlanceTheme.colors.widgetBackground
    return if (Build.VERSION.SDK_INT >= 31) {
        background(color).cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        background(imageProvider = ImageProvider(R.drawable.widget_background), colorFilter = ColorFilter.tint(color))
    }
}
