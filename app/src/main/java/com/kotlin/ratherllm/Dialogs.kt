package com.kotlin.ratherllm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** A centered modal card (title, body, action row) matching the design's dialogs. */
@Composable
fun CenterDialog(
    onDismiss: () -> Unit,
    widthDp: Int = 300,
    scrollable: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = widthDp.dp)
                .fillMaxWidth()
                .background(AppColors.SurfaceDialog, RoundedCornerShape(20.dp))
                .border(1.dp, AppColors.cream(0.10f), RoundedCornerShape(20.dp))
                .then(if (scrollable) Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()) else Modifier)
                .padding(18.dp),
            content = content,
        )
    }
}

@Composable
fun DialogTitle(text: String) = Text(text, style = AppText.Small.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = AppColors.TextPrimary)

@Composable
fun DialogBody(text: String) = Text(text, style = AppText.Small.copy(fontSize = 12.5.sp, lineHeight = 19.sp), color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))

@Composable
fun DialogActions(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun TextAction(label: String, color: Color = AppColors.TextSecondary, bold: Boolean = false, gradient: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (gradient) {
            GradientText(label, style = AppText.Small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold))
        } else {
            Text(label, style = AppText.Small.copy(fontSize = 12.5.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold), color = color)
        }
    }
}

// ---------------------------------------------------------------- Confirm dialogs

@Composable
fun DeleteModelDialog(entry: ModelEntry, isActive: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    CenterDialog(onDismiss, widthDp = 296) {
        DialogTitle("Delete model?")
        DialogBody(
            if (isActive) "\"${entry.name}\" is currently loaded. Delete it anyway? The engine will unload it."
            else "Delete \"${entry.name}\"? This can't be undone."
        )
        DialogActions {
            TextAction("Cancel", onClick = onDismiss)
            TextAction("Delete", color = AppColors.Danger, bold = true, onClick = onConfirm)
        }
    }
}

@Composable
fun DeleteConversationDialog(conv: Conversation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    CenterDialog(onDismiss, widthDp = 290) {
        DialogTitle("Delete chat?")
        DialogBody("Delete \"${conv.title}\"? This can't be undone.")
        DialogActions {
            TextAction("Cancel", onClick = onDismiss)
            TextAction("Delete", color = AppColors.Danger, bold = true, onClick = onConfirm)
        }
    }
}

@Composable
fun ExperimentalConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    CenterDialog(onDismiss, widthDp = 300) {
        DialogTitle(title)
        DialogBody(body)
        DialogActions {
            TextAction("Cancel", onClick = onDismiss)
            TextAction("Enable", gradient = true, onClick = onConfirm)
        }
    }
}

// ---------------------------------------------------------------- Export dialog

@Composable
fun ExportDialog(title: String, onExport: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    CenterDialog(onDismiss, widthDp = 300) {
        DialogTitle("Export conversation")
        DialogBody("Save \"$title\" as a file.")
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ExportFormat.entries.forEach { fmt ->
                Row(
                    Modifier.fillMaxWidth().clickable { onExport(fmt) }.padding(horizontal = 9.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(30.dp).background(AppColors.cream(0.06f), RoundedCornerShape(9.dp))
                            .border(1.dp, AppColors.cream(0.10f), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(fmt.ext, style = AppText.MetaTiny.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = AppColors.TextBody) }
                    Text(fmt.fileName, style = AppText.Small.copy(fontSize = 13.sp), color = AppColors.TextBody, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Download, null, tint = AppColors.TextMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
        DialogActions { TextAction("Close", onClick = onDismiss) }
    }
}

// ---------------------------------------------------------------- URL download dialog

@Composable
fun UrlDownloadDialog(onDismiss: () -> Unit, onConfirm: (url: String, name: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    CenterDialog(onDismiss, widthDp = 320) {
        DialogTitle("Download from URL")
        DialogBody("Paste a direct .gguf link (e.g. a Hugging Face resolve URL).")
        DialogField(
            value = url, onChange = { url = it }, placeholder = "https://…/model.gguf",
            keyboardType = KeyboardType.Uri, modifier = Modifier.padding(top = 10.dp),
        )
        DialogActions {
            TextAction("Cancel", onClick = onDismiss)
            val ok = url.trim().startsWith("http")
            TextAction("Download", gradient = ok, color = AppColors.TextFaint) {
                if (ok) onConfirm(url.trim(), url.trim().substringAfterLast('/').ifBlank { "model.gguf" })
            }
        }
    }
}

// ---------------------------------------------------------------- Edit / configure model

@Composable
fun EditConfigDialog(
    entry: ModelEntry,
    meta: ModelMeta,
    onImportMmproj: (Uri, (String?) -> Unit) -> Unit,
    onSave: (ModelMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    CenterDialog(onDismiss, widthDp = 320, scrollable = true) {
        DialogTitle("Edit model configuration")
        Text(entry.name, style = AppText.Meta.copy(fontSize = 11.5.sp), color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        ModelConfigForm(
            entry = entry, initial = meta, multimodalHint = false,
            onImportMmproj = onImportMmproj, saveLabel = "Save changes",
            onSave = onSave, onCancel = onDismiss,
        )
    }
}

/**
 * Config popup shown immediately after a model file is picked: the import progress
 * bar lives inside it, and once the copy finishes it becomes the capability form
 * where the user confirms multimodal support (checkbox) and pairs an mmproj file.
 */
@Composable
fun ImportConfigDialog(
    svc: InferenceService,
    onImportMmproj: (Uri, (String?) -> Unit) -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val busy by svc.opBusy.collectAsState()
    val opText by svc.opText.collectAsState()
    val fraction by svc.opFraction.collectAsState()
    val imported by svc.lastImported.collectAsState()

    CenterDialog(onDismiss, widthDp = 320, scrollable = true) {
        DialogTitle("Add model")
        val entry = imported
        if (entry == null) {
            val failed = !busy && opText != null
            Text(
                opText ?: "Importing model…",
                style = AppText.Small.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                color = if (failed) AppColors.Danger else AppColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (busy) ProgressLine(fraction, Modifier.padding(top = 12.dp))
            if (!busy) DialogActions { TextAction("Close", onClick = onDismiss) }
        } else {
            Text(entry.name, style = AppText.Meta.copy(fontSize = 11.5.sp), color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val hint = remember(entry.name) { svc.looksMultimodal(entry) }
            val initial = remember(entry.name) {
                ModelMeta(
                    capabilities = if (hint) setOf(ModelCapability.Text, ModelCapability.Image)
                                   else setOf(ModelCapability.Text),
                )
            }
            ModelConfigForm(
                entry = entry, initial = initial, multimodalHint = hint,
                onImportMmproj = onImportMmproj, saveLabel = "Save & finish",
                onSave = { meta ->
                    svc.updateModelMeta(entry.name, meta)
                    svc.clearLastImported(); onToast("Model added"); onDismiss()
                },
                onCancel = { svc.clearLastImported(); onDismiss() },
            )
        }
    }
}

/** Shared description / capabilities / mmproj form used by add-model and edit-config. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelConfigForm(
    entry: ModelEntry,
    initial: ModelMeta,
    multimodalHint: Boolean,
    onImportMmproj: (Uri, (String?) -> Unit) -> Unit,
    saveLabel: String,
    onSave: (ModelMeta) -> Unit,
    onCancel: () -> Unit,
) {
    var desc by remember(entry.name) { mutableStateOf(initial.description) }
    var caps by remember(entry.name) { mutableStateOf(initial.capabilities.ifEmpty { setOf(ModelCapability.Text) }) }
    var mmproj by remember(entry.name) { mutableStateOf(initial.mmproj) }
    val canMmproj = caps.any { it != ModelCapability.Text }
    val canConfirm = caps.isNotEmpty()

    val mmprojPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImportMmproj(uri) { name -> if (name != null) mmproj = name } }

    if (multimodalHint) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp)
                .background(AppColors.AccentA.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                .border(1.dp, AppColors.AccentA.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Text(
                "This looks like a multimodal model. Confirm Image below and pair its mmproj projector to enable vision.",
                style = AppText.Small.copy(fontSize = 11.5.sp, lineHeight = 17.sp), color = AppColors.TextBody,
            )
        }
    }

    SectionHeading("Vision / multimodal projector")
    if (mmproj != null) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .background(AppColors.cream(0.05f), RoundedCornerShape(11.dp))
                .border(1.dp, AppColors.cream(0.10f), RoundedCornerShape(11.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(mmproj!!, style = AppText.Meta.copy(fontSize = 11.sp), color = AppColors.TextBody,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.size(20.dp).clickable { mmproj = null }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Remove", tint = AppColors.TextMuted, modifier = Modifier.size(11.dp))
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .border(1.dp, AppColors.cream(if (canMmproj) 0.22f else 0.12f), RoundedCornerShape(11.dp))
                .clickable(enabled = canMmproj) { runCatching { mmprojPicker.launch(arrayOf("*/*")) } }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Add, null, tint = AppColors.TextBody.copy(alpha = if (canMmproj) 1f else 0.4f), modifier = Modifier.size(13.dp))
            Text(if (canMmproj) "Attach mmproj (.gguf)" else "Enable Image to attach mmproj",
                style = AppText.Small.copy(fontWeight = FontWeight.SemiBold),
                color = AppColors.TextBody.copy(alpha = if (canMmproj) 1f else 0.4f))
        }
    }

    SectionHeading("Description shown in model picker")
    DialogField(
        value = desc, onChange = { desc = it },
        placeholder = "e.g. Fast general assistant, good at coding.",
        minLines = 2, modifier = Modifier.padding(top = 8.dp),
    )

    SectionHeading("Model capabilities")
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ModelCapability.entries.forEach { cap ->
            val on = cap in caps
            Row(
                Modifier.clickable {
                    caps = if (on) caps - cap else caps + cap
                }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CheckSquare(on)
                Text(cap.label, style = AppText.Small.copy(fontSize = 12.5.sp), color = AppColors.TextBody)
            }
        }
    }

    DialogActions {
        TextAction("Cancel", onClick = onCancel)
        TextAction(saveLabel, gradient = canConfirm, color = AppColors.TextFaint) {
            if (canConfirm) onSave(ModelMeta(desc.trim(), caps, if (canMmproj) mmproj else null))
        }
    }
}

/** A thin indeterminate/determinate progress bar matching the app's busy banner. */
@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(4.dp).background(AppColors.cream(0.12f), RoundedCornerShape(2.dp)),
    ) {
        val f = if (fraction >= 0f) fraction.coerceIn(0f, 1f) else 0.3f
        Box(Modifier.fillMaxWidth(f).height(4.dp).background(AccentBrush, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun SectionHeading(text: String) =
    Text(text, style = AppText.Small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = AppColors.TextPrimary, modifier = Modifier.padding(top = 16.dp))

/** A styled single/multi-line text field used inside dialogs and sheets. */
@Composable
fun DialogField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(AppColors.FieldBg, RoundedCornerShape(11.dp))
            .border(1.dp, AppColors.cream(0.10f), RoundedCornerShape(11.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = (minLines * 18).dp),
            textStyle = AppText.Small.copy(fontSize = 13.sp, color = AppColors.TextPrimary),
            cursorBrush = SolidColor(AppColors.AccentA),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = AppText.Small.copy(fontSize = 13.sp), color = AppColors.TextFaint)
                inner()
            },
        )
    }
}
