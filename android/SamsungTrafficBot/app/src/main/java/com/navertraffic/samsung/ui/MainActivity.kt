package com.navertraffic.samsung.ui

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.navertraffic.samsung.R
import com.navertraffic.samsung.boss.BossController
import com.navertraffic.samsung.data.AccountLeaseClient
import com.navertraffic.samsung.data.AndroidServerApiClient
import com.navertraffic.samsung.data.DeviceIdentity
import com.navertraffic.samsung.data.GroupControlClient
import com.navertraffic.samsung.data.NoopAccountLeaseClient
import com.navertraffic.samsung.data.NoopGroupControlClient
import com.navertraffic.samsung.data.NoopStrategyTaskLeaseClient
import com.navertraffic.samsung.data.StrategyTaskLeaseClient
import com.navertraffic.samsung.data.StrategyTaskReport
import com.navertraffic.samsung.data.StrategyTaskResult
import com.navertraffic.samsung.soldier.IpRotationManager
import com.navertraffic.samsung.soldier.SoldierController
import com.navertraffic.samsung.strategy.DryRunBrowserSession
import com.navertraffic.samsung.strategy.ExternalSamsungBrowserSession
import com.navertraffic.samsung.strategy.SamsungBrowserStrategyA
import com.navertraffic.samsung.strategy.SamsungWebViewManager
import com.navertraffic.samsung.strategy.SecondKeywordStore
import com.navertraffic.samsung.strategy.StrategyATask
import com.navertraffic.samsung.strategy.WebViewBrowserSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var etDeviceName: EditText
    private lateinit var etRotateEvery: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etNaverId: EditText
    private lateinit var etNaverPassword: EditText
    private lateinit var tvLog: TextView
    private lateinit var webViewManager: SamsungWebViewManager
    private lateinit var secondKeywordStore: SecondKeywordStore

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etDeviceName = findViewById(R.id.etDeviceName)
        etRotateEvery = findViewById(R.id.etRotateEvery)
        etServerUrl = findViewById(R.id.etServerUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etNaverId = findViewById(R.id.etNaverId)
        etNaverPassword = findViewById(R.id.etNaverPassword)
        tvLog = findViewById(R.id.tvLog)
        secondKeywordStore = SecondKeywordStore(this)
        webViewManager = SamsungWebViewManager(findViewById<WebView>(R.id.webView), ::appendLog).also {
            it.initialize()
        }

        applyLaunchExtras()

        findViewById<Button>(R.id.btnRunA).setOnClickListener {
            runStrategyASmoke()
        }
    }

    private fun applyLaunchExtras() {
        intent.getStringExtra(EXTRA_DEVICE_NAME)?.let { etDeviceName.setText(it) }
        intent.getStringExtra(EXTRA_ROTATE_EVERY)?.let { etRotateEvery.setText(it) }
        intent.getIntExtra(EXTRA_ROTATE_EVERY, -1)
            .takeIf { it > 0 }
            ?.let { etRotateEvery.setText(it.toString()) }
        intent.getStringExtra(EXTRA_SERVER_URL)?.let { etServerUrl.setText(it) }
        intent.getStringExtra(EXTRA_API_KEY)?.let { etApiKey.setText(it) }
        intent.getStringExtra(EXTRA_NAVER_ID)?.let { etNaverId.setText(it) }
        intent.getStringExtra(EXTRA_NAVER_PASSWORD)?.let { etNaverPassword.setText(it) }
        if (intent.getBooleanExtra(EXTRA_AUTO_RUN, false)) {
            appendLog("ADB autoRun 요청 수신")
            etDeviceName.post { runStrategyASmoke() }
        }
    }

    private fun runStrategyASmoke() {
        val deviceName = etDeviceName.text.toString().trim()
        DeviceIdentity.validateInput(deviceName)?.let {
            appendLog(it)
            return
        }

        val identity = DeviceIdentity.parse(deviceName) ?: return
        val rotateEvery = etRotateEvery.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 5
        val loopCount = intent.getIntExtra(EXTRA_LOOP_COUNT, 1).coerceIn(1, 1_000)
        val dryRun = intent.getBooleanExtra(EXTRA_DRY_RUN, false)
        val externalBrowser = intent.getBooleanExtra(EXTRA_EXTERNAL_BROWSER, true)
        val fallbackTask = StrategyATask(
            keyword = "양갈비",
            secondKeyword = "최상급 어린양으로 만든 양꼬치 양갈비 양고기",
            linkUrl = "https://msearch.shopping.naver.com/product/82095489871",
            mid = "82095489871",
            productTitle = "최상급 어린양으로 만든 양꼬치 양갈비 양고기",
        )

        val strategyA = if (dryRun) {
            appendLog("DRY_RUN 모드: 외부 브라우저/사이트 접속 없이 URL 흐름만 검증")
            SamsungBrowserStrategyA(
                DryRunBrowserSession(::appendLog),
                stepDelayMs = 0,
                productDelayMs = 0,
                useMidClick = false,
            )
        } else if (externalBrowser) {
            appendLog("외부 Samsung Internet 모드: APK는 백그라운드, 브라우저는 포그라운드")
            SamsungBrowserStrategyA(
                ExternalSamsungBrowserSession(applicationContext, ::appendLog),
                stepDelayMs = 1_200,
                productDelayMs = 2_500,
            )
        } else {
            SamsungBrowserStrategyA(
                WebViewBrowserSession(webViewManager),
            )
        }
        val serverClient = buildServerClient()
        if (serverClient.accountLeaseClient is NoopAccountLeaseClient) {
            appendLog("서버 URL 없음: 로컬 smoke 모드")
        } else {
            appendLog("서버 연동 모드: heartbeat/account lease/report 활성")
        }
        lifecycleScope.launch {
            val taskLease = runCatching {
                serverClient.taskLeaseClient.leaseTask(identity.rawName, identity.role, "A", APP_VERSION)
            }.onFailure { appendLog("상품 task lease 실패: ${it.message}") }.getOrNull()
            val task = taskLease?.task ?: fallbackTask
            if (taskLease == null) {
                appendLog("상품 task 없음: 하드코딩 smoke 상품 사용")
            } else {
                appendLog("상품 task lease 획득: ${task.productTitle ?: task.linkUrl}")
            }

            val bossController = if (identity.isBoss) {
                BossController(
                    identity = identity,
                    strategyA = strategyA,
                    ipRotationManager = IpRotationManager(),
                    accountLeaseClient = serverClient.accountLeaseClient,
                    groupControlClient = serverClient.groupControlClient,
                    rotateEveryGroupTasks = rotateEvery,
                    log = ::appendLog,
                )
            } else {
                null
            }
            val soldierController = if (identity.isSoldier) {
                SoldierController(
                    identity = identity,
                    strategyA = strategyA,
                    accountLeaseClient = serverClient.accountLeaseClient,
                    groupControlClient = serverClient.groupControlClient,
                    log = ::appendLog,
                )
            } else {
                null
            }

            var successCount = 0
            repeat(loopCount) { index ->
                val mid = task.mid.orEmpty()
                val secondKeyword = secondKeywordStore.nextKeyword(
                    mid = mid,
                    source = task.secondKeyword,
                )
                if (secondKeyword == null) {
                    appendLog("사용 가능한 2차 검색어 없음: 제외 ${secondKeywordStore.excludedCount(mid)}개")
                    return@launch
                }
                val currentTask = task.copy(secondKeyword = secondKeyword)

                if (!dryRun && !externalBrowser && index == 0) {
                    val loginOk = webViewManager.loginNaver(
                        loginId = etNaverId.text.toString().trim(),
                        password = etNaverPassword.text.toString(),
                    )
                    if (!loginOk) {
                        appendLog("네이버 로그인 실패: 작업 중단")
                        return@launch
                    }
                }
                if (!dryRun) {
                    if (externalBrowser) {
                        moveTaskToBack(true)
                    } else {
                        webViewManager.resetSurface()
                    }
                }

                appendLog("A전략 반복 ${index + 1}/$loopCount 시작")
                appendLog("2차 검색어 선택: $secondKeyword")
                val success = bossController?.runOnce(currentTask) ?: soldierController?.runOnce(currentTask) ?: false
                if (success) successCount += 1
                if (!success && mid.isNotBlank()) {
                    secondKeywordStore.exclude(mid, secondKeyword)
                    appendLog("2차 검색어 제외 등록: $secondKeyword")
                }

                runCatching {
                    serverClient.taskLeaseClient.reportTask(
                        StrategyTaskReport(
                            taskLeaseId = taskLease?.taskLeaseId,
                            deviceName = identity.rawName,
                            result = if (success) StrategyTaskResult.SUCCESS else StrategyTaskResult.FAILED,
                            message = if (success) null else "strategy execution failed or skipped",
                        ),
                    )
                }.onFailure { appendLog("상품 task report 실패: ${it.message}") }
            }
            appendLog("A전략 반복 완료: ${successCount}/$loopCount 성공")
        }
    }

    private fun buildServerClient(): ServerClients {
        val serverUrl = etServerUrl.text.toString().trim()
        if (serverUrl.isBlank()) {
            return ServerClients(
                NoopAccountLeaseClient(),
                NoopGroupControlClient(),
                NoopStrategyTaskLeaseClient(),
            )
        }
        val apiKey = etApiKey.text.toString().trim().takeIf { it.isNotBlank() }
        val client = AndroidServerApiClient(serverUrl, apiKey)
        return ServerClients(client, client, client)
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logLines.addFirst("[$time] $message")
        while (logLines.size > 80) logLines.removeLast()
        runOnUiThread {
            tvLog.text = logLines.joinToString("\n")
        }
    }

    private data class ServerClients(
        val accountLeaseClient: AccountLeaseClient,
        val groupControlClient: GroupControlClient,
        val taskLeaseClient: StrategyTaskLeaseClient,
    )

    companion object {
        private const val TAG = "SamsungTrafficBot"
        private const val APP_VERSION = "0.1.0"
        private const val EXTRA_AUTO_RUN = "autoRun"
        private const val EXTRA_DEVICE_NAME = "deviceName"
        private const val EXTRA_ROTATE_EVERY = "rotateEvery"
        private const val EXTRA_SERVER_URL = "serverUrl"
        private const val EXTRA_API_KEY = "apiKey"
        private const val EXTRA_LOOP_COUNT = "loopCount"
        private const val EXTRA_DRY_RUN = "dryRun"
        private const val EXTRA_EXTERNAL_BROWSER = "externalBrowser"
        private const val EXTRA_NAVER_ID = "naverId"
        private const val EXTRA_NAVER_PASSWORD = "naverPassword"
    }
}
