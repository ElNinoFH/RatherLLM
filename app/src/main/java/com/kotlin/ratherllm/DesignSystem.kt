package com.kotlin.ratherllm

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Central design tokens transcribed from the "RatherLLM Dark" handoff design.
 * Every color, gradient, and text style used across the app resolves here so the
 * look stays consistent and is trivial to re-theme.
 */
object AppColors {
    val Bg = Color(0xFF1B1916)             // app background
    val BgRadialInner = Color(0xFF1B1814)  // subtle greeting glow center
    val Surface = Color(0xFF242019)        // cards, input bar
    val SurfaceSheet = Color(0xFF26221C)   // settings bottom sheet
    val SurfaceDrawer = Color(0xFF201D18)  // navigation drawer
    val SurfaceMenu = Color(0xFF292520)    // popup menus
    val SurfaceDialog = Color(0xFF2A2620)  // modal dialogs
    val SurfaceBubble = Color(0xFF2E2A24)  // user chat bubble, toast
    val CodeBg = Color(0xFF16130F)         // fenced code block
    val FieldBg = Color(0xFF1D1A15)        // text inputs inside sheets

    val TextPrimary = Color(0xFFEDE7DC)
    val TextBody = Color(0xFFDED7CA)
    val TextSecondary = Color(0xFFA69C8D)
    val TextMuted = Color(0xFF8C8375)
    val TextFaint = Color(0xFF756C60)
    val IconTint = Color(0xFFB8AD9E)
    val CodeText = Color(0xFFC8D4F5)

    val Danger = Color(0xFFFF8F7D)
    val Teal = Color(0xFF7FD8C8)
    val Orange = Color(0xFFE8B98A)

    // Accent gradient endpoints (the "115deg" brand gradient).
    val AccentA = Color(0xFF86A5FF)
    val AccentB = Color(0xFFB28AFF)

    /** Base cream used for translucent borders/hovers: rgba(236,227,213,a). */
    val Cream = Color(0xFFECE3D5)
    fun cream(alpha: Float) = Cream.copy(alpha = alpha)
}

/** The brand gradient, angled roughly 115° like the CSS `linear-gradient(115deg,…)`. */
val AccentBrush: Brush
    get() = Brush.linearGradient(
        colors = listOf(AppColors.AccentA, AppColors.AccentB),
        start = Offset(0f, 0f),
        end = Offset(120f, 90f),
    )

/** Three-stop gradient used for the greeting headline. */
val GreetingBrush: Brush
    get() = Brush.linearGradient(
        colors = listOf(AppColors.AccentA, AppColors.AccentB, AppColors.Orange),
        start = Offset(0f, 0f),
        end = Offset(320f, 120f),
    )

/** Font families. Lora→Serif for the wordmark/greeting, Figtree→SansSerif for body, mono for meta. */
object AppFonts {
    val Display = FontFamily.Serif
    val Body = FontFamily.SansSerif
    val Mono = FontFamily.Monospace
}

/** Named text styles mirroring the pixel sizes in the design. */
object AppText {
    val Wordmark = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val WordmarkLarge = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
    val Greeting = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.Medium, fontSize = 33.sp, lineHeight = 39.sp)
    val ScreenTitle = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val SheetTitle = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

    val Body = TextStyle(fontFamily = AppFonts.Body, fontSize = 14.5.sp, lineHeight = 23.sp)
    val Message = TextStyle(fontFamily = AppFonts.Body, fontSize = 14.5.sp, lineHeight = 23.5.sp)
    val Label = TextStyle(fontFamily = AppFonts.Body, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val Small = TextStyle(fontFamily = AppFonts.Body, fontSize = 12.sp)
    val Meta = TextStyle(fontFamily = AppFonts.Mono, fontSize = 10.5.sp, lineHeight = 15.sp)
    val MetaTiny = TextStyle(fontFamily = AppFonts.Mono, fontSize = 9.5.sp)
    val Code = TextStyle(fontFamily = AppFonts.Mono, fontSize = 12.sp, lineHeight = 18.sp)
}

/** Shadow used behind popovers/dialogs. */
val PopupShadow = Shadow(color = Color(0x73000000), blurRadius = 32f, offset = Offset(0f, 12f))

/** The little rotated-square "diamond" that marks the assistant/brand throughout the UI. */
@Composable
fun AccentDiamond(sizeDp: Int, modifier: Modifier = Modifier, blink: Boolean = false) {
    val alpha = if (blink) {
        val transition = rememberInfiniteTransition(label = "blink")
        val a by transition.animateFloat(
            initialValue = 1f, targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "blinkAlpha",
        )
        a
    } else 1f
    Box(
        modifier
            .size(sizeDp.dp)
            .graphicsLayer { rotationZ = 45f; this.alpha = alpha }
            .background(AccentBrush, RoundedCornerShape((sizeDp * 0.22f).dp)),
    )
}
