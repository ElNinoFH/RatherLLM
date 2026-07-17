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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    @Volatile var lastError: String? = null; private set
    @Volatile var modelPath: String? = null; private set
    @Volatile var lastTimingsJson: String? = null; private set

    /** User-tunable generation settings (Stage 5 exposes these in the UI). */
    @Volatile var genParams: GenParams = GenParams()
    @Volatile var systemPrompt: String = ""

    @Volatile private var handle: Long = 0L
    private var loadJob: Job? = null
    private var genJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val isReady: Boolean get() = handle > 0L && _status.value == Status.Ready

    // ---- Service lifecycle --------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            runCatching { repo.migrateLegacyModel() }
            _models.value = runCatching { repo.listModels() }.getOrDefault(emptyList())
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
                        nThreadsBatch = DEFAULT_THREADS,
                        nGpuLayers = 0,
                        useMlock = false,
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
        serviceScope.launch { _models.value = runCatching { repo.listModels() }.getOrDefault(emptyList()) }
    }

    fun importModel(uri: Uri) {
        if (_opBusy.value) return
        serviceScope.launch {
            _opBusy.value = true; _opFraction.value = -1f; _opText.value = "Importing model…"
            val res = repo.importFromUri(uri) { f ->
                _opFraction.value = f
                _opText.value = if (f >= 0) "Importing model… ${(f * 100).toInt()}%" else "Importing model…"
            }
            finishOp(res.isSuccess, res.exceptionOrNull()?.message, "Import failed")
        }
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
            _models.value = runCatching { repo.listModels() }.getOrDefault(emptyList())
        }
    }

    fun dismissOpMessage() { if (!_opBusy.value) _opText.value = null }

    private fun finishOp(success: Boolean, errorMsg: String?, failPrefix: String) {
        _opFraction.value = -1f
        _opBusy.value = false
        _models.value = runCatching { repo.listModels() }.getOrDefault(emptyList())
        _opText.value = if (success) null else "$failPrefix: ${errorMsg ?: "unknown error"}"
    }

    // ---- Generation (single in-flight request) ------------------------------

    fun submit(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || !isReady) return

        val convo = _messages.value + ChatMessage(Role.User, text)
        _messages.value = convo
        _status.value = Status.Generating
        acquireWakeLock()
        updateNotification()

        val h = handle
        val request = buildRequestJson(convo)
        genJob = serviceScope.launch {
            val job = coroutineContext[Job]
            val sb = StringBuilder()
            try {
                val rc = withContext(Dispatchers.Default) {
                    NativeBridge.generate(h, request, TokenCallback { piece ->
                        sb.append(piece)
                        _streamingText.value = sb.toString()
                        job?.isActive != false
                    })
                }
                lastTimingsJson = runCatching { NativeBridge.lastTimings(h) }.getOrNull()
                _lastDecodeTps.value = lastTimingsJson?.let {
                    runCatching { JSONObject(it).optDouble("decodeTokPerSec").toFloat().takeIf { v -> v > 0f } }.getOrNull()
                }
                val out = sb.toString()
                _messages.value = _messages.value + when {
                    out.isNotEmpty()  -> ChatMessage(Role.Assistant, out)
                    rc < 0            -> ChatMessage(Role.Assistant, "⚠️ ${Rc.message(rc)}")
                    else              -> ChatMessage(Role.Assistant, "")
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                if (sb.isNotEmpty()) _messages.value = _messages.value + ChatMessage(Role.Assistant, sb.toString())
                throw c
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(Role.Assistant, "⚠️ ${e.message}")
            } finally {
                _streamingText.value = ""
                _status.value = if (handle > 0L) Status.Ready else Status.Idle
                releaseWakeLock()
                updateNotification()
            }
        }
    }

    fun cancelGeneration() {
        val h = handle
        if (h != 0L) runCatching { NativeBridge.cancel(h) } // trip native flag now (<1 token)
        genJob?.cancel()
    }

    fun clearConversation() {
        if (_status.value == Status.Generating) cancelGeneration()
        _messages.value = emptyList()
        _streamingText.value = ""
    }

    private fun buildRequestJson(convo: List<ChatMessage>): String {
        val arr = JSONArray()
        if (systemPrompt.isNotBlank()) {
            arr.put(JSONObject().put("role", Role.System.wire).put("content", systemPrompt))
        }
        convo.forEach { m -> arr.put(JSONObject().put("role", m.role.wire).put("content", m.text)) }
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
        private const val DEFAULT_THREADS = 4

        fun start(context: Context) {
            context.startForegroundService(Intent(context, InferenceService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, InferenceService::class.java).apply { action = ACTION_STOP })
        }
    }
}
