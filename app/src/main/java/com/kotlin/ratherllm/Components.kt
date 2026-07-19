package com.kotlin.ratherllm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable widgets shared across screens: the gradient pill toggle, gradient-filled
 * text, round icon buttons, popup menu surface, toast host, and the collapsible
 * info panel used in Settings.
 */

/** Text whose glyphs are painted with a [Brush] (the brand gradient by default). */
@Composable
fun GradientText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    brush: Brush = AccentBrush,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = brush),
        textAlign = textAlign,
        maxLines = maxLines,
    )
}

/** The rounded "switch" toggle. [trackWidth]/[knob] size it (small 32×18, large 40×22). */
@Composable
fun GradientToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val trackW = if (large) 40.dp else 32.dp
    val trackH = if (large) 22.dp else 18.dp
    val knobD = if (large) 18.dp else 14.dp
    val travel = trackW - knobD - 4.dp
    val knobOffset by animateDpAsState(if (checked) travel else 0.dp, label = "knob")
    Box(
        modifier
            .size(trackW, trackH)
            .background(
                if (checked) AccentBrush else Brush.linearGradient(listOf(AppColors.cream(0.15f), AppColors.cream(0.15f))),
                RoundedCornerShape(trackH / 2),
            )
            .clickable(
                enabled = enabled,
                interactionSource = rememberInteraction(),
                indication = null,
            ) { onToggle() },
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .offset(x = knobOffset)
                .size(knobD)
                .background(AppColors.TextPrimary, CircleShape),
        )
    }
}

@Composable
private fun rememberInteraction() = androidx.compose.runtime.remember { MutableInteractionSource() }

/** A square, subtly-rounded icon button (transparent, ripple on tap). */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 38,
    iconSizeDp: Int = 19,
    tint: Color = AppColors.IconTint,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(sizeDp.dp)
            .background(Color.Transparent, RoundedCornerShape((sizeDp * 0.29f).dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSizeDp.dp))
    }
}

/** Full-screen invisible scrim that dismisses a popup when tapped outside. */
@Composable
fun DismissScrim(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .clickable(interactionSource = rememberInteraction(), indication = null) { onClick() },
    )
}

/** A popup panel surface (menu / dropdown) with the design's border + rounded corners. */
@Composable
fun MenuSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(AppColors.SurfaceMenu, RoundedCornerShape(14.dp))
            .border(1.dp, AppColors.cream(0.10f), RoundedCornerShape(14.dp))
            .padding(6.dp),
    ) { content() }
}

/** A single row inside a [MenuSurface]. */
@Composable
fun MenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    color: Color = AppColors.TextBody,
) {
    Row(
        modifier
            .background(Color.Transparent, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Text(label, style = AppText.Label, color = color, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Collapsible help panel (used under Settings labels). */
@Composable
fun InfoPanel(visible: Boolean, text: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .background(AppColors.cream(0.04f), RoundedCornerShape(10.dp))
                .border(1.dp, AppColors.cream(0.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(text, style = AppText.Small.copy(lineHeight = 18.sp), color = AppColors.TextSecondary)
        }
    }
}

/** Small "i" info button (bordered circle). */
@Composable
fun InfoDot(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(16.dp)
            .border(1.dp, AppColors.cream(0.30f), CircleShape)
            .clickable(interactionSource = rememberInteraction(), indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("i", style = AppText.MetaTiny.copy(fontSize = 10.sp), color = AppColors.TextSecondary)
    }
}

/** Bottom-anchored transient toast. */
@Composable
fun ToastHost(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .background(AppColors.SurfaceBubble, RoundedCornerShape(14.dp))
                .border(1.dp, AppColors.cream(0.12f), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(message ?: "", style = AppText.Small, color = AppColors.TextPrimary)
        }
    }
}

/** Spacer helper that consumes remaining width in a Row. */
@Composable
fun RowSpacer() = Box(Modifier.height(0.dp))

/** Ignore system-bar insets consumer used by full-bleed overlays. */
val NoInsets = WindowInsets(0, 0, 0, 0)
