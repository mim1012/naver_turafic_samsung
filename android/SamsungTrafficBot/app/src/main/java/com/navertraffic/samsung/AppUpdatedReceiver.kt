package com.navertraffic.samsung

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.navertraffic.samsung.ui.MainActivity

class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("autoRun", true)
        }
        runCatching { context.startActivity(launchIntent) }
    }
}
