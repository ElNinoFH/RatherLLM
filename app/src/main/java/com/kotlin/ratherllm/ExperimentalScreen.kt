package com.kotlin.ratherllm

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/**
 * Experimental features: a master switch that unlocks two advanced, RAM-affecting
 * behaviors — removing the memory safety reserve, and multi-response (several models
 * answer per message). Each is confirmed before enabling. A live device-memory
 * panel shows the current safe ceiling so the trade-offs are concrete.
 */
@Composable
fun ExperimentalScreen(svc: InferenceService, onBack: () -> Unit) {
    val expOn by svc.experimentalEnabled.collectAsState()
    val maximizeOn by svc.maximizeMemory.collectAsState()
    val multiOn by svc.multiResponse.collectAsState()
    val context = LocalContext.current

    // Total is fixed; free RAM refreshes live so the ceiling reflects reality as
    // background apps are killed/spawned. Both come from the kernel's MemAvailable
    // (DeviceMemory), matching the phone's own free-RAM readout.
    val am = remember { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    val totalGb = remember { DeviceMemory.totalBytes(am) / 1e9 }
    val freeGb by androidx.compose.runtime.produceState(
        initialValue = DeviceMemory.availableBytes(am) / 1e9, am,
    ) {
        while (true) {
            value = DeviceMemory.availableBytes(am) / 1e9
            kotlinx.coroutines.delay(1500)
        }
    }
    val maxBNormal = floor((freeGb - 1.0).coerceAtLeast(0.0)).toInt()
    val maxBMax = floor(freeGb.coerceAtLeast(0.0)).toInt()

    var confirm by remember { mutableStateOf<String?>(null) } // "maximize" | "multi" | null

    Column(
        Modifier.fillMaxSize().background(AppColors.Bg).statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Text("Experimental features", style = AppText.ScreenTitle, color = AppColors.TextPrimary)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Master switch
            FeatureCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Experimental features", style = AppText.Small.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = AppColors.TextPrimary)
                        Text(
                            "Unlocks advanced, unsupported memory & multi-model behavior below. Use with care.",
                            style = AppText.Small.copy(fontSize = 11.5.sp, lineHeight = 17.sp), color = AppColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    GradientToggle(checked = expOn, onToggle = { svc.setExperimentalEnabled(!expOn) }, large = true)
                }
            }

            // Device memory panel (dashed)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AppColors.SurfaceDrawer, RoundedCornerShape(14.dp))
                    .dashedBorder(14.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("DEVICE MEMORY", style = AppText.MetaTiny.copy(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = AppColors.TextFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MemStat("Total", "${"%.0f".format(totalGb)} GB")
                    MemStat("Free", "${"%.1f".format(freeGb)} GB")
                }
                Text(
                    "Safe ceiling right now: up to ${maxBNormal}B params (1GB reserved). " +
                        "With Maximize memory allocation: up to ${maxBMax}B, using all free RAM.",
                    style = AppText.Small.copy(fontSize = 11.sp, lineHeight = 16.5.sp), color = AppColors.TextFaint,
                )
            }

            // Maximize memory
            FeatureCard(dim = !expOn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Maximize memory allocation", style = AppText.Small.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold), color = AppColors.TextPrimary)
                        Text(
                            "Removes the 1GB safety reserve so a model can use all free RAM — raises your ceiling from ${maxBNormal}B to ${maxBMax}B.",
                            style = AppText.Small.copy(fontSize = 11.5.sp, lineHeight = 17.sp), color = AppColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    GradientToggle(
                        checked = maximizeOn, enabled = expOn, large = true,
                        onToggle = { if (expOn) { if (maximizeOn) svc.setMaximizeMemory(false) else confirm = "maximize" } },
                    )
                }
            }

            // Multi-response
            FeatureCard(dim = !expOn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Multi-response model", style = AppText.Small.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold), color = AppColors.TextPrimary)
                        Text(
                            "Load more than one model at once and get an answer from each, back to back, for every message you send — up to your RAM budget.",
                            style = AppText.Small.copy(fontSize = 11.5.sp, lineHeight = 17.sp), color = AppColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    GradientToggle(
                        checked = multiOn, enabled = expOn, large = true,
                        onToggle = { if (expOn) { if (multiOn) svc.setMultiResponse(false) else confirm = "multi" } },
                    )
                }
            }
        }
    }

    when (confirm) {
        "maximize" -> ExperimentalConfirmDialog(
            title = "Enable Maximize memory allocation?",
            body = "This removes the 1GB safety reserve. Loading models close to your full ${"%.1f".format(freeGb)}GB free RAM may slow the device or fail to load.",
            onConfirm = { svc.setMaximizeMemory(true); confirm = null },
            onDismiss = { confirm = null },
        )
        "multi" -> ExperimentalConfirmDialog(
            title = "Enable Multi-response model?",
            body = "This lets you load multiple models at once and get an answer from each per message. Large combinations can still exceed available RAM.",
            onConfirm = { svc.setMultiResponse(true); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun FeatureCard(dim: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, AppColors.cream(0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp)
            .then(if (dim) Modifier.alpha(0.45f) else Modifier),
    ) { content() }
}

@Composable
private fun MemStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = AppText.Meta.copy(fontSize = 12.5.sp), color = AppColors.TextBody)
        Text(value, style = AppText.Meta.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold), color = AppColors.TextPrimary)
    }
}

/** Dashed 1dp border in the cream tint, rounded to [radius]. */
private fun Modifier.dashedBorder(radius: androidx.compose.ui.unit.Dp): Modifier = this.drawBehind {
    val stroke = Stroke(
        width = 1.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
    )
    drawRoundRect(
        color = AppColors.Cream.copy(alpha = 0.14f),
        style = stroke,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx(), radius.toPx()),
    )
}
