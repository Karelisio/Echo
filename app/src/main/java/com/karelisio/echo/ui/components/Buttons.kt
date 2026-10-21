package com.karelisio.echo.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class BigButtonStyle { Filled, Tonal, Outlined }

/** Bouton « medium » Material 3 Expressive, dont la forme se resserre à l'appui. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BigButtonStyle = BigButtonStyle.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val shapes = ButtonDefaults.shapesFor(height)
    val padding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = icon != null)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ButtonDefaults.textStyleFor(height))
    }
    val sized = modifier.heightIn(min = height)
    when (style) {
        BigButtonStyle.Filled -> Button(onClick, shapes, sized, enabled, contentPadding = padding, content = content)
        BigButtonStyle.Tonal -> FilledTonalButton(onClick, shapes, sized, enabled, contentPadding = padding, content = content)
        BigButtonStyle.Outlined -> OutlinedButton(onClick, shapes, sized, enabled, contentPadding = padding, content = content)
    }
}
