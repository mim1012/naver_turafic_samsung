package com.navertraffic.samsung.strategy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BrowserAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private suspend fun clickByTitle(titleHint: String?): Boolean {
        val words = titleHint
            ?.split(Regex("\\s+"))
            ?.map { it.trim() }
            ?.filter { it.length >= 2 }
            .orEmpty()
        if (words.isEmpty()) return false

        repeat(8) {
            val node = findBestNode(rootInActiveWindow, words)
            if (node != null && clickNode(node)) return true
            swipeUp(700)
            delay(700)
        }
        return false
    }

    private fun findBestNode(root: AccessibilityNodeInfo?, words: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        fun visit(node: AccessibilityNodeInfo) {
            val text = listOfNotNull(node.text, node.contentDescription)
                .joinToString(" ")
            val score = words.count { text.contains(it, ignoreCase = true) }
            if (score > bestScore && score >= 2) {
                bestScore = score
                best = AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    visit(child)
                    child.recycle()
                }
            }
        }

        visit(root)
        return best
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                target.recycle()
                return true
            }
            val parent = target.parent
            target.recycle()
            target = parent
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    private suspend fun tap(x: Float, y: Float): Boolean {
        return dispatch(Path().apply { moveTo(x, y) }, 0, 90)
    }

    private suspend fun swipeUp(durationMs: Long): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.78f
        val endY = metrics.heightPixels * 0.32f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        return dispatch(path, 0, durationMs)
    }

    private suspend fun dispatch(path: Path, startMs: Long, durationMs: Long): Boolean {
        return withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Boolean>()
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, startMs, durationMs))
                .build()
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        done.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        done.complete(false)
                    }
                },
                null,
            )
            withTimeoutOrNull(durationMs + 2_000) { done.await() } == true
        }
    }

    companion object {
        @Volatile
        private var instance: BrowserAccessibilityService? = null

        fun isReady(): Boolean = instance != null

        suspend fun clickProduct(titleHint: String?): Boolean {
            return instance?.clickByTitle(titleHint) == true
        }

        suspend fun swipeDetail(durationMs: Long): Boolean {
            return instance?.swipeUp(durationMs) == true
        }
    }
}
