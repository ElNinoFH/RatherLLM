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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the lifetime of the native ratherllm engine and
 * keeps the process in a high-importance bucket so HyperOS does not reclaim it.
 */
class InferenceService : Service() {

    enum class Status { Idle, Starting, Ready, Error }

    inner class LocalBinder : Binder() {
        val service: InferenceService get() = this@InferenceService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    private val client = RatherLlmClient()
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

        startForegroundCompat(buildNotification("Starting inference engine…"))

        if (started.compareAndSet(false, true)) {
            val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH).orEmpty()
            val useNeuropilot = intent?.getBooleanExtra(EXTRA_USE_NEUROPILOT, true) ?: true
            acquireWakeLock()
            launchEngine(modelPath, useNeuropilot)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        shutdownEngine()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- Public API for bound clients --------------------------------------

    fun streamTokens(prompt: String, params: GenParams = GenParams()): Flow<TokenChunk> =
        client.streamTokens(prompt, params)

    // ---- Engine orchestration ----------------------------------------------

    private fun launchEngine(modelPath: String, useNeuropilot: Boolean) {
        _status.value = Status.Starting
        serviceScope.launch {
            if (!NativeBridge.isLibraryLoaded) {
                fail("native library libratherllm.so failed to load")
                return@launch
            }
            if (modelPath.isBlank()) {
                fail("no model path provided")
                return@launch
            }
            val rc = try {
                NativeBridge.startEngine(modelPath, useNeuropilot)
            } catch (e: Throwable) {
                fail("startEngine threw: ${e.message}")
                return@launch
            }

            if (rc == 0 && NativeBridge.isRunning()) {
                _status.value = Status.Ready
                updateNotification("Engine ready — weights locked resident")
            } else {
                fail("startEngine failed (rc=$rc)")
            }
        }
    }

    private fun shutdownEngine() {
        if (started.compareAndSet(true, false)) {
            runCatching { NativeBridge.stopEngine() }
            releaseWakeLock()
            _status.value = Status.Idle
        }
    }

    private fun stopSelfClean() {
        shutdownEngine()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        lastError = message
        _status.value = Status.Error
        updateNotification("Engine error: $message")
    }

    // ---- Wake lock ----------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS) // bounded so the OS can reclaim a leaked lock
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
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
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
            return // notification updates are suppressed without the runtime grant
        }
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, // minSdk 34 => always available
        )
    }

    companion object {
        const val CHANNEL_ID = "ratherllm_inference"
        const val NOTIFICATION_ID = 0x524C // stable non-zero id ("RL")
        const val WAKELOCK_TAG = "ratherllm:decode"
        private const val WAKELOCK_TIMEOUT_MS = 10L * 60L * 1000L // 10 min

        const val ACTION_STOP = "com.kotlin.ratherllm.action.STOP_ENGINE"
        const val EXTRA_MODEL_PATH = "com.kotlin.ratherllm.extra.MODEL_PATH"
        const val EXTRA_USE_NEUROPILOT = "com.kotlin.ratherllm.extra.USE_NEUROPILOT"

        fun start(context: Context, modelPath: String, useNeuropilot: Boolean = true) {
            val intent = Intent(context, InferenceService::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_USE_NEUROPILOT, useNeuropilot)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, InferenceService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
