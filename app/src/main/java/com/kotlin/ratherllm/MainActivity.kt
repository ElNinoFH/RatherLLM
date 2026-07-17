package com.kotlin.ratherllm

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val boundService = MutableStateFlow<InferenceService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            boundService.value = (binder as? InferenceService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            boundService.value = null
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* FGS runs regardless */ }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { boundService.value?.importModel(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        InferenceService.start(this)

        setContent {
            RatherLlmTheme {
                val service by boundService.collectAsState()
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val svc = service
                    if (svc == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Connecting to engine…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        var showModels by remember { mutableStateOf(false) }
                        LaunchedEffect(showModels) { if (showModels) svc.refreshModels() }
                        if (showModels) {
                            ModelsHost(
                                svc = svc,
                                onBack = { svc.dismissOpMessage(); showModels = false },
                                onImport = { openDocument.launch(arrayOf("*/*")) },
                                onLoaded = { showModels = false },
                            )
                        } else {
                            ChatScreen(svc, onOpenModels = { showModels = true })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(connection) }
        boundService.value = null
    }

    private fun requestNotificationPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun ModelsHost(
    svc: InferenceService,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onLoaded: () -> Unit,
) {
    val models by svc.models.collectAsState()
    val activePath by svc.activeModelPath.collectAsState()
    val opText by svc.opText.collectAsState()
    val opFraction by svc.opFraction.collectAsState()
    val opBusy by svc.opBusy.collectAsState()

    ModelsScreen(
        models = models,
        activeModelPath = activePath,
        isBusy = opBusy,
        busyText = opText,
        busyFraction = opFraction,
        ramRiskFor = { svc.repo.isRamRisky(it.sizeBytes) },
        onBack = onBack,
        onLoad = { entry -> svc.loadModel(entry.path); onLoaded() },
        onDelete = { entry -> svc.deleteModel(entry) },
        onImport = onImport,
        onDownload = { url, name -> svc.downloadModel(url, name) },
        onDownloadRecommended = { rec -> svc.downloadModel(rec.url, rec.fileName) },
    )
}

@Composable
private fun ChatScreen(svc: InferenceService, onOpenModels: () -> Unit) {
    val status by svc.status.collectAsState()
    val messages by svc.messages.collectAsState()
    val streamingText by svc.streamingText.collectAsState()
    val loadProgress by svc.loadProgress.collectAsState()
    val modelName by svc.modelName.collectAsState()
    val models by svc.models.collectAsState()
    val decodeTps by svc.lastDecodeTps.collectAsState()
    val conversations by svc.conversations.collectAsState()
    val currentConvId by svc.currentConversationId.collectAsState()
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationsDrawer(
                conversations = conversations,
                currentId = currentConvId,
                onNew = { svc.newConversation(); scope.launch { drawerState.close() } },
                onSelect = { id -> svc.switchConversation(id); scope.launch { drawerState.close() } },
                onDelete = { id -> svc.deleteConversation(id) },
            )
        },
    ) {
        ChatContent(
            status = status,
            errorText = svc.lastError,
            modelName = modelName,
            decodeTps = decodeTps,
            loadProgress = loadProgress,
            hasModels = models.isNotEmpty(),
            messages = messages,
            streamingText = streamingText,
            input = input,
            onInputChange = { input = it },
            onSend = { svc.submit(input); input = "" },
            onStop = { svc.cancelGeneration() },
            onOpenModels = onOpenModels,
            onOpenSettings = { showSettings = true },
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onNewChat = { svc.newConversation() },
        )
        if (showSettings) {
            SettingsSheet(
                current = svc.genParams,
                systemPrompt = svc.systemPrompt,
                onApply = { p, s -> svc.genParams = p; svc.systemPrompt = s },
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun ConversationsDrawer(
    conversations: List<Conversation>,
    currentId: String,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    ModalDrawerSheet {
        Text("Conversations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        TextButton(onClick = onNew, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  New chat")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(conversations, key = { it.id }) { c ->
                NavigationDrawerItem(
                    label = { Text(c.title, maxLines = 1) },
                    selected = c.id == currentId,
                    onClick = { onSelect(c.id) },
                    badge = {
                        IconButton(onClick = { onDelete(c.id) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    status: InferenceService.Status,
    errorText: String?,
    modelName: String?,
    decodeTps: Float?,
    loadProgress: Float,
    hasModels: Boolean,
    messages: List<ChatMessage>,
    streamingText: String,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
) {
    val isGenerating = status == InferenceService.Status.Generating
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, streamingText, isGenerating) {
        val count = messages.size + if (isGenerating && streamingText.isNotEmpty()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Conversations")
                    }
                },
                title = {
                    Column {
                        Text("ratherllm", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = statusLine(status, modelName, errorText, decodeTps),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(status),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Filled.Storage, contentDescription = "Models")
                    }
                },
            )
        },
        bottomBar = {
            if (status == InferenceService.Status.Ready || isGenerating) {
                InputBar(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = status == InferenceService.Status.Ready,
                    isGenerating = isGenerating,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                status == InferenceService.Status.Loading -> LoadingState(loadProgress)
                messages.isEmpty() && !isGenerating -> EmptyState(status, errorText, hasModels, onOpenModels)
                else -> MessageList(listState, messages, streamingText, isGenerating)
            }
        }
    }
}

@Composable
private fun LoadingState(progress: Float) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Loading model…", style = MaterialTheme.typography.titleMedium)
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

@Composable
private fun EmptyState(
    status: InferenceService.Status,
    errorText: String?,
    hasModels: Boolean,
    onOpenModels: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val headline = when {
            status == InferenceService.Status.Error -> errorText ?: "Something went wrong"
            status == InferenceService.Status.Ready -> "Ready. Say something to begin."
            hasModels -> "No model loaded yet"
            else -> "Welcome — add a model to start chatting"
        }
        Text(headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status != InferenceService.Status.Ready) {
            Button(onClick = onOpenModels, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (hasModels) "Choose a model" else "Add a model")
            }
        }
    }
}

@Composable
private fun MessageList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    messages: List<ChatMessage>,
    streamingText: String,
    isGenerating: Boolean,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        itemsIndexed(messages) { _, msg -> MessageBubble(msg.role, msg.text) }
        if (isGenerating) {
            item { MessageBubble(Role.Assistant, streamingText, showCaret = true) }
        }
    }
}

@Composable
private fun MessageBubble(role: Role, text: String, showCaret: Boolean = false) {
    val isUser = role == Role.User
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(color = bubbleColor, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 340.dp)) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!isUser && !showCaret) {
                    // Final assistant messages render Markdown (code blocks + copy, bold, lists).
                    MarkdownText(text, textColor)
                } else {
                    Text(
                        text = if (showCaret && text.isEmpty()) "▌" else if (showCaret) "$text▌" else text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "Ask anything…" else "Generating…") },
                enabled = enabled,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            if (isGenerating) {
                IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
            } else {
                IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

private fun statusLine(status: InferenceService.Status, modelName: String?, errorText: String?, decodeTps: Float?): String = when (status) {
    InferenceService.Status.Idle -> "idle"
    InferenceService.Status.Loading -> "loading model…"
    InferenceService.Status.Ready -> "ready · ${modelName ?: "model"}" +
        (decodeTps?.let { " · ${String.format(java.util.Locale.US, "%.1f", it)} tok/s" } ?: "")
    InferenceService.Status.Generating -> "generating…"
    InferenceService.Status.Error -> errorText ?: "error"
}

@Composable
private fun statusColor(status: InferenceService.Status): Color = when (status) {
    InferenceService.Status.Ready -> MaterialTheme.colorScheme.primary
    InferenceService.Status.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun sampleMessages(): List<ChatMessage> = listOf(
    ChatMessage(Role.User, "Sebutkan 3 warna bendera Indonesia"),
    ChatMessage(Role.Assistant, "Merah, Putih, dan kombinasi Merah-Putih."),
)

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Ready")
@Composable
private fun ChatReadyPreview() {
    MaterialTheme {
        ChatContent(
            status = InferenceService.Status.Ready, errorText = null, modelName = "gemma3 4B Q4_0", decodeTps = 8.2f,
            loadProgress = 1f, hasModels = true, messages = sampleMessages(), streamingText = "",
            input = "", onInputChange = {}, onSend = {}, onStop = {}, onOpenModels = {}, onOpenSettings = {},
            onOpenDrawer = {}, onNewChat = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Empty")
@Composable
private fun ChatEmptyPreview() {
    MaterialTheme {
        ChatContent(
            status = InferenceService.Status.Idle, errorText = null, modelName = null, decodeTps = null,
            loadProgress = 0f, hasModels = false, messages = emptyList(), streamingText = "",
            input = "", onInputChange = {}, onSend = {}, onStop = {}, onOpenModels = {}, onOpenSettings = {},
            onOpenDrawer = {}, onNewChat = {},
        )
    }
}
