package com.navertraffic.samsung.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class BotKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var partialWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val visibilityPulse = object : Runnable {
        override fun run() {
            bringActivityToFront()
            handler.postDelayed(this, VISIBLE_PULSE_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "실행 중"
        acquireWakeLocks()
        startForeground(NOTIF_ID, buildNotification(message))
        startVisibilityPulse()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        bringActivityToFront()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(visibilityPulse)
        releaseWakeLocks()
        super.onDestroy()
    }

    private fun buildNotification(message: String): Notification {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_KEEP_VISIBLE
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
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
            NotificationChannel(CHANNEL_ID, "Bot Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Samsung Traffic Bot background status"
                setShowBadge(false)
            },
        )
    }

    private fun startVisibilityPulse() {
        handler.removeCallbacks(visibilityPulse)
        handler.postDelayed(visibilityPulse, VISIBLE_PULSE_MS)
    }

    private fun bringActivityToFront() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_KEEP_VISIBLE
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (partialWakeLock?.isHeld != true) {
            partialWakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SamsungTrafficBot:service")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (screenWakeLock?.isHeld != true) {
            screenWakeLock = powerManager
                .newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SamsungTrafficBot:screen",
                )
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseWakeLocks() {
        partialWakeLock?.takeIf { it.isHeld }?.release()
        partialWakeLock = null
        screenWakeLock?.takeIf { it.isHeld }?.release()
        screenWakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "bot_status"
        const val NOTIF_ID = 1001
        const val EXTRA_MESSAGE = "message"
        private const val VISIBLE_PULSE_MS = 10_000L
    }
}
