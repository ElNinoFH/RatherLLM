package com.kotlin.ratherllm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The in-chat model picker: a dropdown listing on-device models. In multi-response
 * mode (an experimental feature) it becomes a checklist bounded by the live RAM
 * budget, with a commit button; otherwise tapping a model loads it.
 */
@Composable
fun ModelPickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    models: List<ModelEntry>,
    metas: Map<String, ModelMeta>,
    activePath: String?,
    multiEnabled: Boolean,
    multiSelected: List<String>,
    ramBudgetGb: () -> Double,
    onPick: (String) -> Unit,
    onCommitMulti: (List<String>) -> Unit,
    onManage: () -> Unit,
    onToast: (String) -> Unit,
) {
    var multiMode by remember(expanded) { mutableStateOf(multiEnabled && multiSelected.size > 1) }
    var selected by remember(expanded) {
        mutableStateOf(multiSelected.toSet().ifEmpty { setOfNotNull(activePath) })
    }
    val budget = remember(expanded) { ramBudgetGb() }
    fun ramGb(entry: ModelEntry) = entry.estimatedRamBytes / 1e9
    val usedGb = models.filter { it.path in selected }.sumOf { ramGb(it) }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(305.dp).background(AppColors.SurfaceMenu).heightIn(max = 400.dp),
    ) {
        if (multiEnabled) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .padding(bottom = 4.dp)
                    .background(AppColors.cream(0.04f), RoundedCornerShape(10.dp))
                    .clickable {
                        multiMode = !multiMode
                        selected = if (multiMode) setOfNotNull(activePath) else emptySet()
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Multi-response model", style = AppText.Small.copy(fontWeight = FontWeight.SemiBold),
                    color = AppColors.TextBody, modifier = Modifier.weight(1f))
                GradientToggle(checked = multiMode, onToggle = {
                    multiMode = !multiMode
                    selected = if (multiMode) setOfNotNull(activePath) else emptySet()
                })
            }
        }

        SectionLabel("On-device models")

        if (models.isEmpty()) {
            Text("No models yet — import one from Manage models.",
                style = AppText.Small, color = AppColors.TextFaint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }

        models.forEach { entry ->
            val meta = metas[entry.name] ?: ModelMeta()
            val isActive = !multiMode && entry.path == activePath
            val checked = entry.path in selected
            ModelRow(
                title = shortName(entry),
                caps = meta.capabilities,
                desc = meta.description,
                showCheckbox = multiMode,
                checked = checked,
                isActive = isActive,
                onClick = {
                    if (multiMode) {
                        if (checked) {
                            selected = selected - entry.path
                        } else {
                            if (usedGb + ramGb(entry) > budget) onToast("Not enough RAM — remove a model first")
                            else selected = selected + entry.path
                        }
                    } else {
                        onPick(entry.path)
                    }
                },
            )
        }

        if (multiMode) {
            val over = usedGb > budget
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${selected.size} selected · ${"%.1f".format(usedGb)} / ${"%.1f".format(budget)} GB",
                    style = AppText.Meta,
                    color = if (over) AppColors.Danger else AppColors.TextSecondary,
                )
            }
            val enabled = selected.isNotEmpty() && !over
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .border(1.dp, AppColors.cream(0.16f), RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) {
                        val paths = models.map { it.path }.filter { it in selected }
                        if (paths.size == 1) onPick(paths.first()) else onCommitMulti(paths)
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selected.size > 1) "Use ${selected.size} models" else "Set model",
                    style = AppText.Small.copy(fontWeight = FontWeight.Bold),
                    color = if (enabled) AppColors.TextBody else AppColors.TextFaint,
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .heightIn(min = 1.dp, max = 1.dp)
                .background(AppColors.cream(0.08f)),
        )

        Row(
            Modifier.fillMaxWidth().clickable { onManage() }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Manage models", style = AppText.Small.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextSecondary)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = AppColors.TextSecondary, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = AppText.MetaTiny.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        color = AppColors.TextFaint,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelRow(
    title: String,
    caps: Set<ModelCapability>,
    desc: String,
    showCheckbox: Boolean,
    checked: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showCheckbox) {
            CheckSquare(checked, Modifier.padding(top = 2.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = AppText.Small.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caps.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    caps.forEach { cap ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(4.dp).background(AccentBrush, RoundedCornerShape(2.dp)))
                            Text(cap.label, style = AppText.MetaTiny.copy(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold), color = AppColors.TextMuted)
                        }
                    }
                }
            }
            if (desc.isNotBlank()) {
                Text(desc, style = AppText.Small.copy(fontSize = 11.sp), color = AppColors.TextSecondary)
            }
        }
        if (isActive) {
            Icon(Icons.Filled.Check, "Active", tint = AppColors.AccentA, modifier = Modifier.size(15.dp).padding(top = 2.dp))
        }
    }
}

@Composable
fun CheckSquare(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(16.dp)
            .then(
                if (checked) Modifier.background(AccentBrush, RoundedCornerShape(5.dp))
                else Modifier.border(1.5.dp, AppColors.cream(0.30f), RoundedCornerShape(5.dp))
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, tint = AppColors.Bg, modifier = Modifier.size(10.dp))
    }
}

/** Short model display name from the GGUF description or the file stem. */
private fun shortName(entry: ModelEntry): String {
    val desc = entry.info?.desc
    return if (!desc.isNullOrBlank()) desc else entry.name.removeSuffix(".gguf")
}
