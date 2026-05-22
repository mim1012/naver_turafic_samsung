package com.navertraffic.samsung.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.navertraffic.samsung.BuildConfig
import com.navertraffic.samsung.DeviceCommandManager
import com.navertraffic.samsung.data.AndroidServerApiClient
import com.navertraffic.samsung.data.DeviceHeartbeat
import com.navertraffic.samsung.data.DeviceIdentity
import com.navertraffic.samsung.data.DeviceRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RemoteControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "원격 제어 대기 중"
        startForeground(NOTIF_ID, buildNotification(message))
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { pollOnce() }
                    .onFailure { Log.w(TAG, "remote control poll failed: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollOnce() {
        val config = ConfigStore(this)
        if (!config.isConfigured()) return

        val identity = DeviceIdentity.parse(config.deviceName) ?: return
        val serverUrl = config.serverUrl.ifBlank { BuildConfig.DEFAULT_SERVER_URL }.trim()
        if (serverUrl.isBlank()) return

        val apiKey = config.apiKey.ifBlank { BuildConfig.DEVICE_API_TOKEN }.trim().takeIf { it.isNotBlank() }
        val client = AndroidServerApiClient(serverUrl, apiKey)
        val commandManager = DeviceCommandManager(
            context = this,
            identity = identity,
            groupControlClient = client,
            serverUrl = serverUrl,
            apiKey = apiKey,
            log = { Log.i(TAG, it) },
        )
        val state = DeviceRuntimeState.IDLE
        val response = client.heartbeat(
            DeviceHeartbeat(
                deviceName = identity.rawName,
                groupId = identity.groupId,
                role = identity.role,
                state = state,
                taskCount = 0,
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
            ),
        )
        commandManager.handleDeviceCommand(response, state)
    }

    private fun buildNotification(message: String): Notification {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Samsung Traffic Bot")
            .setContentText(message)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote Control", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Samsung Traffic Bot admin control channel"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "remote_control"
        const val NOTIF_ID = 1002
        const val EXTRA_MESSAGE = "message"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val TAG = "RemoteControlService"

        fun start(context: Context, message: String = "원격 제어 대기 중") {
            val intent = Intent(context, RemoteControlService::class.java)
                .putExtra(EXTRA_MESSAGE, message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
