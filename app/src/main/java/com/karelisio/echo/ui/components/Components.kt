package com.karelisio.echo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import com.karelisio.echo.vibration.VibrationPattern
import kotlinx.coroutines.delay

/** Heure courante, rafraîchie régulièrement (pour les « il y a 5 min »). */
@Composable
fun rememberNow(intervalMs: Long = 30_000L): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(intervalMs)
        value = System.currentTimeMillis()
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(start = 4.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Emoji ou icône posé sur une forme Material 3 Expressive (cookie, trèfle, soleil…). */
@Composable
fun ShapedBadge(
    shape: RoundedPolygon,
    containerColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.size(size).clip(shape.toShape()).background(containerColor),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun ShapedEmoji(emoji: String, shape: RoundedPolygon, containerColor: Color, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    ShapedBadge(shape, containerColor, modifier, size) {
        Text(emoji, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
fun ShapedIcon(icon: ImageVector, shape: RoundedPolygon, containerColor: Color, contentColor: Color, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    ShapedBadge(shape, containerColor, modifier, size) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(size * 0.45f))
    }
}

/** Aperçu visuel d'un motif : une barre par impulsion, hauteur selon l'intensité. */
@Composable
fun PatternGlyph(pattern: VibrationPattern, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 56.dp, height = 28.dp)) {
        val total = pattern.durationMs.toFloat().coerceAtLeast(1f)
        val gap = 1.5.dp.toPx()
        var start = 0L
        pattern.timings.forEachIndexed { index, duration ->
            val amplitude = pattern.amplitudes[index]
            if (amplitude > 0 && duration > 0) {
                val x = size.width * start / total
                val width = (size.width * duration / total - gap).coerceAtLeast(2.dp.toPx())
                val height = size.height * (0.3f + 0.7f * amplitude / 255f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, (size.height - height) / 2),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2),
                )
            }
            start += duration
        }
    }
}
