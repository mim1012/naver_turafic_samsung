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
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    component = ComponentName(
                        SamsungWebViewManager.SAMSUNG_PACKAGE,
                        "com.sec.android.app.sbrowser.SBrowserMainActivity",
                    )
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

    companion object {
        const val EXTRA_URL = "com.navertraffic.samsung.extra.URL"
    }
}
