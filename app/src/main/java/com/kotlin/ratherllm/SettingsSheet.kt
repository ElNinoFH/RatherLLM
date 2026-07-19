package com.kotlin.ratherllm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** A tunable sampling parameter with its slider range and detailed help text. */
private data class ParamDef(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val isInt: Boolean,
    val get: (GenParams) -> Float,
    val set: (GenParams, Float) -> GenParams,
    val info: String,
)

private val PARAM_DEFS = listOf(
    ParamDef("temperature", "Temperature", 0f, 2f, false, { it.temperature }, { p, v -> p.copy(temperature = v) },
        "Adjusts how imaginative the AI's responses are. Lower = strict, definitive, logical. Higher = creative and casual (may wander off-topic).\n\nExamples:\n• Low (0.2–0.5): math, translation, data analysis — anything needing factual answers.\n• High (0.9–1.2): stories, poems, brainstorming — anything needing creative ideas."),
    ParamDef("topK", "Top-K", 1f, 100f, true, { it.topK.toFloat() }, { p, v -> p.copy(topK = v.toInt()) },
        "Prevents the AI from choosing completely unrelated words. Smaller = sticks to common, safe words.\n\nExample: keep it around 40–60 — fluent sentences without sudden, bizarre word choices."),
    ParamDef("topP", "Top-P", 0f, 1f, false, { it.topP }, { p, v -> p.copy(topP = v) },
        "Similar to Top-K — filters for sensible words so sentences sound natural and flow like normal human writing.\n\nExample: 0.90–0.95 keeps storytelling smooth without losing direction."),
    ParamDef("minP", "Min-P", 0f, 0.5f, false, { it.minP }, { p, v -> p.copy(minP = v) },
        "A smarter, more modern version of Top-P. Automatically discards \"garbage\" words when the AI gets confused, keeping it focused on your topic.\n\nExample: 0.05 — freely creative on casual topics, but strict and precise on technical questions."),
    ParamDef("repeatPenalty", "Repeat penalty", 1f, 1.5f, false, { it.repeatPenalty }, { p, v -> p.copy(repeatPenalty = v) },
        "Penalizes the AI so it doesn't repeat the same words, sentences, or points. Higher = more forced to use synonyms and varied wording.\n\nExample: if replies keep starting with \"In conclusion...\" or \"Furthermore...\", raise this to 1.15–1.20 for more varied vocabulary."),
    ParamDef("maxTokens", "Max tokens", 64f, 2048f, true, { it.maxTokens.toFloat() }, { p, v -> p.copy(maxTokens = v.toInt()) },
        "Adjusts how long the AI is allowed to reply — a maximum length limit.\n\nExamples:\n• Small (~150): customer-service chatbot, quick Q&A.\n• Large (1000–2000): long blog articles, formal letters, full code blocks."),
)

private const val SYSTEM_PROMPT_INFO =
    "Sets the AI's \"personality\" or ground rules. Whatever you write here is remembered and followed throughout the conversation.\n\nExamples:\n• \"You are a patient English teacher.\" — for learning help.\n• \"Answer very briefly without any fluff.\" — for just the main points."

/**
 * Power-user settings in a bottom sheet: a system prompt and the sampling
 * parameters, each with a collapsible one-tap explanation. Changes apply to the
 * next message (no reload) and are persisted by the service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    current: GenParams,
    systemPrompt: String,
    onApply: (GenParams, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var params by remember { mutableStateOf(current) }
    var sys by remember { mutableStateOf(systemPrompt) }
    var infoFor by remember { mutableStateOf<String?>(null) }

    fun push(p: GenParams = params, s: String = sys) { params = p; sys = s; onApply(p, s) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.SurfaceSheet,
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(AppColors.cream(0.20f), RoundedCornerShape(2.dp)),
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Settings", style = AppText.SheetTitle, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("System prompt", style = AppText.Small.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = AppColors.TextPrimary)
                InfoDot(onClick = { infoFor = if (infoFor == "system") null else "system" })
            }
            InfoPanel(visible = infoFor == "system", text = SYSTEM_PROMPT_INFO)
            DialogField(
                value = sys, onChange = { push(s = it) },
                placeholder = "e.g. You are a concise, helpful assistant.",
                minLines = 3, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )

            PARAM_DEFS.forEach { def ->
                val value = def.get(params)
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(def.label, style = AppText.Small.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = AppColors.TextPrimary)
                            InfoDot(onClick = { infoFor = if (infoFor == def.key) null else def.key })
                        }
                        Text(
                            if (def.isInt) value.toInt().toString() else String.format(Locale.US, "%.2f", value),
                            style = AppText.Meta.copy(fontSize = 12.sp), color = AppColors.TextBody,
                        )
                    }
                    Slider(
                        value = value,
                        onValueChange = { push(def.set(params, it)) },
                        valueRange = def.min..def.max,
                        colors = SliderDefaults.colors(
                            thumbColor = AppColors.AccentB,
                            activeTrackColor = AppColors.AccentA,
                            inactiveTrackColor = AppColors.cream(0.14f),
                        ),
                    )
                    InfoPanel(visible = infoFor == def.key, text = def.info)
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clickable { push(GenParams(), ""); infoFor = null }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GradientText("Reset to defaults", style = AppText.Small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}
