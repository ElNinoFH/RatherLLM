package com.kotlin.ratherllm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Human size formatter (e.g. 1.9 GB). Shared with the service's progress text. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    val loc = Locale.US
    return if (i >= 3) String.format(loc, "%.1f %s", v, units[i]) else String.format(loc, "%.0f %s", v, units[i])
}

fun formatParams(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.US, "%.0fM", n / 1e6)
    n <= 0 -> ""
    else -> n.toString()
}

/**
 * The Models screen: import/download entry points, a busy-progress banner, and a
 * card per on-device model (metadata, RAM estimate, load, and an overflow menu to
 * edit its configuration or delete it). Delete/edit-config dialogs are hoisted.
 */
@Composable
fun ModelsScreen(
    svc: InferenceService,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onLoaded: () -> Unit,
    onRequestDelete: (ModelEntry) -> Unit,
    onRequestEditConfig: (ModelEntry) -> Unit,
    onToast: (String) -> Unit,
) {
    val models by svc.models.collectAsState()
    val metas by svc.modelMetas.collectAsState()
    val activePath by svc.activeModelPath.collectAsState()
    val opBusy by svc.opBusy.collectAsState()
    val opText by svc.opText.collectAsState()
    val opFraction by svc.opFraction.collectAsState()

    var urlDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .statusBarsPadding(),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Text("Models", style = AppText.ScreenTitle, color = AppColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                "${models.size} " + if (models.size == 1) "model" else "models",
                style = AppText.Meta, color = AppColors.TextFaint,
                modifier = Modifier.padding(end = 10.dp),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            if (opText != null) {
                item("busy") { BusyBanner(opText!!, opBusy, opFraction) }
            }
            item("import") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedActionButton("Import .gguf", enabled = !opBusy, onClick = onImport)
                    Box(
                        Modifier.fillMaxWidth().clickable(enabled = !opBusy) { urlDialog = true }.padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Download from URL", style = AppText.Small, color = AppColors.TextSecondary)
                    }
                }
            }
            if (models.isEmpty() && !opBusy) {
                item("empty") { EmptyModels(onDownload = { rec -> svc.downloadModel(rec.url, rec.fileName) }) }
            }
            items(models, key = { it.path }) { entry ->
                ModelCard(
                    entry = entry,
                    meta = metas[entry.name] ?: ModelMeta(),
                    isActive = entry.path == activePath,
                    ramRisky = svc.repo.isRamRisky(entry.sizeBytes),
                    enabled = !opBusy,
                    onLoad = { svc.loadModel(entry.path); onLoaded() },
                    onEdit = { onRequestEditConfig(entry) },
                    onDelete = { onRequestDelete(entry) },
                )
            }
        }
    }

    if (urlDialog) {
        UrlDownloadDialog(
            onDismiss = { urlDialog = false },
            onConfirm = { url, name -> urlDialog = false; svc.downloadModel(url, name) },
        )
    }
}

@Composable
private fun BusyBanner(text: String, busy: Boolean, fraction: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.AccentA.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .border(1.dp, AppColors.AccentA.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = AppText.Meta.copy(fontSize = 12.5.sp), color = if (busy) AppColors.TextBody else AppColors.Danger)
        if (busy) {
            Box(
                Modifier.fillMaxWidth().height(4.dp).background(AppColors.cream(0.12f), RoundedCornerShape(2.dp)),
            ) {
                val f = if (fraction >= 0f) fraction.coerceIn(0f, 1f) else 0.3f
                Box(Modifier.fillMaxWidth(f).height(4.dp).background(AccentBrush, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun OutlinedActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.Transparent, RoundedCornerShape(14.dp))
            .border(1.dp, AppColors.cream(0.16f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AppText.Label, color = AppColors.TextBody)
    }
}

@Composable
private fun EmptyModels(onDownload: (RecommendedModel) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("No models yet", style = AppText.Label, color = AppColors.TextPrimary)
        Text(
            "Import a .gguf from storage, or grab a small starter model:",
            style = AppText.Small, color = AppColors.TextSecondary,
        )
        ModelDownloader.RECOMMENDED.forEach { rec ->
            OutlinedActionButton("${rec.label}  ·  ${rec.approxSize}", enabled = true) { onDownload(rec) }
        }
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    meta: ModelMeta,
    isActive: Boolean,
    ramRisky: Boolean,
    enabled: Boolean,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val info = entry.info
    val metaLine = buildString {
        if (info != null) {
            append(info.arch.ifBlank { "?" }); append(" · "); append(info.quant.ifBlank { "?" })
            if (info.nParams > 0) { append(" · "); append(formatParams(info.nParams)) }
            append(" · ")
        }
        append(formatBytes(entry.sizeBytes))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, AppColors.cream(0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(shortNameOf(entry), style = AppText.Small.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (isActive) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(AccentBrush, RoundedCornerShape(3.dp)))
                    GradientText("ACTIVE", style = AppText.MetaTiny.copy(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp))
                }
            }
        }
        Text(metaLine, style = AppText.Meta, color = AppColors.TextSecondary)
        Text(
            "≈ ${formatBytes(entry.estimatedRamBytes)} RAM",
            style = AppText.Meta.copy(fontSize = 10.5.sp),
            color = if (ramRisky) AppColors.Danger else AppColors.TextSecondary,
        )
        if (ramRisky) {
            Text("May exceed safe RAM — could be slow or fail to load.", style = AppText.Small.copy(fontSize = 11.sp), color = AppColors.Danger)
        }
        if (meta.description.isNotBlank()) {
            Text(meta.description, style = AppText.Small.copy(fontSize = 11.sp), color = AppColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .width(76.dp)
                    .background(if (isActive) Color.Transparent else AppColors.cream(0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, AppColors.cream(0.16f), RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled && !isActive) { onLoad() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (isActive) "Loaded" else "Load",
                    style = AppText.Small.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isActive) AppColors.TextFaint else AppColors.TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            Box {
                Box(
                    Modifier
                        .size(34.dp)
                        .border(1.dp, AppColors.cream(0.10f), RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.MoreHoriz, "More", tint = AppColors.IconTint, modifier = Modifier.size(16.dp)) }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.width(180.dp).background(AppColors.SurfaceMenu),
                ) {
                    MenuItem("Edit configuration", { menuOpen = false; onEdit() },
                        leading = { Icon(Icons.Filled.Edit, null, tint = AppColors.TextBody, modifier = Modifier.size(13.dp)) })
                    MenuItem("Delete", { menuOpen = false; onDelete() }, color = AppColors.Danger,
                        leading = { Icon(Icons.Filled.DeleteOutline, null, tint = AppColors.Danger, modifier = Modifier.size(13.dp)) })
                }
            }
        }
    }
}

private fun shortNameOf(entry: ModelEntry): String {
    val desc = entry.info?.desc
    return if (!desc.isNullOrBlank()) desc else entry.name.removeSuffix(".gguf")
}
