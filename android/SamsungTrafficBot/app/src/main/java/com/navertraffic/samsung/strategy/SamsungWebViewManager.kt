package com.navertraffic.samsung.strategy

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SamsungWebViewManager(
    private var webView: WebView,
    private val log: (String) -> Unit,
) {
    private var pageLoad = CompletableDeferred<Unit>()
    private var lastLoadHadError = false
    @Volatile var rendererGone = false
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun initialize() {
        WebView.setWebContentsDebuggingEnabled(false)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadsImagesAutomatically = true
            blockNetworkImage = false
            userAgentString = SAMSUNG_BROWSER_UA
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!url.startsWith("http://") && !url.startsWith("https://")) return true
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                view?.evaluateJavascript(NAVIGATOR_SPOOF_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!pageLoad.isCompleted) pageLoad.complete(Unit)
                view?.evaluateJavascript(NAVIGATOR_SPOOF_JS, null)
                log("로드 완료: ${url ?: "(unknown)"}")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                lastLoadHadError = true
                if (!pageLoad.isCompleted) pageLoad.complete(Unit)
                log("로드 오류: ${error?.description ?: "unknown"}")
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                rendererGone = true
                if (!pageLoad.isCompleted) pageLoad.complete(Unit)
                log("WebView 렌더러 OOM: 루프 재개 대기 중")
                return true
            }
        }
    }

    suspend fun loadAndWait(url: String, timeoutMs: Long = 30_000) {
        if (rendererGone) return
        val referer = withContext(Dispatchers.Main) {
            webView.url?.takeIf { it.isNotEmpty() && it != "about:blank" && !it.startsWith("about:") }
        }
        repeat(3) { attempt ->
            lastLoadHadError = false
            log("URL 로딩: $url")
            // pageLoad reset must happen atomically with loadUrl on the main thread.
            // If reset on the coroutine thread, a stale onPageFinished from the
            // previous navigation can complete the new deferred before loadUrl fires.
            withContext(Dispatchers.Main) {
                pageLoad = CompletableDeferred()
                webView.loadUrl(url, samsungHeaders(referer))
            }
            withTimeoutOrNull(timeoutMs) { pageLoad.await() }
            delay(500)
            if (!lastLoadHadError && (visibleText().isNotBlank() || currentUrl()?.startsWith("about:blank") == true)) return
            if (attempt < 2) {
                log("페이지 로드 실패 감지: 재시도 ${attempt + 2}/3")
                delay(1_500)
            }
        }
    }

    // Fast session check: reads Naver session cookies without loading any page.
    // CookieManager persists cookies to disk across app restarts automatically.
    fun hasCookieSession(): Boolean {
        val cookies = currentNaverCookieItems()
        return cookies.any { it.startsWith("NID_AUT=") } &&
            cookies.any { it.startsWith("NID_SES=") }
    }

    fun saveCredentials(context: Context, id: String, pw: String) {
        if (id.isBlank() || pw.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_ID, id)
            .putString(PREF_PW, pw)
            .apply()
    }

    fun loadCredentials(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(PREF_ID, null) ?: return null
        val pw = prefs.getString(PREF_PW, null) ?: return null
        return id to pw
    }

    fun sessionAccountMatches(context: Context, deviceName: String, accountAlias: String?): Boolean {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(sessionAccountKey(deviceName), null)
            ?: return false
        return saved == normalizeSessionAccount(accountAlias)
    }

    fun saveSessionAccount(context: Context, deviceName: String, accountAlias: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(sessionAccountKey(deviceName), normalizeSessionAccount(accountAlias))
            .apply()
    }

    fun clearSessionAccount(context: Context, deviceName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(sessionAccountKey(deviceName))
            .apply()
    }

    fun setNaverCookie(cookie: String) {
        if (cookie.isBlank()) return
        val manager = CookieManager.getInstance()
        cookie.split(";")
            .map { it.trim() }
            .filter { isReusableNaverCookie(it) }
            .forEach { item ->
                val cookieWithAttrs = "$item; Domain=.naver.com; Path=/; Max-Age=${naverCookieMaxAge(item)}"
                NAVER_COOKIE_URLS.forEach { url ->
                    manager.setCookie(url, cookieWithAttrs)
                }
            }
        manager.flush()
        log("네이버 쿠키 주입 완료: ${currentNaverCookieItems().map { it.substringBefore("=") }}")
    }

    fun clearNaverCookies() {
        val manager = CookieManager.getInstance()
        val expiredCookies = NAVER_REUSABLE_COOKIE_NAMES.flatMap { name ->
            listOf(
                "$name=; Domain=.naver.com; Path=/; Max-Age=0",
                "$name=; Domain=naver.com; Path=/; Max-Age=0",
                "$name=; Path=/; Max-Age=0",
            )
        }
        NAVER_COOKIE_URLS.forEach { url ->
            expiredCookies.forEach { manager.setCookie(url, it) }
        }
        manager.flush()
        log("네이버 쿠키 삭제 완료")
    }

    private fun persistNaverSessionCookies() {
        val manager = CookieManager.getInstance()
        currentNaverCookieItems().forEach { item ->
            val cookieWithAttrs = "$item; Domain=.naver.com; Path=/; Max-Age=${naverCookieMaxAge(item)}"
            NAVER_COOKIE_URLS.forEach { url ->
                manager.setCookie(url, cookieWithAttrs)
            }
        }
        manager.flush()
    }

    private fun currentNaverCookieItems(): List<String> {
        val manager = CookieManager.getInstance()
        val byName = linkedMapOf<String, String>()
        NAVER_COOKIE_URLS
            .mapNotNull { manager.getCookie(it) }
            .flatMap { it.split(";") }
            .map { it.trim() }
            .filter { isReusableNaverCookie(it) }
            .forEach { item ->
                val name = item.substringBefore("=")
                if (!byName.containsKey(name)) {
                    byName[name] = item
                }
            }
        return byName.values.toList()
    }

    private fun isReusableNaverCookie(item: String): Boolean {
        val name = item.substringBefore("=", missingDelimiterValue = "")
        return name in NAVER_REUSABLE_COOKIE_NAMES && item.contains("=")
    }

    private fun naverCookieMaxAge(item: String): Int {
        val name = item.substringBefore("=")
        return when (name) {
            "NID_SES" -> 86_400
            else -> 2_592_000
        }
    }

    suspend fun visibleText(): String {
        val js = """
            (function() {
              return document.body ? document.body.innerText : "";
            })();
        """.trimIndent()
        return evaluateText(js)
    }

    fun currentUrl(): String? = webView.url

    suspend fun isNaverLoggedIn(): Boolean {
        loadAndWait("https://m.naver.com/", 30_000)
        delay(1_200)
        val js = """
            (function() {
              var body = document.body ? document.body.innerText : "";
              var hasLogout = !!document.querySelector('a[href*="nidlogin.logout"], a[href*="logout"]') || body.indexOf('로그아웃') >= 0;
              var hasLogin = !!document.querySelector('a[href*="nidlogin.login"], a[href*="mode=form"]') || body.indexOf('로그인') >= 0;
              var hasUserArea = !!document.querySelector('[class*="MyView"], [class*="my_view"], [class*="account"], [id*="account"]');
              return (hasLogout || (hasUserArea && !hasLogin)) ? "true" : "false";
            })();
        """.trimIndent()
        return evaluateText(js) == "true"
    }

    suspend fun loginNaver(loginId: String, password: String): Boolean {
        // Fast path: check NID_AUT/NID_SES cookies without loading a page
        if (hasCookieSession()) {
            log("네이버 쿠키 세션 유지됨 — 로그인 생략")
            return true
        }
        if (loginId.isBlank() || password.isBlank()) {
            log("네이버 로그인 정보 없음")
            return false
        }

        loadAndWait(NAVER_LOGIN_URL, 30_000)
        delay(1_000)
        if (hasLoginChallenge()) {
            log("네이버 로그인 보안문자 감지: 자동 로그인 중단")
            return false
        }
        if (!typeIntoSelector("#id", loginId) || !typeIntoSelector("#pw", password)) {
            log("네이버 로그인 폼 입력 실패")
            return false
        }
        // 자동 로그인 체크 → NID_AUT에 Max-Age 부여 (세션 쿠키 방지)
        checkAutoLogin()
        if (!tapLoginButton()) {
            log("네이버 로그인 버튼 터치 실패")
            return false
        }

        repeat(45) {
            delay(1_000)
            if (currentUrl()?.contains("nidlogin.login") != true) {
                persistNaverSessionCookies()
                log("네이버 로그인 완료")
                return true
            }
        }
        log("네이버 로그인 타임아웃")
        return false
    }

    private suspend fun checkAutoLogin() {
        evaluateText(
            """
            (function() {
              var cb = document.querySelector('#keep, input[name="keep"], input[id*="auto"], label[for="keep"]');
              if (cb && !cb.checked) { cb.click(); return "checked"; }
              return cb ? "already" : "not_found";
            })();
            """.trimIndent(),
        )
    }

    private suspend fun hasLoginChallenge(): Boolean {
        val js = """
            (function() {
              var body = document.body ? document.body.innerText : "";
              var captcha = !!document.querySelector('#captcha, #chptcha, input[name="captcha"], input[name="chptcha"]');
              return (captcha || body.indexOf('자동입력 방지') >= 0 || body.indexOf('보안문자') >= 0) ? "true" : "false";
            })();
        """.trimIndent()
        return evaluateText(js) == "true"
    }

    private suspend fun typeIntoSelector(selector: String, text: String): Boolean {
        val focused = evaluateText(
            """
                (function(selector) {
                  var el = document.querySelector(selector);
                  if (!el) return "missing";
                  el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
                  el.focus();
                  el.value = "";
                  el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContentBackward' }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                  return "focused";
                })(${quoteJs(selector)});
            """.trimIndent(),
        ) == "focused"
        if (!focused) return false
        delay(250)
        withContext(Dispatchers.Main) {
            webView.requestFocus()
            val inputConnection = webView.onCreateInputConnection(EditorInfo())
            inputConnection?.commitText(text, 1)
        }
        delay(400)
        val length = evaluateText(
            """
                (function(selector) {
                  var el = document.querySelector(selector);
                  return el ? String((el.value || "").length) : "0";
                })(${quoteJs(selector)});
            """.trimIndent(),
        ).toIntOrNull() ?: 0
        val ok = length == text.length
        if (!ok) log("입력 길이 불일치: $selector expected=${text.length} actual=$length")
        return ok
    }

    private suspend fun tapLoginButton(): Boolean {
        val result = evaluateText(
            """
                (function() {
                  var btn = document.getElementById('log.login') || document.querySelector('button[type="submit"]');
                  if (!btn) return "missing";
                  btn.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
                  var r = btn.getBoundingClientRect();
                  return ['found', r.left + r.width / 2, r.top + r.height / 2].join('|');
                })();
            """.trimIndent(),
        )
        if (!result.startsWith("found|")) return false
        val parts = result.split("|")
        val cssX = parts.getOrNull(1)?.toFloatOrNull() ?: return false
        val cssY = parts.getOrNull(2)?.toFloatOrNull() ?: return false
        tapCssPoint(cssX, cssY)
        return true
    }

    // Returns "clicked|cur|total", "end|cur|total", or "none"
    private suspend fun clickShopCarouselNext(maxPage: Int): String {
        val js = """
            (function(maxPage) {
              var isVisible = function(el) {
                var rect = el.getBoundingClientRect();
                var s = window.getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
              };
              var readPaging = function(text) {
                var s = text.match(/(\d+)\s*\/\s*(\d+)/);
                if (s) return { current: Number(s[1]), total: Number(s[2]) };
                var a = text.match(/현재\s*(\d+)\s*전체\s*(\d+)/);
                if (a) return { current: Number(a[1]), total: Number(a[2]) };
                return null;
              };
              var readPagingFromRoot = function(root) {
                if (!root) return null;
                var curText = (root.querySelector('._current, .cmm_npgs_now, [aria-current="page"]') || {}).textContent || '';
                var totText = (root.querySelector('._total') || {}).textContent || '';
                var cur = Number((curText.match(/\d+/) || [])[0]);
                var tot = Number((totText.match(/\d+/) || [])[0]);
                if (cur > 0 && tot > 0) return { current: cur, total: tot };
                return readPaging((root.textContent || '').replace(/\s+/g, ' ').trim());
              };
              var shopLike = function(text) { return /(플러스스토어|가격비교|쇼핑|상품|스토어|상품판매)/.test(text); };
              var tapCoords = function(el) {
                el.scrollIntoView({ block: 'center', inline: 'center' });
                var r = el.getBoundingClientRect();
                return 'tap|' + (r.left + r.width / 2) + '|' + (r.top + r.height / 2);
              };
              var NEXT_SEL = [
                'a.cmm_pg_next._next.on', 'button.cmm_pg_next._next.on',
                '.pagination_wrap._page_root a.cmm_pg_next._next.on',
                '.pagination_wrap._page_root button.cmm_pg_next._next.on',
                'a._next.on', 'button._next.on',
                "a[class*='pg_next'].on", "button[class*='pg_next'].on",
                "a[class*='btn_next']", "button[class*='btn_next']"
              ].join(',');
              var candidates = Array.from(document.querySelectorAll(NEXT_SEL)).filter(function(el, i, a) { return a.indexOf(el) === i; });
              for (var i = 0; i < candidates.length; i++) {
                var next = candidates[i];
                if (!isVisible(next)) continue;
                var aria = (next.getAttribute('aria-label') || '') + ' ' + (next.getAttribute('title') || '') + ' ' + (next.textContent || '');
                var cls = next.className || '';
                if (/이전|prev|previous|left/i.test(aria + ' ' + cls)) continue;
                if (next.getAttribute('aria-disabled') === 'true' || next.hasAttribute('disabled') || /disabled|_off|inactive/i.test(cls)) continue;
                var pagingRoot = next.closest('.pagination_wrap._page_root, .pagination_wrap, .cmm_pgs');
                var paging = readPagingFromRoot(pagingRoot);
                if (paging) {
                  var limit = Math.min(paging.total, maxPage);
                  if (paging.current >= limit) return 'end|' + paging.current + '|' + paging.total;
                }
                var coords = tapCoords(next);
                return coords + '|' + (paging ? paging.current : 0) + '|' + (paging ? paging.total : 0);
              }
              var SECTION_SEL = [
                'section._root_shp_lis','section._root_shs_lis','section._sp_nshop_gift',
                "section[class*='sp_shop']","section[class*='shop_gift']","section[class*='shop_product']",
                "section[class*='_root_shp']","section[class*='_root_shs']","section"
              ].join(',');
              var sections = Array.from(document.querySelectorAll(SECTION_SEL)).filter(function(el, i, a) { return a.indexOf(el) === i; });
              for (var si = 0; si < sections.length; si++) {
                var sec = sections[si];
                var text = sec.innerText || '';
                if (!/(플러스스토어|쇼핑|상품|스토어)/.test(text)) continue;
                var paging = readPaging(text);
                if (!paging) continue;
                var limit = Math.min(paging.total, maxPage);
                if (paging.current >= limit) return 'end|' + paging.current + '|' + paging.total;
                var secRect = sec.getBoundingClientRect();
                var directNext = sec.querySelector('a.cmm_pg_next._next.on, button.cmm_pg_next._next.on, a._next.on, button._next.on');
                var btns = (directNext ? [directNext] : []).concat(
                  Array.from(sec.querySelectorAll("button,a,[role='button']"))
                ).filter(function(el) {
                  if (!isVisible(el)) return false;
                  var a = (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.textContent || '');
                  var c = el.className || '';
                  if (el.getAttribute('aria-disabled') === 'true' || el.hasAttribute('disabled') || /disabled|_off|inactive/i.test(c)) return false;
                  if (/이전|prev|previous|left/i.test(a + ' ' + c)) return false;
                  if (/다음|next|right|btn_next|pg_next|cmm_pg_next|pagination_next|_next/i.test(a + ' ' + c)) return true;
                  var r = el.getBoundingClientRect();
                  return r.left > secRect.left + secRect.width / 2 && r.width >= 18 && r.width <= 90 && r.height >= 18 && r.height <= 90;
                }).sort(function(a, b) { return b.getBoundingClientRect().left - a.getBoundingClientRect().left; });
                if (!btns.length) continue;
                var coords = tapCoords(btns[0]);
                return coords + '|' + paging.current + '|' + paging.total;
              }
              return 'none';
            })(${quoteJs(maxPage.toString()).drop(1).dropLast(1)});
        """.trimIndent()
        val raw = evaluateText(js).ifBlank { "none" }
        if (raw.startsWith("tap|")) {
            val parts = raw.split("|")
            val cx = parts.getOrNull(1)?.toFloatOrNull() ?: return "none"
            val cy = parts.getOrNull(2)?.toFloatOrNull() ?: return "none"
            val cur = parts.getOrNull(3) ?: "0"
            val total = parts.getOrNull(4) ?: "0"
            tapCssPoint(cx, cy)
            return "clicked|$cur|$total"
        }
        return raw
    }

    suspend fun clickMidLink(mid: String, maxCarouselPages: Int = 5): Boolean {
        if (mid.isBlank()) return false

        repeat(maxCarouselPages) { pageIdx ->
            val result = evaluateText(buildMidScanJs(mid))
            if (result.startsWith("found|")) {
                val parts = result.split("|")
                val method = parts.getOrNull(1) ?: "unknown"
                val cssX = parts.getOrNull(2)?.toFloatOrNull() ?: return@repeat
                val cssY = parts.getOrNull(3)?.toFloatOrNull() ?: return@repeat
                log("MID($mid) 상품 발견: $method (캐러셀 ${pageIdx + 1}페이지)")
                tapCssPoint(cssX, cssY)
                return true
            }
            // MID not on this carousel page → try next
            if (pageIdx < maxCarouselPages - 1) {
                val state = clickShopCarouselNext(maxCarouselPages)
                log("플러스스토어 캐러셀 → $state")
                if (!state.startsWith("clicked")) return false
                delay(900) // wait for carousel to settle
            }
        }
        log("MID($mid) 상품 미노출 (캐러셀 ${maxCarouselPages}페이지 탐색 완료)")
        return false
    }

    private fun buildMidScanJs(mid: String): String {
        return """
            (function(mid) {
              function isAdAnchor(anchor) {
                var inv = anchor.getAttribute('data-shp-inventory') ||
                  (anchor.closest('[data-shp-inventory]') && anchor.closest('[data-shp-inventory]').getAttribute('data-shp-inventory')) || '';
                return /lst\*(A|P|D)/.test(inv);
              }
              function score(anchor) {
                if (isAdAnchor(anchor)) return null;
                var href = anchor.href || anchor.getAttribute('href') || '';
                var contentId = anchor.getAttribute('data-shp-contents-id') || '';
                var labelledBy = anchor.getAttribute('aria-labelledby') || '';
                var dataset = JSON.stringify(anchor.dataset || {});
                if (href.indexOf(mid) >= 0 && (href.indexOf('/p/crd/rd') >= 0 || href.indexOf('cr.shopping') >= 0 || href.indexOf('cr2.shopping') >= 0 || href.indexOf('cr3.shopping') >= 0 || href.indexOf('/bridge/searchGate') >= 0 || href.indexOf('searchGate') >= 0)) return [0, 'tracked-search-gate'];
                if (href.indexOf('nv_mid=' + mid) >= 0) return [1, 'nv_mid'];
                if (href.indexOf('searchGate') >= 0 && href.indexOf(mid) >= 0) return [2, 'searchGate'];
                if (contentId === mid) return [3, 'data-shp-contents-id'];
                if (labelledBy.indexOf('nstore_productId_' + mid) >= 0) return [4, 'aria-product-id'];
                if (dataset.indexOf(mid) >= 0) return [5, 'data-attr-mid'];
                if (href.indexOf('/products/' + mid) >= 0 || href.indexOf('smartstore.naver.com/main/products/' + mid) >= 0 || href.indexOf('m.smartstore.naver.com/main/products/' + mid) >= 0) return [6, 'direct-product'];
                return null;
              }
              var ranked = Array.prototype.slice.call(document.querySelectorAll('a')).map(function(anchor, index) {
                var s = score(anchor);
                return s ? { anchor: anchor, index: index, score: s[0], method: s[1] } : null;
              }).filter(Boolean).sort(function(a, b) { return a.score - b.score || a.index - b.index; });
              if (!ranked.length) return 'missing';
              var picked = ranked[0];
              picked.anchor.removeAttribute('target');
              picked.anchor.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
              var r = picked.anchor.getBoundingClientRect();
              return ['found', picked.method, r.left + r.width / 2, r.top + r.height / 2].join('|');
            })(${quoteJs(mid)});
        """.trimIndent()
    }

    suspend fun swipeDetail(durationMs: Long = 2_000) {
        dispatchSwipe(durationMs)
    }

    suspend fun scrollByJs(dy: Int) {
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("window.scrollBy(0,$dy)", null)
        }
        delay(200)
    }

    fun setUserAgent(ua: String) {
        webView.settings.userAgentString = ua
    }

    fun extractNaverCookies(): String {
        return currentNaverCookieItems()
            .joinToString("; ")
    }

    fun pauseTimers() {
        runCatching { webView.pauseTimers() }
    }

    fun resumeTimers() {
        runCatching { webView.resumeTimers() }
    }

    suspend fun rebuildWebView(container: ViewGroup) {
        withContext(Dispatchers.Main) {
            val params = webView.layoutParams
            val viewId = webView.id
            webView.stopLoading()
            webView.destroy()
            container.removeView(webView)
            webView = WebView(container.context).apply {
                layoutParams = params
                id = viewId
            }
            container.addView(webView, 0)
            rendererGone = false
            initialize()
        }
        log("WebView 재생성 완료")
    }

    suspend fun resetSurface() {
        withContext(Dispatchers.Main) {
            webView.loadUrl("about:blank")
            webView.clearHistory()
        }
        delay(500)
        log("브라우저 화면 재시작: 쿠키 세션 유지")
    }

    // Load about:blank and wait for TCP connections to close before network toggle.
    // Prevents modem driver kernel panic (S7 Android 8.0) from active naver.com connections.
    suspend fun typeIntoSearchBar(keyword: String): Boolean {
        val selectors = listOf(
            "input#query",
            "input[name=\"query\"]",
            "input[type=\"search\"]",
            ".gnb_search input",
            ".lnb_search input",
        )
        for (sel in selectors) {
            val result = evaluateText(
                """
                (function(sel, text) {
                    var el = document.querySelector(sel);
                    if (!el) return "missing";
                    el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
                    el.focus();
                    var nativeSetter = Object.getOwnPropertyDescriptor(
                        window.HTMLInputElement.prototype, 'value').set;
                    nativeSetter.call(el, text);
                    el.dispatchEvent(new Event('input',  { bubbles: true, cancelable: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                    return String(el.value.length);
                })(${quoteJs(sel)}, ${quoteJs(keyword)});
                """.trimIndent()
            )
            val len = result?.toIntOrNull() ?: -1
            if (len == keyword.length) {
                log("검색창 키워드 입력 완료: $keyword")
                return true
            }
            if (result != "missing") {
                log("검색창 입력 부분 실패 ($sel): expected=${keyword.length} actual=$len")
            }
        }
        log("검색창 키워드 입력 실패")
        return false
    }

    suspend fun tapSearchSubmitAndWait(timeoutMs: Long = 30_000): Boolean {
        val js = """
            (function() {
                var selectors = [
                    'button[type="submit"]', 'input[type="submit"]',
                    '.btn_search', '.search_btn', 'button.submit',
                    'button[aria-label*="검색"]', 'button[title*="검색"]',
                    'button[class*="search"]'
                ];
                for (var i = 0; i < selectors.length; i++) {
                    var el = document.querySelector(selectors[i]);
                    if (!el) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) {
                        el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});
                        return 'found|' + (r.left + r.width / 2) + '|' + (r.top + r.height / 2);
                    }
                }
                return 'missing';
            })();
        """.trimIndent()
        val result = evaluateText(js)
        if (!result.startsWith("found|")) {
            log("검색 버튼 탭 실패: $result")
            return false
        }
        val parts = result.split("|")
        val cssX = parts.getOrNull(1)?.toFloatOrNull() ?: return false
        val cssY = parts.getOrNull(2)?.toFloatOrNull() ?: return false
        lastLoadHadError = false
        withContext(Dispatchers.Main) {
            pageLoad = CompletableDeferred()
        }
        tapCssPoint(cssX, cssY)
        log("검색 버튼 탭 완료 — 페이지 로드 대기")
        withTimeoutOrNull(timeoutMs) { pageLoad.await() }
        delay(500)
        log("검색 제출 착지: ${currentUrl()}")
        return true
    }

    suspend fun tapSearchBar(): Boolean {
        val js = """
            (function() {
                var selectors = [
                    'input#query', 'input[name="query"]', 'input[type="search"]',
                    '.gnb_search input', '.lnb_search input', '.search_input input',
                    'input[placeholder]'
                ];
                for (var i = 0; i < selectors.length; i++) {
                    var el = document.querySelector(selectors[i]);
                    if (!el) continue;
                    el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});
                    var r = el.getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) {
                        return 'found|' + (r.left + r.width / 2) + '|' + (r.top + r.height / 2);
                    }
                }
                return 'missing';
            })();
        """.trimIndent()
        val result = evaluateText(js)
        if (!result.startsWith("found|")) {
            log("검색창 탭 실패: $result")
            return false
        }
        val parts = result.split("|")
        val cssX = parts.getOrNull(1)?.toFloatOrNull() ?: return false
        val cssY = parts.getOrNull(2)?.toFloatOrNull() ?: return false
        tapCssPoint(cssX, cssY)
        log("검색창 탭 완료")
        return true
    }

    suspend fun simulateAutocomplete(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty() || rendererGone) return
        val partials = mutableListOf<String>()
        var acc = ""
        for (ch in trimmed) {
            acc += ch
            if (acc.length >= 2 && (acc.length % 2 == 0 || acc == trimmed)) {
                partials.add(acc)
            }
            if (partials.size >= 5) break
        }
        if (partials.isEmpty()) partials.add(trimmed)
        for (partial in partials) {
            val js = """
                (function(q) {
                    try {
                        fetch('https://ac.search.naver.com/ac?q=' + encodeURIComponent(q) + '&q_enc=UTF-8&st=100&r_format=json', {
                            method: 'GET',
                            credentials: 'include',
                            mode: 'no-cors'
                        });
                    } catch(e) {}
                })(${quoteJs(partial)});
            """.trimIndent()
            evaluateText(js)
            delay(Random.nextLong(200, 480))
        }
        log("자동완성 시뮬레이션: ${partials.size}회 (\"${partials.first()}\"→\"${partials.last()}\")")
    }

    suspend fun clearPage() {
        withContext(Dispatchers.Main) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }
        delay(3_000)
        log("WebView 연결 정리 완료 (IP 로테이션 전)")
    }

    private suspend fun evaluateText(script: String): String {
        if (rendererGone) return ""
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            if (rendererGone) { result.complete(""); return@withContext }
            webView.evaluateJavascript(script) { raw ->
                result.complete(
                    raw
                        ?.trim('"')
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                        ?: "",
                )
            }
        }
        return withTimeoutOrNull(5_000) { result.await() } ?: ""
    }

    private suspend fun tapCssPoint(cssX: Float, cssY: Float) {
        withContext(Dispatchers.Main) {
            val x = cssX * webView.scale
            val y = cssY * webView.scale
            val downTime = SystemClock.uptimeMillis()
            webView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
            webView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0))
        }
        delay(1_000)
    }

    private suspend fun dispatchSwipe(durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(800)
        withContext(Dispatchers.Main) {
            val width = webView.width.toFloat().coerceAtLeast(1f)
            val height = webView.height.toFloat().coerceAtLeast(1f)
            // 손가락 시작 위치에 약간의 랜덤 편차
            val baseX = width / 2f + Random.nextFloat() * 30f - 15f
            val startY = height * (0.72f + Random.nextFloat() * 0.06f)
            val endY = height * (0.28f + Random.nextFloat() * 0.06f)
            val downTime = SystemClock.uptimeMillis()
            val steps = 40
            webView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, baseX, startY, 0))
            for (i in 1 until steps) {
                val t = i.toFloat() / steps.toFloat()
                // ease-in-out quadratic: 천천히 시작, 빠르게, 천천히 끝
                val eased = if (t < 0.5f) 2f * t * t else -1f + (4f - 2f * t) * t
                val now = downTime + (safeDuration * t).toLong()
                val y = startY + (endY - startY) * eased
                // 손가락이 완전히 직선이 아니라 자연스럽게 약간 흔들림
                val xDrift = baseX + sin(t * PI).toFloat() * 6f
                webView.dispatchTouchEvent(MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, xDrift, y, 0))
            }
            webView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + safeDuration, MotionEvent.ACTION_UP, baseX, endY, 0))
        }
        delay(Random.nextLong(400, 900))
    }

    private fun quoteJs(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
    }

    fun setBrowserMode(isChrome: Boolean) {
        chromeMode = isChrome
        webView.settings.userAgentString = if (isChrome) CHROME_137_UA else SAMSUNG_BROWSER_UA
    }

    private var chromeMode = false

    private fun samsungHeaders(referer: String? = null): Map<String, String> {
        return buildMap {
            put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            if (chromeMode) {
                put("sec-ch-ua", "\"Chromium\";v=\"137\", \"Google Chrome\";v=\"137\", \"Not/A)Brand\";v=\"24\"")
            } else {
                put("sec-ch-ua", "\"Chromium\";v=\"136\", \"Samsung Internet\";v=\"29\", \"Not.A/Brand\";v=\"99\"")
            }
            put("sec-ch-ua-mobile", "?1")
            put("sec-ch-ua-platform", "\"Android\"")
            referer?.let { put("Referer", it) }
        }
    }

    companion object {
        const val SAMSUNG_PACKAGE = "com.sec.android.app.sbrowser"
        const val NAVER_LOGIN_URL =
            "https://nid.naver.com/nidlogin.login?mode=form&url=https://www.naver.com/"

        // AI Reward 실측 기준: SamsungBrowser/29.0 Chrome/136 (런타임 오버라이드 스펙)
        const val SAMSUNG_BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) SamsungBrowser/29.0 Chrome/136.0.0.0 Mobile Safari/537.36"

        // G전략 전용 UA
        const val CHROME_137_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

        // navigator.webdriver 및 자동화 지문 제거
        const val NAVIGATOR_SPOOF_JS = """
            (function() {
              try {
                Object.defineProperty(navigator, 'webdriver', {get: () => undefined, configurable: true});
                Object.defineProperty(navigator, 'languages', {get: () => ['ko-KR','ko','en-US','en'], configurable: true});
                Object.defineProperty(navigator, 'platform', {get: () => 'Linux armv8l', configurable: true});
                Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 4, configurable: true});
                Object.defineProperty(navigator, 'deviceMemory', {get: () => 4, configurable: true});
                if (typeof window.chrome === 'undefined') {
                  window.chrome = {runtime: {}, app: {}, loadTimes: function(){}, csi: function(){}};
                }
              } catch(e) {}
            })();
        """

        private const val PREFS_NAME = "naver_session"
        private const val PREF_ID = "naver_id"
        private const val PREF_PW = "naver_pw"
        private const val PREF_SESSION_ACCOUNT_PREFIX = "session_account:"
        private const val MANUAL_SESSION_ACCOUNT = "__manual__"
        private val NAVER_COOKIE_URLS = listOf(
            "https://nid.naver.com",
            "https://www.naver.com",
            "https://m.naver.com",
            "https://naver.com",
        )
        private val NAVER_REUSABLE_COOKIE_NAMES = setOf(
            "NID_AUT",
            "NID_SES",
            "NID-JKL",
            "NID_JKL",
        )

        private fun sessionAccountKey(deviceName: String) = PREF_SESSION_ACCOUNT_PREFIX + deviceName

        private fun normalizeSessionAccount(accountAlias: String?): String {
            return accountAlias?.takeIf { it.isNotBlank() } ?: MANUAL_SESSION_ACCOUNT
        }
    }
}
