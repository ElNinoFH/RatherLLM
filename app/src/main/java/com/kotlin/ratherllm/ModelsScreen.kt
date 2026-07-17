package com.kotlin.ratherllm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Human size formatter (e.g. 1.9 GB). */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    val loc = java.util.Locale.US
    return if (i >= 3) String.format(loc, "%.1f %s", v, units[i]) else String.format(loc, "%.0f %s", v, units[i])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    models: List<ModelEntry>,
    activeModelPath: String?,
    isBusy: Boolean,
    busyText: String?,
    busyFraction: Float,
    ramRiskFor: (ModelEntry) -> Boolean,
    onBack: () -> Unit,
    onLoad: (ModelEntry) -> Unit,
    onDelete: (ModelEntry) -> Unit,
    onImport: () -> Unit,
    onDownload: (url: String, name: String) -> Unit,
    onDownloadRecommended: (RecommendedModel) -> Unit,
) {
    var showUrlDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ModelEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            // Show progress while busy, and keep an error message visible after the
            // op ends (it stays until the user goes back or starts another op).
            if (busyText != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(
                        busyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isBusy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    if (isBusy) {
                        if (busyFraction >= 0f) {
                            LinearProgressIndicator(
                                progress = { busyFraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onImport, enabled = !isBusy, modifier = Modifier.weight(1f)) { Text("Import .gguf") }
                OutlinedButton(onClick = { showUrlDialog = true }, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    Text("Download URL")
                }
            }

            if (models.isEmpty() && !isBusy) {
                EmptyModels(onImport, onDownloadRecommended)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(models, key = { it.path }) { entry ->
                        ModelCard(
                            entry = entry,
                            isActive = entry.path == activeModelPath,
                            ramRisky = ramRiskFor(entry),
                            enabled = !isBusy,
                            onLoad = { onLoad(entry) },
                            onDelete = { pendingDelete = entry },
                        )
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        UrlDownloadDialog(
            onDismiss = { showUrlDialog = false },
            onConfirm = { url, name -> showUrlDialog = false; onDownload(url, name) },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete model?") },
            text = {
                Text(
                    if (entry.path == activeModelPath)
                        "\"${entry.name}\" is currently loaded. Delete it anyway? The engine will unload it."
                    else "Delete \"${entry.name}\"? This can't be undone."
                )
            },
            confirmButton = { TextButton(onClick = { pendingDelete = null; onDelete(entry) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyModels(onImport: () -> Unit, onDownloadRecommended: (RecommendedModel) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("No models yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Import a .gguf file from your storage, or grab a small starter model:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelDownloader.RECOMMENDED.forEach { rec ->
            OutlinedButton(onClick = { onDownloadRecommended(rec) }, modifier = Modifier.fillMaxWidth()) {
                Text("${rec.label}  ·  ${rec.approxSize}")
            }
        }
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    isActive: Boolean,
    ramRisky: Boolean,
    enabled: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (isActive) {
                    Text(
                        "● active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val info = entry.info
            val meta = buildString {
                if (info != null) {
                    append(info.arch.ifBlank { "?" })
                    append(" · "); append(info.quant.ifBlank { "?" })
                    if (info.nParams > 0) { append(" · "); append(formatParams(info.nParams)) }
                }
                append(if (isEmpty()) "" else " · ")
                append(formatBytes(entry.sizeBytes))
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "≈ ${formatBytes(entry.estimatedRamBytes)} RAM",
                style = MaterialTheme.typography.labelSmall,
                color = if (ramRisky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ramRisky) {
                Text(
                    "May exceed safe RAM — could be slow or fail to load.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onLoad, enabled = enabled && !isActive, modifier = Modifier.weight(1f)) {
                    Text(if (isActive) "Loaded" else "Load")
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlDownloadDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download from URL") },
        text = {
            Column {
                Text("Paste a direct .gguf link (e.g. a Hugging Face resolve URL).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("https://…/model.gguf") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.trim().startsWith("http"),
                onClick = { onConfirm(url.trim(), url.trim().substringAfterLast('/').ifBlank { "model.gguf" }) },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatParams(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.0fM", n / 1e6)
    else -> n.toString()
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun ModelsScreenPreview() {
    MaterialTheme {
        ModelsScreen(
            models = listOf(
                ModelEntry(java.io.File("/x/gemma3-4b-q4.gguf"), GgufModelInfo("gemma3", "Q4_0", 3_880_000_000L, 3_155_051_328L, 131072, true, "gemma3 4B Q4_0"), 4_100_000_000L),
            ),
            activeModelPath = "/x/gemma3-4b-q4.gguf",
            isBusy = false, busyText = null, busyFraction = -1f,
            ramRiskFor = { false }, onBack = {}, onLoad = {}, onDelete = {}, onImport = {}, onDownload = { _, _ -> }, onDownloadRecommended = {},
        )
    }
}
