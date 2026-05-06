package com.navertraffic.samsung.strategy

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SamsungWebViewManager(
    private val webView: WebView,
    private val log: (String) -> Unit,
) {
    private var pageLoad = CompletableDeferred<Unit>()
    private var lastLoadHadError = false

    @SuppressLint("SetJavaScriptEnabled")
    fun initialize() {
        WebView.setWebContentsDebuggingEnabled(true)
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
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!pageLoad.isCompleted) pageLoad.complete(Unit)
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
        }
    }

    suspend fun loadAndWait(url: String, timeoutMs: Long = 30_000) {
        repeat(3) { attempt ->
            pageLoad = CompletableDeferred()
            lastLoadHadError = false
            log("URL 로딩: $url")
            withContext(Dispatchers.Main) {
                webView.loadUrl(url, samsungHeaders())
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

    fun setNaverCookie(cookie: String) {
        if (cookie.isBlank()) return
        val manager = CookieManager.getInstance()
        cookie.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .forEach { item ->
                manager.setCookie(".naver.com", "$item; domain=.naver.com; path=/")
            }
        manager.flush()
        log("네이버 쿠키 주입 완료")
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
        if (isNaverLoggedIn()) {
            log("네이버 로그인 세션 유지됨")
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
        if (!tapLoginButton()) {
            log("네이버 로그인 버튼 터치 실패")
            return false
        }

        repeat(45) {
            delay(1_000)
            if (currentUrl()?.contains("nidlogin.login") != true) {
                CookieManager.getInstance().flush()
                log("네이버 로그인 완료")
                return true
            }
        }
        log("네이버 로그인 타임아웃")
        return false
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

    suspend fun clickMidLink(mid: String): Boolean {
        if (mid.isBlank()) return false
        val js = """
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
        val result = evaluateText(js)
        if (!result.startsWith("found|")) {
            log("MID($mid) 상품 미노출")
            return false
        }
        val parts = result.split("|")
        val method = parts.getOrNull(1) ?: "unknown"
        val cssX = parts.getOrNull(2)?.toFloatOrNull() ?: return false
        val cssY = parts.getOrNull(3)?.toFloatOrNull() ?: return false
        log("MID($mid) 상품 발견: $method")
        tapCssPoint(cssX, cssY)
        return true
    }

    suspend fun swipeDetail(durationMs: Long = 2_000) {
        dispatchSwipe(durationMs)
    }

    suspend fun resetSurface() {
        withContext(Dispatchers.Main) {
            webView.loadUrl("about:blank")
            webView.clearHistory()
        }
        delay(500)
        log("브라우저 화면 재시작: 쿠키 세션 유지")
    }

    private suspend fun evaluateText(script: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
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
        val safeDuration = durationMs.coerceAtLeast(200)
        withContext(Dispatchers.Main) {
            val width = webView.width.toFloat().coerceAtLeast(1f)
            val height = webView.height.toFloat().coerceAtLeast(1f)
            val x = width / 2f
            val startY = height * 0.78f
            val endY = height * 0.32f
            val downTime = SystemClock.uptimeMillis()
            webView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0))
            val steps = 12
            for (i in 1 until steps) {
                val t = i.toFloat() / steps.toFloat()
                val now = downTime + (safeDuration * t).toLong()
                val y = startY + (endY - startY) * t
                webView.dispatchTouchEvent(MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, x, y, 0))
            }
            webView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + safeDuration, MotionEvent.ACTION_UP, x, endY, 0))
        }
        delay(500)
    }

    private fun quoteJs(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
    }

    private fun samsungHeaders(): Map<String, String> {
        return mapOf(
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        )
    }

    companion object {
        const val SAMSUNG_PACKAGE = "com.sec.android.app.sbrowser"
        const val NAVER_LOGIN_URL =
            "https://nid.naver.com/nidlogin.login?mode=form&url=https://www.naver.com/"
        const val SAMSUNG_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991N) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) SamsungBrowser/23.0 Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
