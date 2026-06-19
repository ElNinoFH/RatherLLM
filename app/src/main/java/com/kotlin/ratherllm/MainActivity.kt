package com.kotlin.ratherllm

import androidx.compose.ui.tooling.preview.Preview
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** UI-side chat model. */
private enum class Author { User, Assistant }

private data class ChatMessage(val author: Author, val text: String)

class MainActivity : ComponentActivity() {

    // Bound service exposed to Compose as a StateFlow so the UI recomposes
    // when the connection is established / torn down.
    private val boundService = MutableStateFlow<InferenceService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            boundService.value = (binder as? InferenceService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService.value = null
        }
    }

    // POST_NOTIFICATIONS runtime permission (API 33+). Granting it makes the
    // foreground-service notification — and thus the priority-bucket promotion
    // that keeps HyperOS from reclaiming us — reliable.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* FGS runs regardless */ }

    // Demo model location; push a GGUF here (e.g. via `adb push … /files/model.gguf`).
    private val modelPath: String by lazy { File(filesDir, "model.gguf").absolutePath }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        // Start the foreground service, which brings up the native engine.
        InferenceService.start(this, modelPath, useNeuropilot = true)

        setContent {
            MaterialTheme {
                val service by boundService.collectAsState()
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChatScreen(service = service)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, InferenceService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(connection) }
        boundService.value = null
    }

    private fun requestNotificationPermissionIfNeeded() {
        // minSdk is 34, so POST_NOTIFICATIONS always applies.
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun ChatScreen(service: InferenceService?) {
    val scope: CoroutineScope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var streamingText by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    var status by remember { mutableStateOf(InferenceService.Status.Idle) }
    LaunchedEffect(service) {
        status = InferenceService.Status.Idle
        service?.status?.collect { status = it }
    }

    fun send() {
        val prompt = input.trim()
        val svc = service
        if (prompt.isEmpty() || isStreaming || svc == null || status != InferenceService.Status.Ready) return

        input = ""
        messages.add(ChatMessage(Author.User, prompt))
        streamingText = ""
        isStreaming = true

        scope.launch {
            val sb = StringBuilder()
            try {
                svc.streamTokens(prompt).collect { chunk ->
                    if (chunk.text.isNotEmpty()) {
                        sb.append(chunk.text)
                        streamingText = sb.toString()
                    }
                    if (chunk.final) {
                        messages.add(ChatMessage(Author.Assistant, sb.toString()))
                        streamingText = ""
                        isStreaming = false
                    }
                }
            } catch (e: Exception) {
                messages.add(ChatMessage(Author.Assistant, "⚠️ stream error: ${e.message}"))
            } finally {
                if (isStreaming) {
                    if (sb.isNotEmpty()) messages.add(ChatMessage(Author.Assistant, sb.toString()))
                    streamingText = ""
                    isStreaming = false
                }
            }
        }
    }

    ChatContent(
        status = status,
        bound = service != null,
        messages = messages,
        streamingText = streamingText,
        isStreaming = isStreaming,
        input = input,
        onInputChange = { input = it },
        onSend = ::send,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    status: InferenceService.Status,
    bound: Boolean,
    messages: List<ChatMessage>,
    streamingText: String,
    isStreaming: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, streamingText, isStreaming) {
        val count = messages.size + if (isStreaming) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                androidx.compose.foundation.layout.Column {
                    Text("ratherllm", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = statusLabel(status, bound),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(status),
                    )
                }
            })
        },
        bottomBar = {
            InputBar(
                value = input,
                onValueChange = onInputChange,
                enabled = status == InferenceService.Status.Ready && !isStreaming,
                onSend = onSend,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            itemsIndexed(messages) { _, msg ->
                MessageBubble(author = msg.author, text = msg.text)
            }
            if (isStreaming) {
                item {
                    MessageBubble(author = Author.Assistant, text = streamingText, showCaret = true)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(author: Author, text: String, showCaret: Boolean = false) {
    val isUser = author == Author.User
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = if (showCaret && text.isEmpty()) "▌" else if (showCaret) "$text▌" else text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
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
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "Ask anything…" else "Waiting for engine…") },
                enabled = enabled,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private fun statusLabel(status: InferenceService.Status, bound: Boolean): String = when {
    !bound -> "connecting to service…"
    status == InferenceService.Status.Idle -> "idle"
    status == InferenceService.Status.Starting -> "loading model & locking weights…"
    status == InferenceService.Status.Ready -> "ready · weights resident"
    else -> "error"
}

@Composable
private fun statusColor(status: InferenceService.Status): Color = when (status) {
    InferenceService.Status.Ready -> MaterialTheme.colorScheme.primary
    InferenceService.Status.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun sampleMessages(): List<ChatMessage> = listOf(
    ChatMessage(Author.User, "What SoC am I running on?"),
    ChatMessage(
        Author.Assistant,
        "You're on the MediaTek Dimensity 9500s (MT6991Z) — an all-big-core ARMv9.2-A layout: " +
            "1× Cortex-X925 @ 3.73 GHz, 3× Cortex-X4, and 4× Cortex-A720.",
    ),
    ChatMessage(Author.User, "Are the model weights locked in RAM?"),
    ChatMessage(
        Author.Assistant,
        "Yes — the engine mmaps the GGUF file and mlock()s the arena, with MADV_WILLNEED " +
            "pre-faulting, so HyperOS's LMK can't reclaim the hot pages mid-decode.",
    ),
)

@Preview(showBackground = true, name = "Chat · Ready")
@Composable
private fun ChatContentReadyPreview() {
    MaterialTheme {
        ChatContent(
            status = InferenceService.Status.Ready,
            bound = true,
            messages = sampleMessages(),
            streamingText = "",
            isStreaming = false,
            input = "",
            onInputChange = {},
            onSend = {},
        )
    }
}

@Preview(showBackground = true, name = "Chat · Streaming")
@Composable
private fun ChatContentStreamingPreview() {
    MaterialTheme {
        ChatContent(
            status = InferenceService.Status.Ready,
            bound = true,
            messages = sampleMessages(),
            streamingText = "The X925 prime core runs the autoregressive decode while the X4 cores handle prefill",
            isStreaming = true,
            input = "Explain the token pipeline",
            onInputChange = {},
            onSend = {},
        )
    }
}

@Preview(showBackground = true, name = "Chat · Loading")
@Composable
private fun ChatContentLoadingPreview() {
    MaterialTheme {
        ChatContent(
            status = InferenceService.Status.Starting,
            bound = true,
            messages = emptyList(),
            streamingText = "",
            isStreaming = false,
            input = "",
            onInputChange = {},
            onSend = {},
        )
    }
}
