package com.kotlin.ratherllm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Power-user settings, hidden behind a bottom sheet: a system prompt plus the
 * sampling parameters. Changes take effect on the next message (no reload). Each
 * control carries a one-line explanation and there is a reset-to-defaults button.
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

    fun push(p: GenParams = params, s: String = sys) { params = p; sys = s; onApply(p, s) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))

            Text("System prompt", style = MaterialTheme.typography.titleSmall)
            Text(
                "Persistent instruction prepended to every conversation.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = sys,
                onValueChange = { push(s = it) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                placeholder = { Text("e.g. You are a concise, helpful assistant.") },
                minLines = 2, maxLines = 4,
            )

            ParamSlider("Temperature", params.temperature, 0f..2f,
                "Higher = more creative/random; lower = more focused.") { push(params.copy(temperature = it)) }
            ParamSlider("Top-K", params.topK.toFloat(), 1f..100f,
                "Sample only from the K most likely tokens.", isInt = true) { push(params.copy(topK = it.toInt())) }
            ParamSlider("Top-P", params.topP, 0f..1f,
                "Nucleus sampling: keep the smallest set of tokens with cumulative prob ≥ P.") { push(params.copy(topP = it)) }
            ParamSlider("Min-P", params.minP, 0f..0.5f,
                "Drop tokens below this fraction of the top token's probability (filters garbage).") { push(params.copy(minP = it)) }
            ParamSlider("Repeat penalty", params.repeatPenalty, 1f..1.5f,
                "Penalize recently used tokens to curb repetition/loops.") { push(params.copy(repeatPenalty = it)) }
            ParamSlider("Max tokens", params.maxTokens.toFloat(), 64f..2048f,
                "Upper bound on the reply length.", isInt = true) { push(params.copy(maxTokens = it.toInt())) }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { push(GenParams(), "") }) { Text("Reset to defaults") }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    help: String,
    isInt: Boolean = false,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                if (isInt) value.toInt().toString() else String.format(Locale.US, "%.2f", value),
                style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
        Text(help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
