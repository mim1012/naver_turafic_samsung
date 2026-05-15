package com.navertraffic.samsung.ui

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.navertraffic.samsung.AppUpdateManager
import com.navertraffic.samsung.BuildConfig
import com.navertraffic.samsung.R
import com.navertraffic.samsung.boss.BossController
import com.navertraffic.samsung.data.AccountLeaseClient
import com.navertraffic.samsung.data.AndroidServerApiClient
import com.navertraffic.samsung.data.DeviceIdentity
import com.navertraffic.samsung.data.GroupControlClient
import com.navertraffic.samsung.data.NoopAccountLeaseClient
import com.navertraffic.samsung.data.NoopGroupControlClient
import com.navertraffic.samsung.data.CookieStorageClient
import com.navertraffic.samsung.data.NoopCookieStorageClient
import com.navertraffic.samsung.data.NoopStrategyTaskLeaseClient
import com.navertraffic.samsung.data.SupabaseApiClient
import com.navertraffic.samsung.data.StrategyTaskLeaseClient
import com.navertraffic.samsung.data.StrategyTaskReport
import com.navertraffic.samsung.data.StrategyTaskResult
import com.navertraffic.samsung.strategy.StrategyAResult
import com.navertraffic.samsung.soldier.IpRotationManager
import com.navertraffic.samsung.soldier.SoldierController
import com.navertraffic.samsung.strategy.BotStrategy
import com.navertraffic.samsung.strategy.DryRunBrowserSession
import com.navertraffic.samsung.strategy.ExternalSamsungBrowserSession
import com.navertraffic.samsung.strategy.SamsungBrowserStrategyA
import com.navertraffic.samsung.strategy.SamsungBrowserStrategyG
import com.navertraffic.samsung.strategy.StrategyVariant
import com.navertraffic.samsung.strategy.SamsungWebViewManager
import com.navertraffic.samsung.strategy.SecondKeywordStore
import com.navertraffic.samsung.strategy.StrategyATask
import com.navertraffic.samsung.strategy.StrategyGTask
import com.navertraffic.samsung.strategy.WebViewBrowserSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var etDeviceName: EditText
    private lateinit var etNaverId: EditText
    private lateinit var etNaverPassword: EditText
    private lateinit var tvLog: TextView
    private lateinit var configPanel: android.view.View
    private lateinit var statusBar: android.view.View
    private lateinit var webViewManager: SamsungWebViewManager
    private lateinit var webViewContainer: android.view.ViewGroup

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etDeviceName = findViewById(R.id.etDeviceName)
        etNaverId = findViewById(R.id.etNaverId)
        etNaverPassword = findViewById(R.id.etNaverPassword)
        tvLog = findViewById(R.id.tvLog)
        configPanel = findViewById(R.id.configPanel)
        statusBar = findViewById(R.id.statusBar)
        val webViewView = findViewById<WebView>(R.id.webView)
        webViewContainer = webViewView.parent as android.view.ViewGroup
        webViewManager = SamsungWebViewManager(webViewView, ::appendLog).also {
            it.initialize()
        }

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            )
        }

        loadSavedConfig()
        applyLaunchExtras()
        applyDebugDefaults()

        findViewById<Button>(R.id.btnRunA).setOnClickListener {
            runBot()
        }

        if (!getBoolExtra(EXTRA_AUTO_RUN)) {
            val config = ConfigStore(this)
            val hasCredentials = etNaverId.text.isNotBlank() && etNaverPassword.text.isNotBlank()
            val hasSession = webViewManager.hasCookieSession()
            if (config.isConfigured() && (hasCredentials || hasSession)) {
                appendLog("저장된 설정 확인 → 자동 시작")
                etDeviceName.post { checkUpdateAndRun() }
            } else if (hasCredentials || hasSession) {
                appendLog("크레덴셜 준비됨 — 버튼을 눌러 시작")
            }
        }
    }

    private fun applyDebugDefaults() {
        if (etDeviceName.text.isBlank()) {
            etDeviceName.setText(BuildConfig.DEBUG_DEVICE_NAME)
        }
        if (etNaverId.text.isBlank() && etNaverPassword.text.isBlank()) {
            val saved = webViewManager.loadCredentials(this)
            if (saved != null) {
                etNaverId.setText(saved.first)
                etNaverPassword.setText(saved.second)
                appendLog("네이버 계정 자동 복원됨")
            } else if (BuildConfig.DEBUG_NAVER_ID.isNotBlank()) {
                etNaverId.setText(BuildConfig.DEBUG_NAVER_ID)
                etNaverPassword.setText(BuildConfig.DEBUG_NAVER_PW)
                appendLog("네이버 계정 빌드 기본값 적용됨")
            }
        }
    }

    private fun applyLaunchExtras() {
        intent.getStringExtra(EXTRA_DEVICE_NAME)?.let { etDeviceName.setText(it) }
        intent.getStringExtra(EXTRA_NAVER_ID)?.let { etNaverId.setText(it) }
        intent.getStringExtra(EXTRA_NAVER_PASSWORD)?.let { etNaverPassword.setText(it) }
        if (getBoolExtra(EXTRA_AUTO_RUN)) {
            appendLog("autoRun 수신")
            etDeviceName.post { checkUpdateAndRun() }
        }
    }

    private fun enterRunningMode(label: String = "전략 실행 중") {
        configPanel.visibility = android.view.View.GONE
        statusBar.visibility = android.view.View.VISIBLE
        startForegroundService(
            android.content.Intent(this, BotKeepAliveService::class.java).apply {
                putExtra(BotKeepAliveService.EXTRA_MESSAGE, label)
            },
        )
    }

    private fun exitRunningMode() {
        stopService(android.content.Intent(this, BotKeepAliveService::class.java))
        configPanel.visibility = android.view.View.VISIBLE
        statusBar.visibility = android.view.View.GONE
    }

    override fun onPause() {
        super.onPause()
        webViewManager.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webViewManager.resumeTimers()
    }

    private fun checkUpdateAndRun() {
        lifecycleScope.launch {
            val updated = AppUpdateManager(this@MainActivity, ::appendLog)
                .checkAndUpdate(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
            if (!updated) runBot()
        }
    }

    private fun runBot() {
        runStrategyG()
    }

    private fun loadSavedConfig() {
        val config = ConfigStore(this)
        if (etDeviceName.text.isBlank() && config.deviceName.isNotBlank()) etDeviceName.setText(config.deviceName)
    }

    // ── Strategy A ───────────────────────────────────────────────────────────

    private fun runStrategyA() {
        val deviceName = etDeviceName.text.toString().trim()
        DeviceIdentity.validateInput(deviceName)?.let { appendLog(it); return }

        val identity = DeviceIdentity.parse(deviceName) ?: return
        val rotateEvery = BuildConfig.ROTATE_EVERY
        val drainWaitMs = BuildConfig.ROTATION_DRAIN_WAIT_SEC * 1000L
        val loopCount = getIntExtra(EXTRA_LOOP_COUNT, BuildConfig.DEBUG_LOOP_COUNT).coerceIn(1, 1_000)
        val dryRun = getBoolExtra(EXTRA_DRY_RUN)
        val externalBrowser = getBoolExtra(EXTRA_EXTERNAL_BROWSER)
        val variant = when (intent.getStringExtra(EXTRA_STRATEGY_VARIANT)?.uppercase()) {
            "B" -> StrategyVariant.B
            "C" -> StrategyVariant.C
            else -> StrategyVariant.A
        }
        appendLog("전략 A 변형: $variant")

        val keyword = intent.getStringExtra(EXTRA_KEYWORD) ?: "고양이 캣타워"
        val secondKeyword = intent.getStringExtra(EXTRA_SECOND_KEYWORD) ?: "오볼로 미니 캣타워 낮은 소형 고양이 켓타워 먼치킨 뚱냥이 노묘"
        val linkUrl = intent.getStringExtra(EXTRA_LINK_URL) ?: "https://m.smartstore.naver.com/o-volo/products/10866518505"
        val mid = intent.getStringExtra(EXTRA_MID) ?: "88411024518"
        val productTitle = intent.getStringExtra(EXTRA_PRODUCT_TITLE) ?: mid
        val tailKeyword = intent.getStringExtra(EXTRA_TAIL_KEYWORD) ?: keyword.split(" ").last()
        val fallbackTask = StrategyATask(
            keyword = keyword,
            secondKeyword = secondKeyword,
            linkUrl = linkUrl,
            mid = mid,
            productTitle = productTitle,
            tailKeyword = tailKeyword,
        )

        val strategyImpl = if (dryRun) {
            appendLog("DRY_RUN 모드: URL 흐름만 검증")
            SamsungBrowserStrategyA(DryRunBrowserSession(::appendLog), stepDelayMs = 0, productDelayMs = 0, useMidClick = false)
        } else if (externalBrowser) {
            appendLog("외부 Samsung Internet 모드")
            SamsungBrowserStrategyA(ExternalSamsungBrowserSession(applicationContext, ::appendLog), stepDelayMs = 1_200, productDelayMs = 2_500)
        } else {
            SamsungBrowserStrategyA(WebViewBrowserSession(webViewManager), variant = variant)
        }
        val botStrategy = BotStrategy.A(strategyImpl)
        val serverClient = buildServerClient()
        if (serverClient.taskLeaseClient is NoopStrategyTaskLeaseClient) appendLog("Supabase URL 없음: 로컬 smoke 모드")
        else appendLog("Supabase 연동 모드 활성")
        enterRunningMode("전략 A 실행 중")

        lifecycleScope.launch {
            val taskLease = runCatching {
                serverClient.taskLeaseClient.leaseTask(identity.rawName, identity.role, "A", APP_VERSION)
            }.onFailure { appendLog("상품 task lease 실패: ${it.message}") }.getOrNull()
            val task = taskLease?.task ?: fallbackTask
            if (taskLease == null) appendLog("상품 task 없음: 하드코딩 smoke 상품 사용")
            else appendLog("상품 task lease 획득: ${task.productTitle ?: task.linkUrl}")

            val bossController = if (identity.isBoss) BossController(
                identity = identity,
                botStrategy = botStrategy,
                ipRotationManager = IpRotationManager(),
                accountLeaseClient = serverClient.accountLeaseClient,
                groupControlClient = serverClient.groupControlClient,
                rotateEveryGroupTasks = rotateEvery,
                rotationDrainWaitMs = drainWaitMs,
                log = ::appendLog,
                beforeRotate = if (!dryRun && !externalBrowser) ({
                    webViewManager.clearPage()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.sec.android.app.sbrowser")).waitFor() }
                    }
                    appendLog("삼성 인터넷 강제 종료 완료")
                }) else null,
            ) else null

            val soldierController = if (identity.isSoldier) SoldierController(
                identity = identity,
                botStrategy = botStrategy,
                accountLeaseClient = serverClient.accountLeaseClient,
                groupControlClient = serverClient.groupControlClient,
                log = ::appendLog,
            ) else null

            val keywordCombos = SecondKeywordStore.generateCombinations(
                source = task.secondKeyword,
                requiredWord = task.keyword,
                count = 5,
            )
            appendLog("2차 검색어 ${keywordCombos.size}개 조합 생성 (필수: ${task.keyword})")
            keywordCombos.forEachIndexed { i, k -> appendLog("  [${i + 1}] $k") }

            val excludedCombos = mutableSetOf<String>()
            var comboIndex = 0
            var successCount = 0

            repeat(loopCount) { index ->
                val available = keywordCombos.filter { it !in excludedCombos }
                if (available.isEmpty()) {
                    appendLog("모든 2차 검색어 조합 제외됨: 종료")
                    return@launch
                }
                val secondKeywordNow = available[comboIndex % available.size]
                comboIndex++
                val currentTask = task.copy(secondKeyword = secondKeywordNow)

                if (!dryRun && !externalBrowser) {
                    val naverId = etNaverId.text.toString().trim()
                    val naverPw = etNaverPassword.text.toString()
                    if (!ensureNaverLogin(naverId, naverPw, serverClient.cookieClient, identity.rawName)) {
                        exitRunningMode()
                        return@launch
                    }
                    if (index == 0) webViewManager.saveCredentials(this@MainActivity, naverId, naverPw)
                }

                appendLog("A전략 반복 ${index + 1}/$loopCount [${available.indexOf(secondKeywordNow) + 1}/${available.size}번 조합]")
                val result: StrategyAResult = bossController?.runOnce(currentTask)
                    ?: soldierController?.runOnce(currentTask)
                    ?: StrategyAResult(success = false, message = "no_controller")

                if (!dryRun && !externalBrowser && webViewManager.rendererGone) {
                    appendLog("렌더러 OOM 감지: WebView 재생성 중")
                    webViewManager.rebuildWebView(webViewContainer)
                    val naverId = etNaverId.text.toString().trim()
                    val naverPw = etNaverPassword.text.toString()
                    if (!ensureNaverLogin(naverId, naverPw, serverClient.cookieClient, identity.rawName)) {
                        exitRunningMode()
                        return@launch
                    }
                }

                if (result.success) successCount += 1
                if (!result.success && result.message?.startsWith("MID product not found") == true) {
                    excludedCombos.add(secondKeywordNow)
                    appendLog("MID 없음 → 2차 검색어 제외: $secondKeywordNow")
                }

                runCatching {
                    serverClient.taskLeaseClient.reportTask(
                        StrategyTaskReport(
                            taskLeaseId = taskLease?.taskLeaseId,
                            deviceName = identity.rawName,
                            result = if (result.success) StrategyTaskResult.SUCCESS else StrategyTaskResult.FAILED,
                            message = if (result.success) null else result.message,
                        ),
                    )
                }.onFailure { appendLog("상품 task report 실패: ${it.message}") }

                if (!dryRun && !externalBrowser && (index + 1) % 30 == 0 && index + 1 < loopCount) {
                    appendLog("주기적 WebView 재생성 (${index + 1}회차 이후)")
                    webViewManager.rebuildWebView(webViewContainer)
                    val naverId = etNaverId.text.toString().trim()
                    val naverPw = etNaverPassword.text.toString()
                    if (!ensureNaverLogin(naverId, naverPw, serverClient.cookieClient, identity.rawName)) {
                        exitRunningMode()
                        return@launch
                    }
                }
            }
            appendLog("A전략 반복 완료: ${successCount}/$loopCount 성공")
            exitRunningMode()
        }
    }

    // ── Strategy G ───────────────────────────────────────────────────────────

    private fun runStrategyG() {
        val deviceName = etDeviceName.text.toString().trim()
        DeviceIdentity.validateInput(deviceName)?.let { appendLog(it); return }

        val identity = DeviceIdentity.parse(deviceName) ?: return
        val rotateEvery = BuildConfig.ROTATE_EVERY
        val drainWaitMs = BuildConfig.ROTATION_DRAIN_WAIT_SEC * 1000L
        val config = ConfigStore(this)
        val loopCount = getIntExtra(EXTRA_LOOP_COUNT, config.loopCount).coerceIn(1, 1_000)

        config.save(
            deviceName = deviceName,
            loopCount = loopCount,
        )
        val dryRun = getBoolExtra(EXTRA_DRY_RUN)

        // G전략: Chrome 137 UA 적용
        if (!dryRun) {
            webViewManager.setUserAgent(SamsungWebViewManager.CHROME_137_UA)
            appendLog("G전략 UA 적용: Chrome 137 Mobile")
        }

        val fallbackTask = StrategyGTask(
            keyword = intent.getStringExtra(EXTRA_KEYWORD) ?: "나이키 운동화",
            keywordName = intent.getStringExtra(EXTRA_KEYWORD_NAME) ?: "나이키 에어맥스 운동화 스니커즈",
            linkUrl = intent.getStringExtra(EXTRA_LINK_URL) ?: "",
            mid = intent.getStringExtra(EXTRA_MID) ?: "",
            productTitle = intent.getStringExtra(EXTRA_PRODUCT_TITLE),
        )

        val strategyImpl = if (dryRun) {
            appendLog("DRY_RUN 모드: URL 흐름만 검증")
            SamsungBrowserStrategyG(DryRunBrowserSession(::appendLog))
        } else {
            SamsungBrowserStrategyG(webViewManager)
        }
        val botStrategy = BotStrategy.G(strategyImpl)
        val serverClient = buildServerClient()
        if (serverClient.taskLeaseClient is NoopStrategyTaskLeaseClient) appendLog("Supabase URL 없음: 로컬 smoke 모드")
        else appendLog("Supabase 연동 모드 활성")
        enterRunningMode("전략 G 실행 중")

        lifecycleScope.launch {
            val bossController = if (identity.isBoss) BossController(
                identity = identity,
                botStrategy = botStrategy,
                ipRotationManager = IpRotationManager(),
                accountLeaseClient = serverClient.accountLeaseClient,
                groupControlClient = serverClient.groupControlClient,
                rotateEveryGroupTasks = rotateEvery,
                rotationDrainWaitMs = drainWaitMs,
                log = ::appendLog,
                beforeRotate = if (!dryRun) ({
                    webViewManager.clearPage()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.sec.android.app.sbrowser")).waitFor() }
                    }
                    appendLog("삼성 인터넷 강제 종료 완료")
                }) else null,
            ) else null

            val soldierController = if (identity.isSoldier) SoldierController(
                identity = identity,
                botStrategy = botStrategy,
                accountLeaseClient = serverClient.accountLeaseClient,
                groupControlClient = serverClient.groupControlClient,
                log = ::appendLog,
            ) else null

            var successCount = 0
            var taskIndex = 0
            var credentialsSaved = false
            while (taskIndex < loopCount) {
                // 반복마다 트래픽 큐에서 새 작업 가져오기
                val taskLease = runCatching {
                    serverClient.taskLeaseClient.leaseTask(identity.rawName, identity.role, "G", APP_VERSION)
                }.onFailure { appendLog("상품 task lease 실패: ${it.message}") }.getOrNull()
                val task = taskLease?.taskG ?: fallbackTask.takeIf { it.mid.isNotBlank() }

                if (task == null) {
                    appendLog("트래픽 큐 비어있음 — 30초 후 재시도")
                    kotlinx.coroutines.delay(30_000)
                    continue
                }
                appendLog("상품 task 획득: ${task.productTitle ?: task.linkUrl}")

                if (!dryRun) {
                    val naverId = etNaverId.text.toString().trim()
                    val naverPw = etNaverPassword.text.toString()
                    if (!ensureNaverLogin(naverId, naverPw, serverClient.cookieClient, identity.rawName)) {
                        exitRunningMode()
                        return@launch
                    }
                    if (!credentialsSaved) {
                        webViewManager.saveCredentials(this@MainActivity, naverId, naverPw)
                        credentialsSaved = true
                    }
                }

                appendLog("G전략 반복 ${taskIndex + 1}/$loopCount")
                val result: StrategyAResult = bossController?.runOnce(task)
                    ?: soldierController?.runOnce(task)
                    ?: StrategyAResult(success = false, message = "no_controller")

                if (result.message == "group_paused") {
                    appendLog("그룹 로테이션 대기 중 — 10초 후 재시도")
                    kotlinx.coroutines.delay(10_000)
                    continue
                }
                taskIndex++

                if (!dryRun && webViewManager.rendererGone) {
                    appendLog("렌더러 OOM 감지: WebView 재생성 중")
                    webViewManager.rebuildWebView(webViewContainer)
                    webViewManager.setUserAgent(SamsungWebViewManager.CHROME_137_UA)
                    val naverId = etNaverId.text.toString().trim()
                    val naverPw = etNaverPassword.text.toString()
                    if (!ensureNaverLogin(naverId, naverPw, serverClient.cookieClient, identity.rawName)) {
                        exitRunningMode()
                        return@launch
                    }
                }

                if (result.success) successCount += 1

                runCatching {
                    serverClient.taskLeaseClient.reportTask(
                        StrategyTaskReport(
                            taskLeaseId = taskLease?.taskLeaseId,
                            deviceName = identity.rawName,
                            result = if (result.success) StrategyTaskResult.SUCCESS else StrategyTaskResult.FAILED,
                            message = if (result.success) null else result.message,
                        ),
                    )
                }.onFailure { appendLog("상품 task report 실패: ${it.message}") }
            }
            appendLog("G전략 반복 완료: ${successCount}/$loopCount 성공")
            exitRunningMode()
        }
    }

    // ── 공통 헬퍼 ───────────────────────────────────────────────────────────

    private suspend fun ensureNaverLogin(
        naverId: String,
        naverPw: String,
        cookieClient: CookieStorageClient,
        deviceName: String,
    ): Boolean {
        if (webViewManager.hasCookieSession()) return true

        // IP 로테이션 이후: 서버 저장 쿠키 복원 시도
        val saved = runCatching { cookieClient.loadCookies(deviceName) }.getOrNull()
        if (!saved.isNullOrBlank()) {
            appendLog("서버 저장 쿠키 복원 시도")
            webViewManager.setNaverCookie(saved)
            kotlinx.coroutines.delay(500)
            if (webViewManager.hasCookieSession()) {
                appendLog("쿠키 복원 성공 — 로그인 생략")
                return true
            }
            appendLog("쿠키 복원 실패 — 재로그인 진행")
        }

        // 전체 로그인
        val loginOk = webViewManager.loginNaver(loginId = naverId, password = naverPw)
        if (!loginOk) {
            appendLog("네이버 로그인 실패: 작업 중단")
            return false
        }

        // 로그인 성공 → 쿠키 서버 저장
        val cookies = webViewManager.extractNaverCookies()
        if (cookies.isNotBlank()) {
            runCatching { cookieClient.saveCookies(deviceName, cookies) }
                .onSuccess { appendLog("네이버 쿠키 서버 저장 완료") }
                .onFailure { appendLog("쿠키 서버 저장 실패: ${it.message}") }
        }
        return true
    }

    private fun buildServerClient(): ServerClients {
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_KEY
        if (url.isBlank()) {
            return ServerClients(
                NoopAccountLeaseClient(),
                NoopGroupControlClient(),
                NoopStrategyTaskLeaseClient(),
                NoopCookieStorageClient(),
            )
        }
        val client = SupabaseApiClient(url, key)
        return ServerClients(NoopAccountLeaseClient(), client, client, client)
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logLines.addFirst("[$time] $message")
        while (logLines.size > 80) logLines.removeLast()
        runOnUiThread { tvLog.text = logLines.joinToString("\n") }
    }

    private data class ServerClients(
        val accountLeaseClient: AccountLeaseClient,
        val groupControlClient: GroupControlClient,
        val taskLeaseClient: StrategyTaskLeaseClient,
        val cookieClient: CookieStorageClient,
    )

    private fun getBoolExtra(key: String): Boolean {
        return when (val v = intent.extras?.get(key)) {
            is Boolean -> v
            is String -> v.lowercase() == "true"
            else -> false
        }
    }

    private fun getIntExtra(key: String, default: Int): Int {
        return when (val v = intent.extras?.get(key)) {
            is Int -> v
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

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
        private const val EXTRA_KEYWORD = "keyword"
        private const val EXTRA_SECOND_KEYWORD = "secondKeyword"
        private const val EXTRA_KEYWORD_NAME = "keywordName"
        private const val EXTRA_LINK_URL = "linkUrl"
        private const val EXTRA_MID = "mid"
        private const val EXTRA_PRODUCT_TITLE = "productTitle"
        private const val EXTRA_TAIL_KEYWORD = "tailKeyword"
        private const val EXTRA_STRATEGY_VARIANT = "strategyVariant"
        private const val EXTRA_STRATEGY = "strategy"
    }
}
