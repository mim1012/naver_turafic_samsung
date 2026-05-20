package com.navertraffic.samsung.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.navertraffic.samsung.AppUpdateManager
import com.navertraffic.samsung.BuildConfig
import com.navertraffic.samsung.DeviceCommandManager
import com.navertraffic.samsung.R
import com.navertraffic.samsung.boss.BossController
import com.navertraffic.samsung.data.AccountLease
import com.navertraffic.samsung.data.AccountLeaseReport
import com.navertraffic.samsung.data.AccountLeaseResult
import com.navertraffic.samsung.data.AccountLeaseClient
import com.navertraffic.samsung.data.AndroidServerApiClient
import com.navertraffic.samsung.data.DeviceIdentity
import com.navertraffic.samsung.data.DeviceRuntimeState
import com.navertraffic.samsung.data.GroupControlClient
import com.navertraffic.samsung.data.NoopAccountLeaseClient
import com.navertraffic.samsung.data.NoopGroupControlClient
import com.navertraffic.samsung.data.CookieStorageClient
import com.navertraffic.samsung.data.NoopCookieStorageClient
import com.navertraffic.samsung.data.NoopStrategyTaskLeaseClient
import com.navertraffic.samsung.data.SupabaseApiClient
import com.navertraffic.samsung.data.ProtectionSignal
import com.navertraffic.samsung.data.StrategyTaskLeaseBlockedException
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
import com.navertraffic.samsung.strategy.ProtectionDetector
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
    private lateinit var etServerUrl: EditText
    private lateinit var etNaverId: EditText
    private lateinit var etNaverPassword: EditText
    private lateinit var cbShowNaverPassword: CheckBox
    private lateinit var tvLog: TextView
    private lateinit var configPanel: android.view.View
    private lateinit var statusBar: android.view.View
    private lateinit var webViewManager: SamsungWebViewManager
    private lateinit var webViewContainer: android.view.ViewGroup

    private val logLines = ArrayDeque<String>()
    private val validatedCookieSessions = mutableSetOf<String>()
    private val protectionDetector = ProtectionDetector()
    private val screenshotCapture = com.navertraffic.samsung.strategy.RootedScreenshotCapture()
    private var runWakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var botRunActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etDeviceName = findViewById(R.id.etDeviceName)
        etServerUrl = findViewById(R.id.etServerUrl)
        etNaverId = findViewById(R.id.etNaverId)
        etNaverPassword = findViewById(R.id.etNaverPassword)
        cbShowNaverPassword = findViewById(R.id.cbShowNaverPassword)
        cbShowNaverPassword.setOnCheckedChangeListener { _, checked ->
            setNaverPasswordVisible(checked)
        }
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
        refreshServerAccountFromServer()

        findViewById<Button>(R.id.btnRunA).setOnClickListener {
            runBot()
        }

        if (!getBoolExtra(EXTRA_AUTO_RUN)) {
            val config = ConfigStore(this)
            val hasSession = webViewManager.hasCookieSession()
            val hasServer = resolveServerUrl(config).isNotBlank()
            if (config.isConfigured() && (hasServer || hasSession)) {
                appendLog("저장된 설정 확인 → 자동 시작")
                etDeviceName.post { checkUpdateAndRun() }
            } else if (hasSession) {
                appendLog("쿠키 세션 준비됨 — 버튼을 눌러 시작")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchExtras()
        refreshServerAccountFromServer()
    }

    private fun applyDebugDefaults() {
        if (etDeviceName.text.isBlank()) {
            etDeviceName.setText(BuildConfig.DEBUG_DEVICE_NAME)
        }
        etNaverId.text.clear()
        etNaverPassword.text.clear()
        setNaverCredentialInputsVisible(false)
    }

    private fun refreshServerAccountFromServer() {
        val config = ConfigStore(this)
        val serverUrl = resolveServerUrl(config)
        if (serverUrl.isBlank()) return

        val identity = DeviceIdentity.parse(etDeviceName.text.toString().trim()) ?: return
        val apiKey = resolveApiKey(config)
        lifecycleScope.launch {
            val client = AndroidServerApiClient(serverUrl, apiKey.takeIf { it.isNotBlank() })
            val account = runCatching {
                client.currentAccount(identity.rawName, identity.role, "G", APP_VERSION)
            }.onFailure {
                appendLog("서버 저장 계정 조회 실패: ${it.message}")
            }.getOrNull()

            if (account != null) {
                applyNaverCredentialToUi(account.loginId, account.password)
                appendLog("서버 저장 계정 복원됨: ${account.accountAlias.ifBlank { account.loginId }}")
                return@launch
            }

            appendLog("서버 저장 계정 없음: 관리자에서 ${identity.rawName} 계정을 배정하세요")
        }
    }

    private fun applyNaverCredentialToUi(loginId: String, password: String) {
        etNaverId.setText(loginId)
        etNaverPassword.setText(password)
    }

    private fun setNaverCredentialInputsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        etNaverId.visibility = visibility
        etNaverPassword.visibility = visibility
        cbShowNaverPassword.visibility = visibility
    }

    private fun applyLaunchExtras() {
        val config = ConfigStore(this)
        intent.getStringExtra(EXTRA_DEVICE_NAME)?.let { etDeviceName.setText(it) }
        intent.getStringExtra(EXTRA_SERVER_URL)?.takeIf { it.isNotBlank() }?.let {
            etServerUrl.setText(it)
            config.serverUrl = it
            appendLog("서버 URL 저장됨")
        }
        intent.getStringExtra(EXTRA_API_KEY)?.takeIf { it.isNotBlank() }?.let {
            config.apiKey = it
            appendLog("서버 API 키 저장됨")
        }
        if (getBoolExtra(EXTRA_AUTO_RUN)) {
            appendLog("autoRun 수신")
            etDeviceName.post { checkUpdateAndRun() }
        }
    }

    private fun enterRunningMode(label: String = "전략 실행 중") {
        botRunActive = true
        configPanel.visibility = android.view.View.GONE
        statusBar.visibility = android.view.View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireRunWakeLock()
        startForegroundService(
            android.content.Intent(this, BotKeepAliveService::class.java).apply {
                putExtra(BotKeepAliveService.EXTRA_MESSAGE, label)
            },
        )
    }

    private fun exitRunningMode() {
        botRunActive = false
        stopService(android.content.Intent(this, BotKeepAliveService::class.java))
        releaseRunWakeLock()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configPanel.visibility = android.view.View.VISIBLE
        statusBar.visibility = android.view.View.GONE
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireRunWakeLock() {
        if (runWakeLock?.isHeld == true) return
        runWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SamsungTrafficBot:run")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseRunWakeLock() {
        runWakeLock?.takeIf { it.isHeld }?.release()
        runWakeLock = null
    }

    override fun onPause() {
        super.onPause()
        webViewManager.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webViewManager.resumeTimers()
    }

    override fun onDestroy() {
        releaseRunWakeLock()
        super.onDestroy()
    }

    private fun checkUpdateAndRun() {
        if (botRunActive) {
            appendLog("작업 시작 요청 수신: 이미 실행/대기 중")
            return
        }
        lifecycleScope.launch {
            val config = ConfigStore(this@MainActivity)
            val serverUrl = resolveServerUrl(config)
            val apiKey = resolveApiKey(config)
            val updateManager = AppUpdateManager(this@MainActivity, ::appendLog)
            val updated = if (serverUrl.isNotBlank()) {
                updateManager.checkAndUpdateFromServer(serverUrl, apiKey.takeIf { it.isNotBlank() })
            } else {
                updateManager.checkAndUpdate(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
            }
            if (!updated) runBot()
        }
    }

    private fun runBot() {
        if (botRunActive) {
            appendLog("작업 시작 요청 무시: 이미 실행/대기 중")
            return
        }
        runStrategyG()
    }

    private fun loadSavedConfig() {
        val config = ConfigStore(this)
        if (etDeviceName.text.isBlank() && config.deviceName.isNotBlank()) etDeviceName.setText(config.deviceName)
        if (etServerUrl.text.isBlank()) {
            resolveServerUrl(config).takeIf { it.isNotBlank() }?.let { etServerUrl.setText(it) }
        }
    }

    // ── Strategy A ───────────────────────────────────────────────────────────

    private fun runStrategyA() {
        val deviceName = etDeviceName.text.toString().trim()
        DeviceIdentity.validateInput(deviceName)?.let { appendLog(it); return }

        val identity = DeviceIdentity.parse(deviceName) ?: return
        val config = ConfigStore(this)
        val rotateEvery = BuildConfig.ROTATE_EVERY
        val drainWaitMs = BuildConfig.ROTATION_DRAIN_WAIT_SEC * 1000L
        val loopCount = getIntExtra(EXTRA_LOOP_COUNT, BuildConfig.DEBUG_LOOP_COUNT).coerceIn(1, 1_000)
        config.save(
            deviceName = deviceName,
            loopCount = loopCount,
            serverUrl = resolveServerUrl(config),
        )
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

        val externalSession = if (!dryRun && externalBrowser) {
            ExternalSamsungBrowserSession(applicationContext, ::appendLog)
        } else {
            null
        }
        val strategyImpl = if (dryRun) {
            appendLog("DRY_RUN 모드: URL 흐름만 검증")
            SamsungBrowserStrategyA(DryRunBrowserSession(::appendLog), stepDelayMs = 0, productDelayMs = 0, useMidClick = false)
        } else if (externalSession != null) {
            appendLog("외부 Samsung Internet 모드")
            SamsungBrowserStrategyA(externalSession, stepDelayMs = 1_200, productDelayMs = 2_500)
        } else {
            SamsungBrowserStrategyA(WebViewBrowserSession(webViewManager), variant = variant)
        }
        val botStrategy = BotStrategy.A(strategyImpl)
        val serverClient = buildServerClient()
        logServerMode(serverClient)
        enterRunningMode("전략 A 실행 중")

        lifecycleScope.launch {
            val deviceCommandManager = DeviceCommandManager(
                context = this@MainActivity,
                identity = identity,
                groupControlClient = serverClient.groupControlClient,
                serverUrl = resolveServerUrl(config),
                apiKey = resolveApiKey(config).takeIf { it.isNotBlank() },
                log = ::appendLog,
            )
            var taskLeaseError: Throwable? = null
            val taskLease = runCatching {
                serverClient.taskLeaseClient.leaseTask(identity.rawName, identity.role, "A", APP_VERSION)
            }.onFailure {
                taskLeaseError = it
                if (it is StrategyTaskLeaseBlockedException) {
                    appendLog("그룹 상태 ${it.groupState}: 작업 lease 차단 (${it.reason})")
                } else {
                    appendLog("상품 task lease 실패: ${it.message}")
                }
            }.getOrNull()
            val task = taskLease?.task ?: fallbackTask.takeIf { dryRun }
            if (task == null) {
                val reason = if (taskLeaseError == null) "트래픽 큐 비어있음" else "트래픽 큐 조회 실패"
                appendLog("$reason — 운영 모드에서는 로컬 smoke 상품을 사용하지 않음")
                exitRunningMode()
                return@launch
            }
            if (taskLease == null) appendLog("DRY_RUN task 사용")
            else appendLog("상품 task lease 획득: ${task.productTitle ?: task.linkUrl}")

            val bossController = if (identity.isBoss) BossController(
                identity = identity,
                botStrategy = botStrategy,
                ipRotationManager = IpRotationManager(),
                accountLeaseClient = serverClient.accountLeaseClient,
                groupControlClient = serverClient.groupControlClient,
                deviceCommandHandler = deviceCommandManager,
                rotateEveryGroupTasks = rotateEvery,
                rotationDrainWaitMs = drainWaitMs,
                log = ::appendLog,
                beforeRotate = if (!dryRun && !externalBrowser) ({
                    webViewManager.clearPage()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.sec.android.app.sbrowser"))
                            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroy()
                        }
                    }
                    appendLog("삼성 인터넷 강제 종료 완료")
                }) else null,
            ) else null

            val soldierController = if (identity.isSoldier) SoldierController(
                identity = identity,
                botStrategy = botStrategy,
                accountLeaseClient = serverClient.accountLeaseClient,
                groupControlClient = serverClient.groupControlClient,
                deviceCommandHandler = deviceCommandManager,
                log = ::appendLog,
            ) else null

            suspend fun reportRuntimeState(state: DeviceRuntimeState, message: String? = null) {
                if (bossController != null) {
                    bossController.reportRuntimeState(state, message)
                } else {
                    soldierController?.reportRuntimeState(state, message)
                }
            }

            val heartbeatJob = bossController?.startHeartbeatMonitor(this)
                ?: soldierController?.startHeartbeatMonitor(this)

            try {
                reportRuntimeState(DeviceRuntimeState.WAITING_TASK)
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
                        exitRunningMode()
                        return@launch
                    }
                    val secondKeywordNow = available[comboIndex % available.size]
                    comboIndex++
                    val currentTask = task.copy(secondKeyword = secondKeywordNow)

                    if (!dryRun && !externalBrowser) {
                        if (!ensureNaverLoginOrBackoff(serverClient, identity, "A", externalSession) { state, message ->
                                reportRuntimeState(state, message)
                            }) return@repeat
                    }

                    appendLog("A전략 반복 ${index + 1}/$loopCount [${available.indexOf(secondKeywordNow) + 1}/${available.size}번 조합]")
                    val result: StrategyAResult = bossController?.runOnce(currentTask)
                        ?: soldierController?.runOnce(currentTask)
                        ?: StrategyAResult(success = false, message = "no_controller")

                    if (!dryRun && !externalBrowser && webViewManager.rendererGone) {
                        appendLog("렌더러 OOM 감지: WebView 재생성 중")
                        webViewManager.rebuildWebView(webViewContainer)
                        if (!ensureNaverLoginOrBackoff(serverClient, identity, "A", externalSession) { state, message ->
                                reportRuntimeState(state, message)
                            }) return@repeat
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
                        if (!ensureNaverLoginOrBackoff(serverClient, identity, "A", externalSession) { state, message ->
                                reportRuntimeState(state, message)
                            }) return@repeat
                    }
                }
                appendLog("A전략 반복 완료: ${successCount}/$loopCount 성공")
            } finally {
                heartbeatJob?.cancel()
            }
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
        val serverUrl = resolveServerUrl(config)
        val loopCount = getIntExtra(EXTRA_LOOP_COUNT, config.loopCount).coerceIn(1, 1_000)

        config.save(
            deviceName = deviceName,
            loopCount = loopCount,
            serverUrl = serverUrl,
        )
        val dryRun = getBoolExtra(EXTRA_DRY_RUN)
        val continuousServerMode = !dryRun && serverUrl.isNotBlank()

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

        val serverClient = buildServerClient()
        val strategyImpl = if (dryRun) {
            appendLog("DRY_RUN 모드: URL 흐름만 검증")
            SamsungBrowserStrategyG(DryRunBrowserSession(::appendLog))
        } else {
            SamsungBrowserStrategyG(
                browserSession = WebViewBrowserSession(webViewManager),
                webViewManager = webViewManager,
                deviceName = deviceName,
                captchaChallengeClient = serverClient.challengeClient,
                screenshotCapture = screenshotCapture,
            )
        }
        val botStrategy = BotStrategy.G(strategyImpl)
        logServerMode(serverClient)
        enterRunningMode("전략 G 실행 중")

        lifecycleScope.launch {
            val deviceCommandManager = DeviceCommandManager(
                context = this@MainActivity,
                identity = identity,
                groupControlClient = serverClient.groupControlClient,
                serverUrl = resolveServerUrl(config),
                apiKey = resolveApiKey(config).takeIf { it.isNotBlank() },
                log = ::appendLog,
            )
            val bossController = if (identity.isBoss) BossController(
                identity = identity,
                botStrategy = botStrategy,
                ipRotationManager = IpRotationManager(),
                accountLeaseClient = serverClient.accountLeaseClient,
                groupControlClient = serverClient.groupControlClient,
                deviceCommandHandler = deviceCommandManager,
                rotateEveryGroupTasks = rotateEvery,
                rotationDrainWaitMs = drainWaitMs,
                log = ::appendLog,
                beforeRotate = if (!dryRun) ({
                    webViewManager.clearPage()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop com.sec.android.app.sbrowser"))
                            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) proc.destroy()
                        }
                    }
                    appendLog("삼성 인터넷 강제 종료 완료")
                }) else null,
            ) else null

            val soldierController = if (identity.isSoldier) SoldierController(
                identity = identity,
                botStrategy = botStrategy,
                accountLeaseClient = serverClient.accountLeaseClient,
                groupControlClient = serverClient.groupControlClient,
                deviceCommandHandler = deviceCommandManager,
                log = ::appendLog,
            ) else null

            suspend fun reportRuntimeState(state: DeviceRuntimeState, message: String? = null) {
                if (bossController != null) {
                    bossController.reportRuntimeState(state, message)
                } else {
                    soldierController?.reportRuntimeState(state, message)
                }
            }

            val heartbeatJob = bossController?.startHeartbeatMonitor(this)
                ?: soldierController?.startHeartbeatMonitor(this)

            try {
                var successCount = 0
                var taskIndex = 0
                reportRuntimeState(DeviceRuntimeState.WAITING_TASK)
                while (taskIndex < loopCount || continuousServerMode) {
                    reportRuntimeState(DeviceRuntimeState.WAITING_TASK)

                    if (!dryRun) {
                        if (!ensureNaverLoginOrBackoff(serverClient, identity, "G") { state, message ->
                                reportRuntimeState(state, message)
                            }) continue
                    }

                    // 반복마다 트래픽 큐에서 새 작업 가져오기
                    var taskLeaseError: Throwable? = null
                    val taskLease = runCatching {
                        serverClient.taskLeaseClient.leaseTask(identity.rawName, identity.role, "G", APP_VERSION)
                    }.onFailure {
                        taskLeaseError = it
                        if (it is StrategyTaskLeaseBlockedException) {
                            appendLog("그룹 상태 ${it.groupState}: 작업 lease 차단 (${it.reason})")
                        } else {
                            appendLog("상품 task lease 실패: ${it.message}")
                        }
                    }.getOrNull()
                    val blockedLease = taskLeaseError as? StrategyTaskLeaseBlockedException
                    if (blockedLease != null) {
                        reportRuntimeState(
                            DeviceRuntimeState.PAUSED,
                            "group_state_blocked:${blockedLease.groupState}",
                        )
                        kotlinx.coroutines.delay(30_000)
                        continue
                    }
                    if (taskLeaseError != null) {
                        reportRuntimeState(DeviceRuntimeState.ERROR, "task_lease_failed")
                        kotlinx.coroutines.delay(30_000)
                        continue
                    }
                    if (taskLease != null && taskLease.taskG == null) {
                        appendLog("G전략 작업 형식 불일치 — 작업 실패 보고 후 다음 큐 확인")
                        runCatching {
                            serverClient.taskLeaseClient.reportTask(
                                StrategyTaskReport(
                                    taskLeaseId = taskLease.taskLeaseId,
                                    deviceName = identity.rawName,
                                    result = StrategyTaskResult.FAILED,
                                    message = "invalid_g_task_payload",
                                ),
                            )
                        }.onFailure { appendLog("상품 task report 실패: ${it.message}") }
                        taskIndex++
                        continue
                    }
                    val task = taskLease?.taskG ?: fallbackTask.takeIf { dryRun && it.mid.isNotBlank() }

                    if (task == null) {
                        appendLog("트래픽 큐 비어있음 — 30초 후 재시도")
                        reportRuntimeState(DeviceRuntimeState.WAITING_TASK)
                        kotlinx.coroutines.delay(30_000)
                        continue
                    }
                    appendLog("상품 task 획득: ${task.productTitle ?: task.linkUrl}")

                    val loopLabel = if (continuousServerMode) {
                        "${taskIndex + 1}/연속"
                    } else {
                        "${taskIndex + 1}/$loopCount"
                    }
                    appendLog("G전략 반복 $loopLabel")
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
                        if (!ensureNaverLoginOrBackoff(serverClient, identity, "G") { state, message ->
                                reportRuntimeState(state, message)
                            }) continue
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
            } finally {
                heartbeatJob?.cancel()
            }
            exitRunningMode()
        }
    }

    // ── 공통 헬퍼 ───────────────────────────────────────────────────────────

    private suspend fun ensureNaverLogin(
        credential: NaverLoginCredential,
        serverClient: ServerClients,
        identity: DeviceIdentity,
        externalSession: ExternalSamsungBrowserSession? = null,
    ): Boolean {
        if (externalSession != null) {
            return ensureExternalNaverLogin(credential, serverClient, identity, externalSession)
        }

        val deviceName = identity.rawName
        val accountAlias = credential.accountAlias
        val sessionKey = cookieSessionKey(deviceName, accountAlias)
        if (!webViewManager.sessionAccountMatches(this, deviceName, accountAlias)) {
            if (webViewManager.hasCookieSession()) {
                appendLog("배정 계정 변경 감지: 기존 네이버 쿠키 삭제")
                webViewManager.clearNaverCookies()
            }
            webViewManager.clearSessionAccount(this, deviceName)
            validatedCookieSessions.removeAll { it.startsWith("$deviceName|") }
        }

        if (webViewManager.hasCookieSession()) {
            if (sessionKey in validatedCookieSessions || webViewManager.isNaverLoggedIn()) {
                webViewManager.saveSessionAccount(this, deviceName, accountAlias)
                validatedCookieSessions.add(sessionKey)
                return true
            }
            appendLog("기존 네이버 쿠키 세션 만료: 재로그인 진행")
            webViewManager.clearNaverCookies()
            webViewManager.clearSessionAccount(this, deviceName)
            validatedCookieSessions.remove(sessionKey)
        }

        // IP 로테이션 이후: 서버 저장 쿠키 복원 시도
        val saved = runCatching { serverClient.cookieClient.loadCookies(deviceName, accountAlias) }.getOrNull()
        if (!saved.isNullOrBlank()) {
            appendLog("서버 저장 쿠키 복원 시도: ${accountAlias ?: "배정 계정"}")
            webViewManager.setNaverCookie(saved)
            kotlinx.coroutines.delay(500)
            if (webViewManager.hasCookieSession() && webViewManager.isNaverLoggedIn()) {
                webViewManager.saveSessionAccount(this, deviceName, accountAlias)
                validatedCookieSessions.add(sessionKey)
                appendLog("쿠키 복원 성공 — 로그인 생략")
                return true
            }
            appendLog("쿠키 복원 실패 — 재로그인 진행")
        }

        // 전체 로그인
        val loginOk = webViewManager.loginNaver(
            loginId = credential.loginId,
            password = credential.password,
        )
        if (!loginOk) {
            val challengeClient = serverClient.challengeClient
            if (challengeClient != null) {
                appendLog("로그인 차단: 관리 페이지 챌린지 제출 중...")
                val solved = tryLoginChallenge(challengeClient, credential, identity)
                if (solved) {
                    webViewManager.saveSessionAccount(this, deviceName, accountAlias)
                    validatedCookieSessions.add(sessionKey)
                    saveCurrentCookiesToServer(
                        serverClient = serverClient.cookieClient,
                        deviceName = deviceName,
                        accountAlias = accountAlias,
                        successLog = "로그인 챌린지 해결 후 쿠키 저장 완료",
                    )
                    return true
                }
            }
            reportLoginBlocked(
                serverClient = serverClient,
                credential = credential,
                identity = identity,
                signals = loginFailureSignals(webViewManager.visibleText()),
                message = "naver_login_failed",
            )
            appendLog("네이버 로그인 실패: 작업 중단")
            return false
        }

        // 로그인 성공 → 쿠키 서버 저장
        webViewManager.saveSessionAccount(this, deviceName, accountAlias)
        validatedCookieSessions.add(sessionKey)
        saveCurrentCookiesToServer(
            serverClient = serverClient.cookieClient,
            deviceName = deviceName,
            accountAlias = accountAlias,
            successLog = "네이버 쿠키 서버 저장 완료",
        )
        return true
    }

    private suspend fun tryLoginChallenge(
        challengeClient: com.navertraffic.samsung.data.CaptchaChallengeClient,
        credential: NaverLoginCredential,
        identity: DeviceIdentity,
    ): Boolean {
        val screenshot = screenshotCapture.captureBase64() ?: run {
            appendLog("로그인 챌린지 스크린샷 캡처 실패")
            return false
        }
        val signalType = if (webViewManager.hasLoginCaptchaVisible()) "LOGIN_CAPTCHA" else "LOGIN_BLOCKED"
        val challengeId = try {
            challengeClient.submitChallenge(
                com.navertraffic.samsung.data.CaptchaChallengeRequest(
                    deviceName = identity.rawName,
                    leaseId = credential.leaseId,
                    accountAlias = credential.accountAlias ?: "",
                    screenshotBase64 = screenshot,
                    signalType = signalType,
                    pageUrl = webViewManager.currentUrl(),
                ),
            )
        } catch (e: Exception) {
            appendLog("로그인 챌린지 제출 실패: ${e.message}")
            return false
        }
        appendLog("로그인 챌린지 제출: $challengeId — 관리자 응답 대기 중 (최대 5분)")

        val deadline = System.currentTimeMillis() + 5 * 60 * 1_000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(10_000)
            val poll = runCatching { challengeClient.pollAnswer(challengeId) }.getOrNull() ?: continue
            when (poll.status) {
                "answered" -> {
                    val answer = poll.answer ?: run {
                        appendLog("관리자 응답값 비어있음")
                        runCatching { challengeClient.completeChallenge(challengeId, false) }
                        return false
                    }
                    appendLog("관리자 응답 수신: $answer")
                    val filled = webViewManager.fillLoginChallengeAndSubmit(answer)
                    val loggedIn = filled && webViewManager.waitForLoginComplete()
                    runCatching { challengeClient.completeChallenge(challengeId, loggedIn) }
                    if (loggedIn) appendLog("로그인 챌린지 해결 완료") else appendLog("챌린지 응답 후 로그인 실패")
                    return loggedIn
                }
                "expired" -> {
                    appendLog("로그인 챌린지 만료")
                    return false
                }
                else -> appendLog("관리자 응답 대기 중... (남은 ${(deadline - System.currentTimeMillis()) / 1000}초)")
            }
        }
        appendLog("로그인 챌린지 응답 시간 초과")
        runCatching { challengeClient.completeChallenge(challengeId, false) }
        return false
    }

    private suspend fun ensureNaverLoginOrBackoff(
        serverClient: ServerClients,
        identity: DeviceIdentity,
        strategyName: String,
        externalSession: ExternalSamsungBrowserSession? = null,
        onWaiting: suspend (DeviceRuntimeState, String?) -> Unit = { _, _ -> },
    ): Boolean {
        val credential = resolveNaverLogin(serverClient, identity, strategyName)
        if (credential == null) {
            onWaiting(DeviceRuntimeState.WAITING_LOGIN, "naver_account_missing")
            appendLog("네이버 로그인/쿠키 준비 안됨: 비로그인 작업 없이 ${LOGIN_RETRY_DELAY_MS / 1000}초 후 재시도")
            kotlinx.coroutines.delay(LOGIN_RETRY_DELAY_MS)
            return false
        }

        onWaiting(DeviceRuntimeState.WAITING_LOGIN, "naver_login_checking")
        if (ensureNaverLogin(credential, serverClient, identity, externalSession)) {
            return true
        }

        onWaiting(DeviceRuntimeState.WAITING_LOGIN, "naver_login_failed")
        appendLog("네이버 로그인/쿠키 준비 안됨: 비로그인 작업 없이 ${LOGIN_RETRY_DELAY_MS / 1000}초 후 재시도")
        kotlinx.coroutines.delay(LOGIN_RETRY_DELAY_MS)
        return false
    }

    private suspend fun saveCurrentCookiesToServer(
        serverClient: CookieStorageClient,
        deviceName: String,
        accountAlias: String?,
        successLog: String,
    ) {
        val cookies = webViewManager.extractNaverCookies()
        if (cookies.isBlank()) return

        runCatching { serverClient.saveCookies(deviceName, accountAlias, cookies) }
            .onSuccess { appendLog(successLog) }
            .onFailure { appendLog("쿠키 서버 저장 실패: ${it.message}") }
    }

    private suspend fun ensureExternalNaverLogin(
        credential: NaverLoginCredential,
        serverClient: ServerClients,
        identity: DeviceIdentity,
        externalSession: ExternalSamsungBrowserSession,
    ): Boolean {
        if (!externalSession.supportsPageInspection) {
            reportLoginBlocked(
                serverClient = serverClient,
                credential = credential,
                identity = identity,
                signals = listOf(ProtectionSignal.SAMSUNG_INTERNET_MISSING, ProtectionSignal.LOGIN_STILL_REQUIRED),
                message = "samsung_internet_accessibility_required_for_login_check",
            )
            appendLog("외부 삼성브라우저 로그인 확인 불가: 접근성 서비스를 켜야 작업을 시작합니다")
            return false
        }

        externalSession.loadAndWait(SamsungBrowserStrategyA.NAVER_HOME_URL, 15_000)
        kotlinx.coroutines.delay(1_500)
        val text = externalSession.visibleText()
        val signals = protectionDetector.detectFromText(text)
        if (signals.isNotEmpty()) {
            reportLoginBlocked(serverClient, credential, identity, signals, "external_browser_protection_detected")
            appendLog("외부 삼성브라우저 보호/재인증 감지: 작업 중단")
            return false
        }
        if (!looksLoggedIn(text)) {
            reportLoginBlocked(
                serverClient = serverClient,
                credential = credential,
                identity = identity,
                signals = listOf(ProtectionSignal.LOGIN_STILL_REQUIRED),
                message = "external_browser_not_logged_in",
            )
            appendLog("외부 삼성브라우저 비로그인 감지: 작업 중단")
            return false
        }
        appendLog("외부 삼성브라우저 로그인 확인 완료")
        return true
    }

    private fun looksLoggedIn(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains("로그아웃")) return true
        if (text.contains("로그인") || text.contains("로그인이 필요")) return false
        return true
    }

    private fun loginFailureSignals(text: String): List<ProtectionSignal> {
        val signals = protectionDetector.detectFromText(text)
        return signals.ifEmpty { listOf(ProtectionSignal.LOGIN_STILL_REQUIRED) }
    }

    private suspend fun reportLoginBlocked(
        serverClient: ServerClients,
        credential: NaverLoginCredential,
        identity: DeviceIdentity,
        signals: List<ProtectionSignal>,
        message: String,
    ) {
        val result = if (signals.any { it != ProtectionSignal.LOGIN_STILL_REQUIRED }) {
            AccountLeaseResult.PROTECTED
        } else {
            AccountLeaseResult.SESSION_EXPIRED
        }
        runCatching {
            serverClient.accountLeaseClient.report(
                AccountLeaseReport(
                    leaseId = credential.leaseId,
                    deviceName = identity.rawName,
                    result = result,
                    signals = signals,
                    lastUrl = webViewManager.currentUrl(),
                    message = message,
                ),
            )
        }.onFailure { appendLog("로그인 차단 report 실패: ${it.message}") }
        credential.leaseId?.let { leaseId ->
            runCatching {
                serverClient.accountLeaseClient.release(
                    leaseId = leaseId,
                    deviceName = identity.rawName,
                    reason = message,
                )
            }.onFailure { appendLog("로그인 차단 lease release 실패: ${it.message}") }
        }
    }

    private fun cookieSessionKey(deviceName: String, accountAlias: String?): String {
        return "$deviceName|${accountAlias?.takeIf { it.isNotBlank() } ?: "manual"}"
    }

    private suspend fun resolveNaverLogin(
        serverClient: ServerClients,
        identity: DeviceIdentity,
        strategyName: String,
    ): NaverLoginCredential? {
        val lease: AccountLease? = runCatching {
            serverClient.accountLeaseClient.leaseAccount(
                identity.rawName,
                identity.role,
                strategyName,
                APP_VERSION,
            )
        }.onFailure { appendLog("네이버 계정 lease 실패: ${it.message}") }.getOrNull()

        if (lease != null && lease.loginId.isNotBlank() && lease.password.isNotBlank()) {
            appendLog("네이버 계정 lease 사용: ${lease.accountAlias}")
            applyNaverCredentialToUi(lease.loginId, lease.password)
            return NaverLoginCredential(
                leaseId = lease.leaseId,
                loginId = lease.loginId,
                password = lease.password,
                accountAlias = lease.accountAlias,
            )
        }

        if (serverClient.accountLeaseClient is NoopAccountLeaseClient) {
            appendLog("서버 URL 없음: 관리자 배정 계정을 받을 수 없습니다")
        } else {
            appendLog("서버 배정 계정 없음: 관리자에서 ${identity.rawName} 계정을 배정하세요")
        }

        return null
    }

    private fun buildServerClient(): ServerClients {
        val config = ConfigStore(this)
        val serverUrl = resolveServerUrl(config)
        val apiKey = resolveApiKey(config)
        if (serverUrl.isNotBlank()) {
            val client = AndroidServerApiClient(serverUrl, apiKey.takeIf { it.isNotBlank() })
            return ServerClients(client, client, client, client, client)
        }

        val supabaseUrl = BuildConfig.SUPABASE_URL
        val supabaseKey = BuildConfig.SUPABASE_KEY
        if (supabaseUrl.isBlank()) {
            return ServerClients(
                NoopAccountLeaseClient(),
                NoopGroupControlClient(),
                NoopStrategyTaskLeaseClient(),
                NoopCookieStorageClient(),
            )
        }
        val client = SupabaseApiClient(supabaseUrl, supabaseKey)
        return ServerClients(NoopAccountLeaseClient(), client, client, client)
    }

    private fun logServerMode(serverClient: ServerClients) {
        when {
            serverClient.taskLeaseClient is NoopStrategyTaskLeaseClient ->
                appendLog("서버 URL 없음: 로컬 smoke 모드")
            serverClient.accountLeaseClient is NoopAccountLeaseClient ->
                appendLog("직접 Supabase 연동 모드: 계정 lease 없음")
            else ->
                appendLog("Vercel 서버 API 연동 모드 활성")
        }
    }

    private fun resolveServerUrl(config: ConfigStore): String {
        return intent.getStringExtra(EXTRA_SERVER_URL)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_SERVER_URL.trim().takeIf { it.isNotBlank() }
            ?: etServerUrl.text.toString().trim().takeIf { it.isNotBlank() }
            ?: config.serverUrl.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun resolveApiKey(config: ConfigStore): String {
        return intent.getStringExtra(EXTRA_API_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: config.apiKey.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEVICE_API_TOKEN.trim()
    }

    private fun setNaverPasswordVisible(visible: Boolean) {
        val selection = etNaverPassword.selectionEnd.coerceIn(0, etNaverPassword.text.length)
        etNaverPassword.inputType =
            InputType.TYPE_CLASS_TEXT or
                if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        etNaverPassword.setSelection(selection)
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
        val challengeClient: com.navertraffic.samsung.data.CaptchaChallengeClient? = null,
    )

    private data class NaverLoginCredential(
        val leaseId: String?,
        val loginId: String,
        val password: String,
        val accountAlias: String?,
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
        private val APP_VERSION = BuildConfig.VERSION_NAME
        private const val LOGIN_RETRY_DELAY_MS = 60_000L
        private const val EXTRA_AUTO_RUN = "autoRun"
        private const val EXTRA_DEVICE_NAME = "deviceName"
        private const val EXTRA_ROTATE_EVERY = "rotateEvery"
        private const val EXTRA_SERVER_URL = "serverUrl"
        private const val EXTRA_API_KEY = "apiKey"
        private const val EXTRA_LOOP_COUNT = "loopCount"
        private const val EXTRA_DRY_RUN = "dryRun"
        private const val EXTRA_EXTERNAL_BROWSER = "externalBrowser"
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
