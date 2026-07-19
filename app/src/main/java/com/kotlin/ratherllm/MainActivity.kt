package com.kotlin.ratherllm

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

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

    // Routed through pendingImportUri (not called directly): opening the system
    // picker backgrounds this Activity, and calling boundService.value?.importModel
    // straight from the callback used to silently no-op when the rebind (below)
    // hadn't completed yet by the time the picker returned.
    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) pendingImportUri = uri
        }

    // Set from a VIEW/SEND intent (e.g. "Open with RatherLLM" on a .gguf in Files/Downloads)
    // or from the in-app "Import .gguf" picker above. Consumed by AppRoot once the
    // service is bound, which also switches the UI to the Models screen.
    private var pendingImportUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        InferenceService.start(this)
        handleIncomingIntent(intent)
        // Bind once for the Activity's whole lifetime (unbound only in onDestroy).
        // Binding per onStart/onStop used to drop the connection every time an
        // external Activity (file picker, share sheet) briefly backgrounded us,
        // racing any binding-dependent action taken right after it returned.
        bindService(Intent(this, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE)

        setContent {
            RatherLlmTheme {
                val service by boundService.collectAsState()
                val svc = service
                if (svc == null) {
                    Box(Modifier.fillMaxSize().background(AppColors.Bg), contentAlignment = Alignment.Center) {
                        Text("Connecting to engine…", style = AppText.Body, color = AppColors.TextSecondary)
                    }
                } else {
                    AppRoot(
                        svc = svc,
                        pendingImportUri = pendingImportUri,
                        onConsumePendingImportUri = { pendingImportUri = null },
                        onImportGguf = { openDocument.launch(arrayOf("*/*")) },
                        onShareExport = { file, mime -> shareFile(file, mime) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) pendingImportUri = uri
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(connection) }
        boundService.value = null
    }

    private fun requestNotificationPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Shares an exported chat file via the system share sheet (FileProvider-backed). */
    private fun shareFile(file: File, mime: String) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Export conversation")) }
    }
}

private enum class Screen { Chat, Models, Experimental }

/**
 * Top-level composable that owns navigation between the chat, models, and
 * experimental screens, the drawer, the settings sheet, and every cross-screen
 * overlay (dialogs + toast). All persistent state lives in [svc]; this only holds
 * ephemeral UI state (which screen, which dialog is open).
 */
@Composable
private fun AppRoot(
    svc: InferenceService,
    pendingImportUri: Uri?,
    onConsumePendingImportUri: () -> Unit,
    onImportGguf: () -> Unit,
    onShareExport: (File, String) -> Unit,
) {
    var screen by remember { mutableStateOf(Screen.Chat) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showImportConfig by remember { mutableStateOf(false) }

    // Runs once svc is bound (it always is here, but this also re-fires if a new
    // uri arrives while already on this screen) — jumps to Models and pops the
    // import-config popup, which carries the progress bar and, once the copy
    // finishes, the capability/multimodal confirmation for the new model.
    LaunchedEffect(svc, pendingImportUri) {
        if (pendingImportUri != null) {
            screen = Screen.Models
            svc.importModel(pendingImportUri)
            showImportConfig = true
            onConsumePendingImportUri()
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var exportConvId by remember { mutableStateOf<String?>(null) }
    var deleteModelTarget by remember { mutableStateOf<ModelEntry?>(null) }
    var deleteConvTarget by remember { mutableStateOf<Conversation?>(null) }
    var editConfigTarget by remember { mutableStateOf<ModelEntry?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    val activePath by svc.activeModelPath.collectAsState()
    val metas by svc.modelMetas.collectAsState()

    // System back: close the drawer, then step sub-screens back to chat.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = !drawerState.isOpen && screen != Screen.Chat) {
        svc.dismissOpMessage(); screen = Screen.Chat
    }

    fun showToast(t: String) { toast = t }
    LaunchedEffect(toast) {
        if (toast != null) { kotlinx.coroutines.delay(1800); toast = null }
    }

    val deviceLine = remember {
        val totalGb = (svc.repo.totalRamBytes / 1e9).toInt()
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · ${totalGb}GB RAM"
    }

    Box(Modifier.fillMaxSize().background(AppColors.Bg)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = screen == Screen.Chat,
            scrimColor = AppColors.Bg.copy(alpha = 0.55f),
            drawerContent = {
                AppDrawer(
                    svc = svc,
                    deviceLine = deviceLine,
                    onNewChat = { svc.newConversation(); scope.launch { drawerState.close() } },
                    onSelectConversation = { id -> svc.switchConversation(id); scope.launch { drawerState.close() } },
                    onRequestExportConversation = { id -> exportConvId = id; scope.launch { drawerState.close() } },
                    onRequestDeleteConversation = { conv -> deleteConvTarget = conv },
                    onOpenSettings = { showSettings = true; scope.launch { drawerState.close() } },
                    onOpenModels = { screen = Screen.Models; scope.launch { drawerState.close() } },
                    onOpenExperimental = { screen = Screen.Experimental; scope.launch { drawerState.close() } },
                    onOpenSavedReply = { s -> svc.switchConversation(s.conversationId); scope.launch { drawerState.close() } },
                    onToast = ::showToast,
                )
            },
        ) {
            when (screen) {
                Screen.Chat -> ChatScreen(
                    svc = svc,
                    onOpenModels = { screen = Screen.Models },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onExportCurrent = { exportConvId = svc.currentConversationSnapshot().id },
                    onToast = ::showToast,
                )
                Screen.Models -> ModelsScreen(
                    svc = svc,
                    onBack = { svc.dismissOpMessage(); screen = Screen.Chat },
                    onImport = onImportGguf,
                    onLoaded = { screen = Screen.Chat },
                    onRequestDelete = { entry -> deleteModelTarget = entry },
                    onRequestEditConfig = { entry -> editConfigTarget = entry },
                    onToast = ::showToast,
                )
                Screen.Experimental -> ExperimentalScreen(
                    svc = svc,
                    onBack = { screen = Screen.Chat },
                )
            }
        }

        // Toast floats above everything (including the drawer scrim).
        Box(Modifier.fillMaxSize().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
            ToastHost(message = toast)
        }
    }

    // ---- Overlays -----------------------------------------------------------

    if (showSettings) {
        SettingsSheet(
            current = svc.genParams,
            systemPrompt = svc.systemPrompt,
            onApply = { p, s -> svc.genParams = p; svc.systemPrompt = s },
            onDismiss = { showSettings = false },
        )
    }

    exportConvId?.let { convId ->
        val title = svc.conversations.value.find { it.id == convId }?.title ?: "this conversation"
        ExportDialog(
            title = title,
            onExport = { fmt ->
                val file = svc.exportConversation(convId, fmt)
                exportConvId = null
                if (file != null) { onShareExport(file, fmt.mime); showToast("Exported ${fmt.fileName}") }
                else showToast("Nothing to export yet")
            },
            onDismiss = { exportConvId = null },
        )
    }

    deleteModelTarget?.let { entry ->
        DeleteModelDialog(
            entry = entry,
            isActive = entry.path == activePath,
            onConfirm = { svc.deleteModel(entry); deleteModelTarget = null; showToast("Model deleted") },
            onDismiss = { deleteModelTarget = null },
        )
    }

    deleteConvTarget?.let { conv ->
        DeleteConversationDialog(
            conv = conv,
            onConfirm = { svc.deleteConversation(conv.id); deleteConvTarget = null; showToast("Chat deleted") },
            onDismiss = { deleteConvTarget = null },
        )
    }

    editConfigTarget?.let { entry ->
        EditConfigDialog(
            entry = entry,
            meta = metas[entry.name] ?: ModelMeta(),
            onImportMmproj = { uri, cb -> svc.importMmproj(uri, cb) },
            onSave = { meta -> svc.updateModelMeta(entry.name, meta); editConfigTarget = null; showToast("Configuration saved") },
            onDismiss = { editConfigTarget = null },
        )
    }

    if (showImportConfig) {
        ImportConfigDialog(
            svc = svc,
            onImportMmproj = { uri, cb -> svc.importMmproj(uri, cb) },
            onToast = ::showToast,
            onDismiss = { showImportConfig = false; svc.clearLastImported() },
        )
    }
}
