package com.navertraffic.samsung.strategy

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser

class SamsungBrowserLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSamsungBrowser()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSamsungBrowser()
    }

    private fun openSamsungBrowser() {
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrBlank()) {
            val activityName = resolveSamsungBrowserActivity()
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    component = ComponentName(SamsungWebViewManager.SAMSUNG_PACKAGE, activityName)
                    putExtra(Browser.EXTRA_APPLICATION_ID, packageName)
                    putExtra(Browser.EXTRA_CREATE_NEW_TAB, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    // PackageManager로 실제 VIEW 핸들러를 먼저 조회, 없으면 알려진 activity명 순서대로 시도
    private fun resolveSamsungBrowserActivity(): String {
        val resolved = packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://m.naver.com")),
                0,
            )
            .firstOrNull { it.activityInfo.packageName == SamsungWebViewManager.SAMSUNG_PACKAGE }
            ?.activityInfo?.name
        if (resolved != null) return resolved
        // Fallback: known activity names by Samsung Internet version
        for (name in KNOWN_ACTIVITIES) {
            if (isActivityPresent(name)) return name
        }
        return KNOWN_ACTIVITIES.first()
    }

    private fun isActivityPresent(activityName: String): Boolean {
        return try {
            packageManager.getActivityInfo(
                ComponentName(SamsungWebViewManager.SAMSUNG_PACKAGE, activityName), 0,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val EXTRA_URL = "com.navertraffic.samsung.extra.URL"
        // Samsung Internet activity names across versions (newest first)
        private val KNOWN_ACTIVITIES = listOf(
            "com.sec.android.app.sbrowser.ActivityMCloud",   // older builds (this S7)
            "com.sec.android.app.sbrowser.SBrowserMainActivity", // newer builds
        )
    }
}
