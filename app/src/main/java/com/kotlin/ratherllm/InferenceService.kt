package com.kotlin.ratherllm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service that OWNS the engine, the conversation, and the in-flight
 * generation. All UI-visible state is exposed as StateFlows, so the Activity is
 * a thin observer: rotation, backgrounding, and rebinds never interrupt a stream
 * because it runs in [serviceScope], not the Activity's composition scope.
 */
class InferenceService : Service() {

    enum class Status { Idle, Loading, Ready, Generating, Error }

    inner class LocalBinder : Binder() {
        val service: InferenceService get() = this@InferenceService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- Observable state ---------------------------------------------------
    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _loadProgress = MutableStateFlow(0f)
    val loadProgress: StateFlow<Float> = _loadProgress.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _modelName = MutableStateFlow<String?>(null)
    val modelName: StateFlow<String?> = _modelName.asStateFlow()

    private val _lastDecodeTps = MutableStateFlow<Float?>(null)
    val lastDecodeTps: StateFlow<Float?> = _lastDecodeTps.asStateFlow()

    private val _activeModelPath = MutableStateFlow<String?>(null)
    val activeModelPath: StateFlow<String?> = _activeModelPath.asStateFlow()

    // ---- Model management (repo/downloader live here so long ops survive UI) --
    val repo: ModelRepository by lazy { ModelRepository(this) }
    private val downloader: ModelDownloader by lazy { ModelDownloader(repo) }

    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    private val _opText = MutableStateFlow<String?>(null)   // import/download message (progress or error)
    val opText: StateFlow<String?> = _opText.asStateFlow()
    private val _opFraction = MutableStateFlow(-1f)
    val opFraction: StateFlow<Float> = _opFraction.asStateFlow()
    private val _opBusy = MutableStateFlow(false)           // an import/download is running
    val opBusy: StateFlow<Boolean> = _opBusy.asStateFlow()

    /** The just-imported model, so the import-config popup can configure it. */
    private val _lastImported = MutableStateFlow<ModelEntry?>(null)
    val lastImported: StateFlow<ModelEntry?> = _lastImported.asStateFlow()

    // ---- Conversation history -----------------------------------------------
    private val store: ConversationStore by lazy { ConversationStore(this) }
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    // ---- New feature backends (saved replies, model meta, settings, analytics) --
    private val savedStore: SavedReplyStore by lazy { SavedReplyStore(this) }
    private val metaStore: ModelMetaStore by lazy { ModelMetaStore(this) }
    val settingsStore: AppSettingsStore by lazy { AppSettingsStore(this) }
    private val analytics: DeviceAnalytics by lazy { DeviceAnalytics(this) }

    private val _savedReplies = MutableStateFlow<List<SavedReply>>(emptyList())
    val savedReplies: StateFlow<List<SavedReply>> = _savedReplies.asStateFlow()

    /** Per-model editable metadata (desc/caps/mmproj), keyed by file name. */
    private val _modelMetas = MutableStateFlow<Map<String, ModelMeta>>(emptyMap())
    val modelMetas: StateFlow<Map<String, ModelMeta>> = _modelMetas.asStateFlow()

    private val _deviceStats = MutableStateFlow<DeviceStats?>(null)
    val deviceStats: StateFlow<DeviceStats?> = _deviceStats.asStateFlow()
    private val _analyticsEnabled = MutableStateFlow(false)
    val analyticsEnabled: StateFlow<Boolean> = _analyticsEnabled.asStateFlow()
    private var analyticsJob: Job? = null

    private val _experimentalEnabled = MutableStateFlow(false)
    val experimentalEnabled: StateFlow<Boolean> = _experimentalEnabled.asStateFlow()
    private val _maximizeMemory = MutableStateFlow(false)
    val maximizeMemory: StateFlow<Boolean> = _maximizeMemory.asStateFlow()
    private val _multiResponse = MutableStateFlow(false)
    val multiResponse: StateFlow<Boolean> = _multiResponse.asStateFlow()

    /** Paths chosen for multi-response mode (each answers, back to back). */
    private val _multiSelectedPaths = MutableStateFlow<List<String>>(emptyList())
    val multiSelectedPaths: StateFlow<List<String>> = _multiSelectedPaths.asStateFlow()

    /** While a multi-response batch runs, how many models remain after the current one. */
    private val _multiRemaining = MutableStateFlow(0)
    val multiRemaining: StateFlow<Int> = _multiRemaining.asStateFlow()
    private val _streamingModelName = MutableStateFlow<String?>(null)
    val streamingModelName: StateFlow<String?> = _streamingModelName.asStateFlow()

    private val _currentId = MutableStateFlow("")
    val currentConversationId: StateFlow<String> = _currentId.asStateFlow()
    private var currentId: String
        get() = _currentId.value
        set(v) { _currentId.value = v }

    @Volatile var lastError: String? = null; private set
    @Volatile var modelPath: String? = null; private set
    @Volatile var lastTimingsJson: String? = null; private set

    /** User-tunable generation settings, persisted so they survive restarts. */
    @Volatile var genParams: GenParams = GenParams()
        set(value) { field = value; runCatching { settingsStore.saveGenParams(value) } }
    @Volatile var systemPrompt: String = ""
        set(value) { field = value; runCatching { settingsStore.systemPrompt = value } }

    @Volatile private var handle: Long = 0L
    private var loadJob: Job? = null
    private var genJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val isReady: Boolean get() = handle > 0L && _status.value == Status.Ready

    // ---- Service lifecycle --------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Restore persisted user settings synchronously (cheap SharedPreferences reads).
        runCatching {
            genParams = settingsStore.loadGenParams()
            systemPrompt = settingsStore.systemPrompt
            _experimentalEnabled.value = settingsStore.experimentalEnabled
            _maximizeMemory.value = settingsStore.maximizeMemory && settingsStore.experimentalEnabled
            _multiResponse.value = settingsStore.multiResponse && settingsStore.experimentalEnabled
        }
        serviceScope.launch {
            runCatching { repo.migrateLegacyModel() }
            reloadModels()
            _savedReplies.value = runCatching { savedStore.load() }.getOrDefault(emptyList())
            if (settingsStore.analyticsEnabled) setAnalyticsEnabled(true)
            val convos = runCatching { store.load() }.getOrDefault(emptyList())
            if (convos.isNotEmpty()) {
                _conversations.value = convos
                currentId = convos.first().id
                _messages.value = convos.first().messages
            } else {
                val c = Conversation()
                currentId = c.id
                _conversations.value = listOf(c)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP   -> { stopSelfClean(); return START_NOT_STICKY }
            ACTION_CANCEL -> { cancelGeneration(); return START_STICKY }
        }
        // Promote to foreground but DO NOT auto-load a model (load is on-demand).
        startForegroundCompat(buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        genJob?.cancel()
        unloadModel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Under real memory pressure, drop the model (unless mid-generation) so the
        // LMK doesn't kill us; the UI explains it and offers reload.
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && _status.value != Status.Generating && handle != 0L) {
            unloadModel()
            lastError = "Model unloaded to free memory — tap to reload."
            _status.value = Status.Error
            updateNotification()
        }
    }

    // ---- Model load / unload (on-demand) ------------------------------------

    fun loadModel(path: String) {
        if (_status.value == Status.Loading || _status.value == Status.Generating) return
        if (!NativeBridge.isLibraryLoaded) {
            fail("native library failed to load: ${NativeBridge.loadError}"); return
        }
        loadJob?.cancel()
        unloadModel()
        modelPath = path
        lastError = null
        _loadProgress.value = 0f
        _status.value = Status.Loading
        updateNotification()

        loadJob = serviceScope.launch {
            val rc = withContext(Dispatchers.Default) {
                runCatching {
                    NativeBridge.loadModel(
                        path = path,
                        nCtx = DEFAULT_CTX,
                        nThreads = DEFAULT_THREADS,
                        nThreadsBatch = DEFAULT_THREADS_BATCH,
                        nGpuLayers = 0,
                        // Maximize memory allocation → mlock the weights so they stay
                        // resident in physical RAM (best-effort; llama.cpp warns and
                        // continues if the lock can't be taken).
                        useMlock = _maximizeMemory.value,
                        progress = { p -> _loadProgress.value = p },
                    )
                }.getOrElse { -1L }
            }
            if (rc > 0L) {
                handle = rc
                _activeModelPath.value = path
                _loadProgress.value = 1f
                _modelName.value = runCatching {
                    NativeBridge.getModelInfo(path)?.let { JSONObject(it).optString("desc").ifBlank { null } }
                }.getOrNull() ?: path.substringAfterLast('/')
                _status.value = Status.Ready
                updateNotification()
            } else {
                handle = 0L
                fail(Rc.message(rc.toInt()))
            }
        }
    }

    fun unloadModel() {
        val h = handle
        if (h != 0L) { handle = 0L; runCatching { NativeBridge.freeModel(h) } }
        _modelName.value = null
        _activeModelPath.value = null
        if (_status.value != Status.Error) _status.value = Status.Idle
    }

    // ---- Model management (import / download / delete) -----------------------

    fun refreshModels() {
        serviceScope.launch { reloadModels() }
    }

    /** Lists model files and rebuilds the metadata map (desc/caps/mmproj) in lockstep. */
    private fun reloadModels() {
        val list = runCatching { repo.listModels() }.getOrDefault(emptyList())
        _models.value = list
        _modelMetas.value = list.associate { it.name to (metaStore.get(it.name) ?: defaultMetaFor(it)) }
    }

    /** A sensible default meta for a model that has never been configured. */
    private fun defaultMetaFor(entry: ModelEntry): ModelMeta = ModelMeta()

    fun updateModelMeta(fileName: String, meta: ModelMeta) {
        metaStore.put(fileName, meta)
        _modelMetas.value = _modelMetas.value + (fileName to meta)
    }

    fun importModel(uri: Uri) {
        if (_opBusy.value) return
        serviceScope.launch {
            _opBusy.value = true; _opFraction.value = -1f; _opText.value = "Importing model…"
            _lastImported.value = null
            val res = repo.importFromUri(uri) { f ->
                _opFraction.value = f
                _opText.value = if (f >= 0) "Importing model… ${(f * 100).toInt()}%" else "Importing model…"
            }
            finishOp(res.isSuccess, res.exceptionOrNull()?.message, "Import failed")
            // Surface the finished entry so the config popup can open on it.
            if (res.isSuccess) {
                val path = res.getOrNull()?.absolutePath
                _lastImported.value = _models.value.find { it.path == path }
            }
        }
    }

    fun clearLastImported() { _lastImported.value = null }

    /**
     * Imports a vision/multimodal projector (mmproj) GGUF into the companion store
     * and invokes [onDone] on the main thread with its stored file name (or null).
     */
    fun importMmproj(uri: Uri, onDone: (String?) -> Unit) {
        serviceScope.launch {
            val res = runCatching { repo.importMmproj(uri) }.getOrElse { Result.failure(it) }
            val name = res.getOrNull()?.name
            withContext(Dispatchers.Main) { onDone(name) }
        }
    }

    /**
     * A best-effort hint that a model's architecture is a known multimodal/vision
     * family, used only to pre-suggest the Image capability — the user still
     * confirms it with the checkbox at upload.
     */
    fun looksMultimodal(entry: ModelEntry?): Boolean {
        val arch = entry?.info?.arch?.lowercase() ?: return false
        val name = entry.name.lowercase()
        val hints = listOf(
            "llava", "vl", "vision", "mmproj", "minicpmv", "minicpm-v", "moondream",
            "smolvlm", "internvl", "pixtral", "idefics", "mllama", "mobilevlm",
            "gemma3", "qwen2vl", "qwen2.5vl", "llama4", "cogvlm", "bunny", "glm-4v",
        )
        return hints.any { arch.contains(it) || name.contains(it) }
    }

    fun downloadModel(url: String, name: String) {
        if (_opBusy.value) return
        serviceScope.launch {
            _opBusy.value = true; _opFraction.value = -1f; _opText.value = "Downloading…"
            val res = downloader.download(url, name) { p ->
                _opFraction.value = p.fraction
                _opText.value = if (p.fraction >= 0) "Downloading… ${(p.fraction * 100).toInt()}%"
                                else "Downloading… ${formatBytes(p.downloadedBytes)}"
            }
            finishOp(res.isSuccess, res.exceptionOrNull()?.message, "Download failed")
        }
    }

    fun deleteModel(entry: ModelEntry) {
        serviceScope.launch {
            if (entry.path == _activeModelPath.value) unloadModel()
            runCatching { repo.delete(entry.file) }
            runCatching { metaStore.remove(entry.name) }
            _multiSelectedPaths.value = _multiSelectedPaths.value.filter { it != entry.path }
            reloadModels()
        }
    }

    fun dismissOpMessage() { if (!_opBusy.value) _opText.value = null }

    private fun finishOp(success: Boolean, errorMsg: String?, failPrefix: String) {
        _opFraction.value = -1f
        _opBusy.value = false
        reloadModels()
        _opText.value = if (success) null else "$failPrefix: ${errorMsg ?: "unknown error"}"
    }

    // ---- Generation (single in-flight request) ------------------------------

    private data class StreamResult(val text: String, val tps: Float?, val rc: Int)

    /** Runs one native generation, streaming into [_streamingText] via [sb]. */
    private suspend fun streamOnce(h: Long, requestJson: String, sb: StringBuilder): StreamResult {
        val job = coroutineContext[Job]
        val rc = withContext(Dispatchers.Default) {
            NativeBridge.generate(h, requestJson, TokenCallback { piece ->
                sb.append(piece)
                _streamingText.value = sb.toString()
                job?.isActive != false
            })
        }
        lastTimingsJson = runCatching { NativeBridge.lastTimings(h) }.getOrNull()
        val tps = lastTimingsJson?.let {
            runCatching { JSONObject(it).optDouble("decodeTokPerSec").toFloat().takeIf { v -> v > 0f } }.getOrNull()
        }
        _lastDecodeTps.value = tps
        return StreamResult(sb.toString(), tps, rc)
    }

    fun submit(userText: String, attachments: List<Attachment> = emptyList()) {
        val text = userText.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (_status.value == Status.Generating) return

        // Multi-response mode: several selected models answer the same prompt in turn.
        val multiPaths = if (_multiResponse.value) {
            _multiSelectedPaths.value.filter { it.isNotBlank() }.distinct()
        } else emptyList()
        when {
            // Two+ models → run the sequential batch.
            multiPaths.size > 1 -> { submitMulti(text, multiPaths, attachments); return }
            // Exactly one selected but it isn't the loaded/ready model → route through
            // submitMulti so it gets loaded first instead of dropping the message.
            multiPaths.size == 1 && !(isReady && _activeModelPath.value == multiPaths[0]) -> {
                submitMulti(text, multiPaths, attachments); return
            }
        }
        if (!isReady) return

        val convo = _messages.value + ChatMessage(Role.User, text, attachments = attachments)
        _messages.value = convo
        persistCurrent()
        _status.value = Status.Generating
        acquireWakeLock()
        updateNotification()

        val h = handle
        val request = buildRequestJson(convo)
        genJob = serviceScope.launch {
            val sb = StringBuilder()
            try {
                val r = streamOnce(h, request, sb)
                _messages.value = _messages.value + when {
                    r.text.isNotEmpty() -> ChatMessage(Role.Assistant, r.text, tps = r.tps, modelName = _modelName.value)
                    r.rc < 0            -> ChatMessage(Role.Assistant, "⚠️ ${Rc.message(r.rc)}")
                    else                -> ChatMessage(Role.Assistant, "")
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                if (sb.isNotEmpty()) _messages.value = _messages.value +
                    ChatMessage(Role.Assistant, sb.toString(), tps = _lastDecodeTps.value, modelName = _modelName.value)
                throw c
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(Role.Assistant, "⚠️ ${e.message}")
            } finally {
                _streamingText.value = ""
                persistCurrent()
                _status.value = if (handle > 0L) Status.Ready else Status.Idle
                releaseWakeLock()
                updateNotification()
            }
        }
    }

    /**
     * Multi-response: load each selected model in turn and let it answer the same
     * prompt, appending one labeled reply per model. Sequential (load→answer→next)
     * rather than co-resident, which is far safer for RAM on a phone.
     */
    private fun submitMulti(text: String, paths: List<String>, attachments: List<Attachment> = emptyList()) {
        val convo = _messages.value + ChatMessage(Role.User, text, attachments = attachments)
        _messages.value = convo
        persistCurrent()
        _status.value = Status.Generating
        acquireWakeLock()
        updateNotification()

        genJob = serviceScope.launch {
            try {
                paths.forEachIndexed { index, path ->
                    _multiRemaining.value = paths.size - index - 1
                    val label = modelShortLabel(path)
                    _streamingModelName.value = label
                    _streamingText.value = ""
                    if (!ensureLoaded(path)) {
                        _messages.value = _messages.value +
                            ChatMessage(Role.Assistant, "⚠️ Couldn't load $label", modelName = label)
                        return@forEachIndexed
                    }
                    val sb = StringBuilder()
                    val r = streamOnce(handle, buildRequestJson(convo), sb)
                    _streamingText.value = ""
                    _messages.value = _messages.value + when {
                        r.text.isNotEmpty() -> ChatMessage(Role.Assistant, r.text, tps = r.tps, modelName = label)
                        r.rc < 0            -> ChatMessage(Role.Assistant, "⚠️ ${Rc.message(r.rc)}", modelName = label)
                        else                -> ChatMessage(Role.Assistant, "", modelName = label)
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                if (_streamingText.value.isNotEmpty()) _messages.value = _messages.value +
                    ChatMessage(Role.Assistant, _streamingText.value, modelName = _streamingModelName.value)
                throw c
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(Role.Assistant, "⚠️ ${e.message}")
            } finally {
                _streamingText.value = ""
                _streamingModelName.value = null
                _multiRemaining.value = 0
                persistCurrent()
                _status.value = if (handle > 0L) Status.Ready else Status.Idle
                releaseWakeLock()
                updateNotification()
            }
        }
    }

    /** Loads [path] if it isn't already the active model. Returns true on success. */
    private suspend fun ensureLoaded(path: String): Boolean {
        if (_activeModelPath.value == path && handle != 0L) return true
        _loadProgress.value = 0f
        val rc = withContext(Dispatchers.Default) {
            val old = handle
            if (old != 0L) { handle = 0L; runCatching { NativeBridge.freeModel(old) } }
            runCatching {
                NativeBridge.loadModel(
                    path = path, nCtx = DEFAULT_CTX, nThreads = DEFAULT_THREADS,
                    nThreadsBatch = DEFAULT_THREADS_BATCH, nGpuLayers = 0,
                    useMlock = _maximizeMemory.value,
                    progress = { p -> _loadProgress.value = p },
                )
            }.getOrElse { -1L }
        }
        return if (rc > 0L) {
            handle = rc
            modelPath = path
            _activeModelPath.value = path
            _loadProgress.value = 1f
            _modelName.value = modelShortLabel(path)
            true
        } else { handle = 0L; false }
    }

    /** A friendly short label for a model path (GGUF name if present, else file stem). */
    fun modelShortLabel(path: String?): String {
        if (path == null) return "No model"
        val entry = _models.value.find { it.path == path }
        val desc = entry?.info?.desc
        return if (!desc.isNullOrBlank()) desc else File(path).name.removeSuffix(".gguf")
    }

    fun cancelGeneration() {
        val h = handle
        if (h != 0L) runCatching { NativeBridge.cancel(h) } // trip native flag now (<1 token)
        genJob?.cancel()
    }

    /** Drop the last assistant reply and re-run the last user turn. */
    fun regenerateLast() {
        if (!isReady || _status.value == Status.Generating) return
        val msgs = _messages.value
        val lastUserIdx = msgs.indexOfLast { it.role == Role.User }
        if (lastUserIdx < 0) return
        val userText = msgs[lastUserIdx].text
        _messages.value = msgs.take(lastUserIdx)
        submit(userText)
    }

    fun newConversation() {
        if (_status.value == Status.Generating) cancelGeneration()
        val c = Conversation()
        currentId = c.id
        _messages.value = emptyList()
        _streamingText.value = ""
        _conversations.value = listOf(c) + _conversations.value
        serviceScope.launch { store.save(_conversations.value) }
    }

    fun switchConversation(id: String) {
        if (id == currentId) return
        if (_status.value == Status.Generating) cancelGeneration()
        val c = _conversations.value.find { it.id == id } ?: return
        currentId = id
        _messages.value = c.messages
        _streamingText.value = ""
    }

    fun deleteConversation(id: String) {
        serviceScope.launch {
            val remaining = _conversations.value.filter { it.id != id }
            _conversations.value = remaining
            store.save(remaining)
            if (id == currentId) {
                val next = remaining.firstOrNull()
                if (next != null) { currentId = next.id; _messages.value = next.messages }
                else { newConversation() }
            }
        }
    }

    /** Snapshot the current messages into the current conversation and persist. */
    private fun persistCurrent() {
        val id = currentId.ifBlank { return }
        val msgs = _messages.value
        val existing = _conversations.value.find { it.id == id }
        val title = existing?.title?.takeUnless { it == "New chat" }
            ?: msgs.firstOrNull { it.role == Role.User }?.let { ConversationStore.titleFrom(it.text) }
            ?: "New chat"
        val updated = Conversation(id, title, msgs, System.currentTimeMillis())
        _conversations.value = listOf(updated) + _conversations.value.filter { it.id != id }
        serviceScope.launch { store.save(_conversations.value) }
    }

    // ---- Device analytics ---------------------------------------------------

    fun setAnalyticsEnabled(on: Boolean) {
        _analyticsEnabled.value = on
        settingsStore.analyticsEnabled = on
        analyticsJob?.cancel()
        if (on) {
            analytics.resetCpuBaseline()
            analyticsJob = serviceScope.launch {
                // First reading after a reset baseline; then steady 1.5s cadence.
                _deviceStats.value = withContext(Dispatchers.Default) { analytics.sample() }
                while (isActive) {
                    delay(1500)
                    _deviceStats.value = withContext(Dispatchers.Default) { analytics.sample() }
                }
            }
        } else {
            _deviceStats.value = null
        }
    }

    // ---- Saved replies ------------------------------------------------------

    fun isSaved(messageId: String): Boolean = _savedReplies.value.any { it.messageId == messageId }

    fun toggleSaved(message: ChatMessage) {
        val already = isSaved(message.id)
        _savedReplies.value = if (already) {
            _savedReplies.value.filter { it.messageId != message.id }
        } else {
            val clean = message.text.replace(Regex("[*`#]+"), " ").replace(Regex("\\s+"), " ").trim()
            listOf(SavedReply(message.id, clean, currentId, currentTitle())) + _savedReplies.value
        }
        serviceScope.launch { savedStore.save(_savedReplies.value) }
    }

    fun removeSaved(messageId: String) {
        _savedReplies.value = _savedReplies.value.filter { it.messageId != messageId }
        serviceScope.launch { savedStore.save(_savedReplies.value) }
    }

    private fun currentTitle(): String =
        _conversations.value.find { it.id == currentId }?.title
            ?: _messages.value.firstOrNull { it.role == Role.User }?.let { ConversationStore.titleFrom(it.text) }
            ?: "New chat"

    // ---- Experimental features ----------------------------------------------

    fun setExperimentalEnabled(on: Boolean) {
        _experimentalEnabled.value = on
        settingsStore.experimentalEnabled = on
        if (!on) {
            // Turning the master switch off disables everything it gates.
            setMaximizeMemory(false)
            setMultiResponse(false)
        }
    }

    fun setMaximizeMemory(on: Boolean) {
        val v = on && _experimentalEnabled.value
        _maximizeMemory.value = v
        settingsStore.maximizeMemory = v
    }

    fun setMultiResponse(on: Boolean) {
        val v = on && _experimentalEnabled.value
        _multiResponse.value = v
        settingsStore.multiResponse = v
        if (!v) _multiSelectedPaths.value = emptyList()
    }

    fun setMultiSelected(paths: List<String>) { _multiSelectedPaths.value = paths }

    /**
     * RAM budget (GB) for loading models: free RAM minus a 1GB safety reserve,
     * or all free RAM when Maximize memory allocation is on.
     */
    fun ramBudgetGb(): Double {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val freeGb = DeviceMemory.availableBytes(am) / 1e9
        return if (_maximizeMemory.value) maxOf(0.0, freeGb) else maxOf(0.0, freeGb - 1.0)
    }

    /** Live free RAM in bytes (kernel MemAvailable) for UI panels that refresh. */
    fun freeRamBytes(): Long =
        DeviceMemory.availableBytes(getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)

    // ---- Export -------------------------------------------------------------

    /** Serializes a conversation to [format] and returns the file (in cacheDir/exports). */
    fun exportConversation(conversationId: String, format: ExportFormat): File? {
        val convo = _conversations.value.find { it.id == conversationId }
            ?: if (conversationId == currentId) Conversation(currentId, currentTitle(), _messages.value) else return null
        return runCatching { ChatExporter.export(this, convo, format) }.getOrNull()
    }

    fun currentConversationSnapshot(): Conversation =
        Conversation(currentId, currentTitle(), _messages.value)

    /**
     * The text handed to the engine for a message, with attachment context folded
     * in: text-like files are inlined (truncated) so a text model can use them;
     * images/binaries become a labelled note. (True pixel-level vision needs the
     * native mtmd/mmproj path, which this text-only engine build doesn't include.)
     */
    private fun promptContent(m: ChatMessage): String {
        if (m.attachments.isEmpty()) return m.text
        val sb = StringBuilder(m.text)
        m.attachments.forEach { a ->
            when {
                AttachmentUtil.isTextLike(a) -> {
                    val body = AttachmentUtil.readTextForPrompt(a)
                    sb.append("\n\n[Attached file: ${a.name}]")
                    if (body != null) sb.append('\n').append(body)
                }
                AttachmentUtil.isImage(a) -> sb.append("\n\n[Attached image: ${a.name}]")
                else -> sb.append("\n\n[Attached file: ${a.name}]")
            }
        }
        return sb.toString().trim()
    }

    private fun buildRequestJson(convo: List<ChatMessage>): String {
        val arr = JSONArray()
        if (systemPrompt.isNotBlank()) {
            arr.put(JSONObject().put("role", Role.System.wire).put("content", systemPrompt))
        }
        convo.forEach { m -> arr.put(JSONObject().put("role", m.role.wire).put("content", promptContent(m))) }
        val p = genParams
        val params = JSONObject()
            .put("maxTokens", p.maxTokens).put("temperature", p.temperature.toDouble())
            .put("topK", p.topK).put("topP", p.topP.toDouble()).put("minP", p.minP.toDouble())
            .put("repeatPenalty", p.repeatPenalty.toDouble()).put("seed", p.seed)
        return JSONObject().put("messages", arr).put("params", params).toString()
    }

    private fun stopSelfClean() {
        genJob?.cancel()
        unloadModel()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        lastError = message
        _status.value = Status.Error
        updateNotification()
    }

    // ---- Wake lock (held only while generating) -----------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    // ---- Notification -------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "On-device AI Inference", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the local LLM engine resident and responsive."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun statusText(): String = when (_status.value) {
        Status.Idle -> "Idle — no model loaded"
        Status.Loading -> "Loading model… ${(loadProgress.value * 100).toInt()}%"
        Status.Ready -> "Ready — ${_modelName.value ?: "model loaded"}"
        Status.Generating -> "Generating…"
        Status.Error -> "Error: ${lastError ?: "unknown"}"
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val generating = _status.value == Status.Generating
        val action = if (generating) ACTION_CANCEL else ACTION_STOP
        val actionLabel = if (generating) "Stop generating" else "Stop engine"
        val actionPi = PendingIntent.getService(
            this, if (generating) 2 else 1,
            Intent(this, InferenceService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ratherllm")
            .setContentText(statusText())
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { contentPi?.let { setContentIntent(it) } }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, actionLabel, actionPi)
            .build()
    }

    private fun updateNotification() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        const val CHANNEL_ID = "ratherllm_inference"
        const val NOTIFICATION_ID = 0x524C
        const val WAKELOCK_TAG = "ratherllm:decode"
        private const val WAKELOCK_TIMEOUT_MS = 10L * 60L * 1000L

        const val ACTION_STOP = "com.kotlin.ratherllm.action.STOP_ENGINE"
        const val ACTION_CANCEL = "com.kotlin.ratherllm.action.CANCEL_GEN"

        private const val DEFAULT_CTX = 4096
        // Benchmarked on the MT6991: decode is memory-bound and fastest on the 4
        // big cores only (4 thr = 11.0 tok/s vs 8 thr = 7.7 for Gemma 3 4B Q4_0);
        // prefill is compute-bound and gains from all 8 cores.
        private const val DEFAULT_THREADS = 4
        private const val DEFAULT_THREADS_BATCH = 8

        fun start(context: Context) {
            context.startForegroundService(Intent(context, InferenceService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, InferenceService::class.java).apply { action = ACTION_STOP })
        }
    }
}
