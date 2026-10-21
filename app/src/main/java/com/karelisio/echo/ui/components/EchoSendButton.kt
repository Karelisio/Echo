package com.karelisio.echo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph

/**
 * Gros bouton d'envoi : un « cookie » qui tourne lentement et se transforme en cœur quand on
 * appuie ou qu'un écho vient de partir (morphing de formes Material 3 Expressive).
 */
@Composable
fun EchoSendButton(
    emoji: String,
    sending: Boolean,
    celebrate: Boolean,
    cooldownProgress: () -> Float,
    showCooldown: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 236.dp,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Heart) }
    val path = remember { Path() }
    val morphProgress = animateFloatAsState(
        targetValue = if (pressed || celebrate) 1f else 0f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "morph",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = motion.fastSpatialSpec(),
        label = "scale",
    )
    val spin = rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 30_000, easing = LinearEasing)),
        label = "spin",
    )
    val containerColor = animateColorAsState(
        targetValue = if (celebrate) colors.tertiaryContainer else colors.primaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "container",
    )

    Box(modifier.size(size + 28.dp), contentAlignment = Alignment.Center) {
        if (showCooldown) {
            CircularWavyProgressIndicator(progress = cooldownProgress, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                }
                .clickable(interactionSource = interactionSource, indication = null, enabled = !sending, onClick = onClick)
                .drawBehind {
                    val progress = morphProgress.value
                    // La rotation s'efface à mesure que la forme devient un cœur (qui reste droit).
                    rotate(spin.value * (1f - progress)) {
                        drawPath(morph.toPath(progress, this.size, path), containerColor.value)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (sending) {
                LoadingIndicator(modifier = Modifier.size(size / 3), color = colors.onPrimaryContainer)
            } else {
                Text(text = emoji, fontSize = (size.value * 0.36f).sp)
            }
        }
    }
}

/** Contour du morphing (formes normalisées sur [0, 1]) mis à l'échelle de [size]. */
private fun Morph.toPath(progress: Float, size: Size, path: Path): Path {
    path.rewind()
    var first = true
    forEachCubic(progress) { cubic ->
        if (first) {
            path.moveTo(cubic.anchor0X * size.width, cubic.anchor0Y * size.height)
            first = false
        }
        path.cubicTo(
            cubic.control0X * size.width, cubic.control0Y * size.height,
            cubic.control1X * size.width, cubic.control1Y * size.height,
            cubic.anchor1X * size.width, cubic.anchor1Y * size.height,
        )
    }
    path.close()
    return path
}
