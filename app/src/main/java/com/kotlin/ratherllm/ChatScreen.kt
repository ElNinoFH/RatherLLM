package com.kotlin.ratherllm

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import kotlinx.coroutines.launch

/**
 * The chat screen: header (with analytics/export overflow), greeting or message
 * list with per-reply actions, and the input bar with the in-chat model picker and
 * attachment menu. Business state comes from [svc]; navigation to other
 * destinations and cross-screen overlays are hoisted to the host via callbacks.
 */
@Composable
fun ChatScreen(
    svc: InferenceService,
    onOpenModels: () -> Unit,
    onOpenDrawer: () -> Unit,
    onExportCurrent: () -> Unit,
    onToast: (String) -> Unit,
) {
    val status by svc.status.collectAsState()
    val messages by svc.messages.collectAsState()
    val streamingText by svc.streamingText.collectAsState()
    val activePath by svc.activeModelPath.collectAsState()
    val models by svc.models.collectAsState()
    val metas by svc.modelMetas.collectAsState()
    val loadProgress by svc.loadProgress.collectAsState()
    val analyticsEnabled by svc.analyticsEnabled.collectAsState()
    val deviceStats by svc.deviceStats.collectAsState()
    val savedReplies by svc.savedReplies.collectAsState()
    val multiEnabled by svc.multiResponse.collectAsState()
    val multiSelected by svc.multiSelectedPaths.collectAsState()
    val multiRemaining by svc.multiRemaining.collectAsState()
    val streamingModel by svc.streamingModelName.collectAsState()
    val lastTps by svc.lastDecodeTps.collectAsState()

    val isGenerating = status == InferenceService.Status.Generating
    val isLoading = status == InferenceService.Status.Loading
    val savedIds = remember(savedReplies) { savedReplies.map { it.messageId }.toSet() }

    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val attachStore = remember { AttachmentStore(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun addFromUri(uri: android.net.Uri?, kind: AttachmentKind) {
        if (uri == null) return
        scope.launch {
            val att = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                attachStore.importFromUri(uri, kind)
            }
            if (att != null) attachments = attachments + att
            else onToast("Couldn't attach that file")
        }
    }

    // Camera writes full-res into a FileProvider target we prepared; keep the file
    // handle so we can turn it into an attachment once capture reports success.
    var pendingCamera by remember { mutableStateOf<java.io.File?>(null) }
    val takePicture = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        val f = pendingCamera
        pendingCamera = null
        if (ok && f != null && f.length() > 0) attachments = attachments + attachStore.attachmentForCameraOutput(f)
    }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> addFromUri(uri, AttachmentKind.Image) }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> addFromUri(uri, AttachmentKind.File) }

    fun launchCamera() {
        val (file, uri) = attachStore.newCameraTarget()
        pendingCamera = file
        runCatching { takePicture.launch(uri) }.onFailure { onToast("No camera available"); pendingCamera = null }
    }

    val activeShort = svc.modelShortLabel(activePath)
    val pillLabel = if (multiSelected.size > 1) "${multiSelected.size} models" else activeShort
    val modelLine = when {
        isLoading -> "switching model…"
        isGenerating -> "generating…"
        activePath != null -> "ready · $activeShort"
        else -> "idle — no model loaded"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .statusBarsPadding(),
    ) {
        ChatHeader(
            modelLine = modelLine,
            analyticsEnabled = analyticsEnabled,
            deviceStats = deviceStats,
            onOpenDrawer = onOpenDrawer,
            onNewChat = { svc.newConversation() },
            onToggleAnalytics = { svc.setAnalyticsEnabled(!analyticsEnabled) },
            onExport = onExportCurrent,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && !isGenerating) {
                Greeting(activePath = activePath, activeMeta = if (activePath != null) activeShort else "no model loaded", switching = isLoading)
            } else {
                MessageList(
                    messages = messages,
                    streamingText = streamingText,
                    isGenerating = isGenerating,
                    streamingModel = streamingModel ?: activeShort,
                    multiRemaining = multiRemaining,
                    liveTps = lastTps,
                    savedIds = savedIds,
                    onCopy = { text -> onToast("Copied to clipboard") },
                    onToggleSave = { msg ->
                        svc.toggleSaved(msg)
                        onToast(if (msg.id in savedIds) "Removed from saved" else "Reply saved")
                    },
                    onRegenerate = { svc.regenerateLast() },
                )
            }
        }

        InputBar(
            input = input,
            onInputChange = { input = it },
            enabled = status == InferenceService.Status.Ready || isGenerating,
            isGenerating = isGenerating,
            pillLabel = pillLabel,
            models = models,
            metas = metas,
            activePath = activePath,
            multiEnabled = multiEnabled,
            multiSelected = multiSelected,
            ramBudgetGb = { svc.ramBudgetGb() },
            attachments = attachments,
            onRemoveAttachment = { a -> attachments = attachments.filter { it !== a } },
            onTakePhoto = { launchCamera() },
            onPickImage = {
                pickImage.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            onPickFile = { runCatching { pickFile.launch(arrayOf("*/*")) }.onFailure { onToast("No file picker available") } },
            onPickModel = { path -> svc.loadModel(path) },
            onCommitMulti = { paths -> svc.setMultiSelected(paths) },
            onManageModels = onOpenModels,
            onSend = {
                val t = input.trim()
                if (t.isNotEmpty() || attachments.isNotEmpty()) {
                    svc.submit(t, attachments); input = ""; attachments = emptyList()
                }
            },
            onStop = { svc.cancelGeneration() },
            onToast = onToast,
            shortLabelFor = { svc.modelShortLabel(it) },
        )
    }
}

// ---------------------------------------------------------------- Header

@Composable
private fun ChatHeader(
    modelLine: String,
    analyticsEnabled: Boolean,
    deviceStats: DeviceStats?,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onToggleAnalytics: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .border(0.dp, Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.AutoMirrored.Filled.List, "Conversations", onOpenDrawer)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("ratherllm", style = AppText.Wordmark, color = AppColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AccentDiamond(6)
                Text(modelLine, style = AppText.Meta, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (analyticsEnabled && deviceStats != null) {
                Row(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatChip(AppColors.Teal, "CPU ${deviceStats.cpuPercent}%")
                    StatChip(AppColors.AccentA, "RAM ${deviceStats.ramPercent}%")
                    StatChip(AppColors.Orange, "${deviceStats.tempCelsius}°C")
                }
            }
        }
        RoundIconButton(Icons.Filled.Add, "New chat", onNewChat)
        Box {
            RoundIconButton(Icons.Filled.MoreHoriz, "More", { menuOpen = true }, iconSizeDp = 18)
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.width(218.dp).background(AppColors.SurfaceMenu),
            ) {
                MenuItem(
                    label = "Device analytics",
                    onClick = { onToggleAnalytics() },
                    trailing = { GradientToggle(checked = analyticsEnabled, onToggle = onToggleAnalytics) },
                )
                MenuItem(
                    label = "Export chat",
                    onClick = { menuOpen = false; onExport() },
                    leading = { Icon(Icons.Filled.Description, null, tint = AppColors.TextBody, modifier = Modifier.size(14.dp)) },
                )
            }
        }
    }
}

@Composable
private fun StatChip(dot: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(5.dp).background(dot, RoundedCornerShape(3.dp)))
        Text(text, style = AppText.MetaTiny, color = AppColors.TextSecondary)
    }
}

// ---------------------------------------------------------------- Greeting

@Composable
private fun Greeting(activePath: String?, activeMeta: String, switching: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        GradientText("Hello, there.", style = AppText.Greeting, brush = GreetingBrush)
        Spacer(Modifier.size(12.dp))
        Text(
            "Ask anything — it runs entirely on this phone. Nothing leaves your device.",
            style = AppText.Body,
            color = AppColors.TextSecondary,
            modifier = Modifier.widthIn(max = 290.dp),
        )
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AccentDiamond(8, blink = switching)
            if (switching) {
                ShimmerText("Switching model…")
            } else {
                Text(activeMeta, style = AppText.Meta, color = AppColors.TextFaint)
            }
        }
    }
}

/** Text with a horizontally-sweeping gradient shimmer (used for streaming labels). */
@Composable
fun ShimmerText(text: String, style: androidx.compose.ui.text.TextStyle = AppText.Meta) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "shift",
    )
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(AppColors.TextFaint, AppColors.AccentA, AppColors.AccentB, AppColors.TextFaint),
        start = androidx.compose.ui.geometry.Offset(shift * 300f - 300f, 0f),
        end = androidx.compose.ui.geometry.Offset(shift * 300f, 0f),
    )
    Text(text, style = style.copy(brush = brush, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
}

// ---------------------------------------------------------------- Message list

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    streamingText: String,
    isGenerating: Boolean,
    streamingModel: String,
    multiRemaining: Int,
    liveTps: Float?,
    savedIds: Set<String>,
    onCopy: (String) -> Unit,
    onToggleSave: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit,
) {
    val listState = rememberLazyListState()
    val lastAiId = remember(messages) { messages.lastOrNull { it.role == Role.Assistant }?.id }
    androidx.compose.runtime.LaunchedEffect(messages.size, streamingText, isGenerating) {
        val count = messages.size + if (isGenerating) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(messages, key = { it.id }) { msg ->
            if (msg.role == Role.User) {
                UserBubble(msg)
            } else {
                AiMessage(
                    msg = msg,
                    isLast = msg.id == lastAiId && !isGenerating,
                    saved = msg.id in savedIds,
                    onCopy = onCopy,
                    onToggleSave = onToggleSave,
                    onRegenerate = onRegenerate,
                )
            }
        }
        if (isGenerating) {
            item(key = "streaming") {
                StreamingMessage(streamingModel, streamingText, multiRemaining, liveTps)
            }
        }
    }
}

@Composable
private fun UserBubble(msg: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(AppColors.SurfaceBubble, RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                .border(1.dp, AppColors.cream(0.07f), RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (msg.attachments.isNotEmpty()) {
                MessageAttachments(msg.attachments)
            }
            if (msg.text.isNotBlank()) {
                Text(msg.text, style = AppText.Message, color = AppColors.TextPrimary)
            }
        }
    }
}

// ---------------------------------------------------------------- Attachments

/** The editable strip of attachment chips shown inside the input bar before send. */
@Composable
private fun AttachmentStrip(attachments: List<Attachment>, onRemove: (Attachment) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { a -> AttachmentChip(a) { onRemove(a) } }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    val isImage = AttachmentUtil.isImage(a)
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .background(AppColors.cream(0.05f), RoundedCornerShape(10.dp))
            .border(1.dp, AppColors.cream(0.12f), RoundedCornerShape(10.dp))
            .padding(start = if (isImage) 4.dp else 9.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isImage) {
            AttachmentThumb(a.path, 26.dp)
        } else {
            Icon(Icons.Filled.Description, null, tint = AppColors.TextMuted, modifier = Modifier.size(14.dp))
        }
        Text(
            a.name, style = AppText.Small.copy(fontSize = 11.sp), color = AppColors.TextBody,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
        )
        Box(
            Modifier.size(18.dp).clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Remove", tint = AppColors.TextMuted, modifier = Modifier.size(11.dp)) }
    }
}

/** Read-only attachment previews shown inside a sent message bubble. */
@Composable
private fun MessageAttachments(attachments: List<Attachment>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { a ->
            if (AttachmentUtil.isImage(a)) {
                AttachmentThumb(a.path, 150.dp, corner = 12.dp)
            } else {
                Row(
                    Modifier
                        .background(AppColors.cream(0.05f), RoundedCornerShape(10.dp))
                        .border(1.dp, AppColors.cream(0.10f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Filled.Description, null, tint = AppColors.TextMuted, modifier = Modifier.size(14.dp))
                    Text(a.name, style = AppText.Small.copy(fontSize = 11.5.sp), color = AppColors.TextBody,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                }
            }
        }
    }
}

/** A decoded, downsampled image thumbnail (falls back to an icon if decode fails). */
@Composable
private fun AttachmentThumb(path: String, size: androidx.compose.ui.unit.Dp, corner: androidx.compose.ui.unit.Dp = 7.dp) {
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AttachmentUtil.decodeThumbnail(path)
        }
    }
    Box(
        Modifier.size(size).background(AppColors.cream(0.06f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Attached image",
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(corner)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Icon(Icons.Filled.Image, null, tint = AppColors.TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AiMessage(
    msg: ChatMessage,
    isLast: Boolean,
    saved: Boolean,
    onCopy: (String) -> Unit,
    onToggleSave: (ChatMessage) -> Unit,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AccentDiamond(9)
            Text(
                (msg.modelName ?: "assistant").uppercase(),
                style = AppText.MetaTiny.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = AppColors.TextFaint,
            )
        }
        MarkdownText(msg.text, AppColors.TextBody)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            msg.tps?.let {
                Text(
                    "${"%.1f".format(java.util.Locale.US, it)} tok/s",
                    style = AppText.Meta, color = AppColors.TextFaint,
                    modifier = Modifier.padding(end = 5.dp),
                )
            }
            SmallActionButton(Icons.Filled.ContentCopy, "Copy") {
                clipboard.setText(AnnotatedString(msg.text)); onCopy(msg.text)
            }
            SmallActionButton(
                if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                "Save reply",
                tint = if (saved) AppColors.AccentB else AppColors.TextMuted,
            ) { onToggleSave(msg) }
            if (isLast) {
                SmallActionButton(Icons.Filled.Refresh, "Regenerate") { onRegenerate() }
            }
        }
    }
}

@Composable
private fun SmallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = AppColors.TextMuted,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(26.dp)
            .background(Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun StreamingMessage(model: String, text: String, multiRemaining: Int, liveTps: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AccentDiamond(9)
            ShimmerText("${model.uppercase()} · GENERATING", style = AppText.MetaTiny)
            if (multiRemaining > 0) {
                Text("$multiRemaining more to answer", style = AppText.MetaTiny, color = AppColors.TextFaint)
            }
        }
        // Plain text while streaming (markdown re-parse per token is wasteful on-device).
        Text(
            text = if (text.isEmpty()) "▌" else "$text▌",
            style = AppText.Message,
            color = AppColors.TextBody,
        )
        liveTps?.let { Text("${"%.1f".format(java.util.Locale.US, it)} tok/s", style = AppText.Meta, color = AppColors.TextFaint) }
    }
}

// ---------------------------------------------------------------- Input bar

@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    pillLabel: String,
    models: List<ModelEntry>,
    metas: Map<String, ModelMeta>,
    activePath: String?,
    multiEnabled: Boolean,
    multiSelected: List<String>,
    ramBudgetGb: () -> Double,
    attachments: List<Attachment>,
    onRemoveAttachment: (Attachment) -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onPickModel: (String) -> Unit,
    onCommitMulti: (List<String>) -> Unit,
    onManageModels: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToast: (String) -> Unit,
    shortLabelFor: (String?) -> String,
) {
    var modelMenu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val canSend = input.isNotBlank() || attachments.isNotEmpty()

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 10.dp, bottom = 8.dp)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(AppColors.Surface, RoundedCornerShape(24.dp))
                .border(1.dp, AppColors.cream(0.10f), RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (attachments.isNotEmpty()) {
                AttachmentStrip(attachments, onRemoveAttachment)
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 22.dp, max = 96.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                textStyle = AppText.Message.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppColors.AccentA),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            if (isGenerating) "Generating…" else "Ask anything…",
                            style = AppText.Message, color = AppColors.TextFaint,
                        )
                    }
                    inner()
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                // Attach
                Box {
                    OutlinedRoundButton(Icons.Filled.Add, "Attach") { attachMenu = true }
                    DropdownMenu(
                        expanded = attachMenu,
                        onDismissRequest = { attachMenu = false },
                        modifier = Modifier.width(205.dp).background(AppColors.SurfaceMenu),
                    ) {
                        MenuItem("Take photo", { attachMenu = false; onTakePhoto() },
                            leading = { Icon(Icons.Filled.PhotoCamera, null, tint = AppColors.TextBody, modifier = Modifier.size(15.dp)) })
                        MenuItem("Attach image", { attachMenu = false; onPickImage() },
                            leading = { Icon(Icons.Filled.Image, null, tint = AppColors.TextBody, modifier = Modifier.size(15.dp)) })
                        MenuItem("Attach file", { attachMenu = false; onPickFile() },
                            leading = { Icon(Icons.Filled.Description, null, tint = AppColors.TextBody, modifier = Modifier.size(15.dp)) })
                    }
                }
                // Model pill
                Box {
                    ModelPill(pillLabel) { modelMenu = true }
                    ModelPickerMenu(
                        expanded = modelMenu,
                        onDismiss = { modelMenu = false },
                        models = models,
                        metas = metas,
                        activePath = activePath,
                        multiEnabled = multiEnabled,
                        multiSelected = multiSelected,
                        ramBudgetGb = ramBudgetGb,
                        onPick = { path -> modelMenu = false; onPickModel(path) },
                        onCommitMulti = { paths -> modelMenu = false; onCommitMulti(paths) },
                        onManage = { modelMenu = false; onManageModels() },
                        onToast = onToast,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isGenerating) {
                    SendStopButton(stop = true, enabled = true, onClick = onStop)
                } else {
                    SendStopButton(stop = false, enabled = canSend, onClick = onSend)
                }
            }
        }
    }
}

@Composable
private fun OutlinedRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .background(Color.Transparent, RoundedCornerShape(16.dp))
            .border(1.dp, AppColors.cream(0.14f), RoundedCornerShape(16.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, cd, tint = AppColors.IconTint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ModelPill(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .background(AppColors.cream(0.05f), RoundedCornerShape(16.dp))
            .border(1.dp, AppColors.cream(0.09f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .widthIn(max = 160.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AccentDiamond(8)
        Text(label, style = AppText.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = AppColors.TextBody, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.KeyboardArrowUp, null, tint = AppColors.TextBody, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun SendStopButton(stop: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .background(AccentBrush, RoundedCornerShape(17.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (stop) {
            Box(Modifier.size(11.dp).background(AppColors.Bg, RoundedCornerShape(3.dp)))
        } else {
            Icon(Icons.Filled.ArrowUpward, "Send", tint = AppColors.Bg, modifier = Modifier.size(16.dp))
        }
    }
}
