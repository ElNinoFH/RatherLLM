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
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service that owns the lifetime of the native ratherllm engine and
 * keeps the process in a high-importance bucket so HyperOS does not reclaim it.
 *
 * Stage 1: the native engine is a stub, so a load attempt resolves to
 * [Status.Error] with an honest "engine not implemented" message. The service
 * scaffolding (FGS, notification, wake lock, StateFlow, streaming) is fully in
 * place so Stage 2 only has to fill in the native implementation.
 */
class InferenceService : Service() {

    enum class Status { Idle, Loading, Ready, Generating, Error }

    inner class LocalBinder : Binder() {
        val service: InferenceService get() = this@InferenceService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var modelPath: String? = null
        private set

    @Volatile
    private var handle: Long = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    // ---- Service lifecycle --------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfClean()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification("Starting…"))
        intent?.getStringExtra(EXTRA_MODEL_PATH)?.takeIf { it.isNotBlank() }?.let { path ->
            serviceScope.launch { loadModel(path) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        unloadModel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- Engine control -----------------------------------------------------

    /** Load [path] into the native engine. Safe to call repeatedly. */
    suspend fun loadModel(path: String) {
        if (!NativeBridge.isLibraryLoaded) {
            fail("native library libratherllm.so failed to load: ${NativeBridge.loadError}")
            return
        }
        unloadModel()
        _status.value = Status.Loading
        lastError = null
        modelPath = path
        updateNotification("Loading model…")

        val rc = withContext(Dispatchers.Default) {
            runCatching {
                NativeBridge.loadModel(
                    path = path,
                    nCtx = DEFAULT_CTX,
                    nThreads = DEFAULT_THREADS,
                    nThreadsBatch = DEFAULT_THREADS,
                    nGpuLayers = 0,
                    useMlock = false,
                )
            }.getOrElse { -1L }
        }

        if (rc > 0L) {
            handle = rc
            _status.value = Status.Ready
            updateNotification("Ready")
        } else {
            handle = 0L
            fail(Rc.message(rc.toInt()))
        }
    }

    fun unloadModel() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            runCatching { NativeBridge.freeModel(h) }
        }
        if (_status.value != Status.Error) _status.value = Status.Idle
    }

    val isReady: Boolean get() = handle > 0L && _status.value == Status.Ready

    /**
     * Stream a reply for [messages]. Cold flow: a new generation runs per
     * collection. Cancelling the collection cooperatively cancels native decode.
     */
    fun streamTokens(
        messages: List<ChatMessage>,
        params: GenParams = GenParams(),
    ): Flow<TokenChunk> = callbackFlow {
        val h = handle
        if (h <= 0L) {
            close(IllegalStateException("no model loaded"))
            return@callbackFlow
        }

        _status.value = Status.Generating
        acquireWakeLock()
        updateNotification("Generating…")

        val request = buildRequestJson(messages, params)
        val callback = TokenCallback { piece ->
            trySend(TokenChunk(piece, final = false)).isSuccess
        }

        val worker = launch(Dispatchers.Default) {
            val rc = runCatching { NativeBridge.generate(h, request, callback) }
                .getOrElse { t -> close(t); return@launch }
            if (rc < 0) {
                close(RuntimeException(Rc.message(rc)))
            } else {
                trySend(TokenChunk("", final = true))
                close()
            }
        }

        awaitClose {
            runCatching { NativeBridge.cancel(h) }
            worker.cancel()
            releaseWakeLock()
            if (_status.value == Status.Generating) {
                _status.value = if (handle > 0L) Status.Ready else Status.Idle
            }
        }
    }

    private fun buildRequestJson(messages: List<ChatMessage>, p: GenParams): String {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role.wire).put("content", m.text))
        }
        val params = JSONObject()
            .put("maxTokens", p.maxTokens)
            .put("temperature", p.temperature.toDouble())
            .put("topK", p.topK)
            .put("topP", p.topP.toDouble())
            .put("minP", p.minP.toDouble())
            .put("repeatPenalty", p.repeatPenalty.toDouble())
            .put("seed", p.seed)
        return JSONObject().put("messages", arr).put("params", params).toString()
    }

    private fun stopSelfClean() {
        unloadModel()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        lastError = message
        _status.value = Status.Error
        updateNotification("Error: $message")
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
            CHANNEL_ID,
            "On-device AI Inference",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the local LLM engine resident and responsive."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val stopIntent = Intent(this, InferenceService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ratherllm engine")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { contentPi?.let { setContentIntent(it) } }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        const val CHANNEL_ID = "ratherllm_inference"
        const val NOTIFICATION_ID = 0x524C // "RL"
        const val WAKELOCK_TAG = "ratherllm:decode"
        private const val WAKELOCK_TIMEOUT_MS = 10L * 60L * 1000L

        const val ACTION_STOP = "com.kotlin.ratherllm.action.STOP_ENGINE"
        const val EXTRA_MODEL_PATH = "com.kotlin.ratherllm.extra.MODEL_PATH"

        private const val DEFAULT_CTX = 4096
        private const val DEFAULT_THREADS = 4

        fun start(context: Context, modelPath: String) {
            val intent = Intent(context, InferenceService::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, InferenceService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
