package com.orion.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.orion.core.ExecutionResult

@Suppress("DEPRECATION")
class AccessibilityAutomationExecutor(private val service: AccessibilityService) : AutomationExecutor {

    companion object {
        private const val TAG = "Orion.Executor"
    }

    private fun findNodesByTextDFS(node: AccessibilityNodeInfo, targetText: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val lower = targetText.lowercase()
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains(lower) || desc.contains(lower)) {
            result.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            result.addAll(findNodesByTextDFS(child, targetText))
            child.recycle()
        }
        return result
    }

    override fun tapNode(nodeText: String): ExecutionResult {
        val root = service.rootInActiveWindow ?: return ExecutionResult(false, errorCode = "NO_ROOT")
        return try {
            var candidates = root.findAccessibilityNodeInfosByText(nodeText)
            if (candidates.isNullOrEmpty()) {
                Log.d(TAG, "tapNode: standard search empty for '$nodeText', trying DFS")
                candidates = findNodesByTextDFS(root, nodeText)
            }
            if (candidates.isNullOrEmpty()) {
                // Composite labels (e.g. "Where to? Schedule a Ride Later") won't match any
                // single a11y node — try each sentence/clause segment individually.
                val segments = nodeText.split(Regex("(?<=[?!])\\s+|,\\s*|\n"))
                    .map { it.trim() }.filter { it.length >= 3 && it != nodeText }
                for (seg in segments) {
                    var segCandidates = root.findAccessibilityNodeInfosByText(seg)
                    if (segCandidates.isNullOrEmpty()) segCandidates = findNodesByTextDFS(root, seg)
                    if (!segCandidates.isNullOrEmpty()) {
                        Log.d(TAG, "tapNode: retrying with segment '$seg'")
                        candidates = segCandidates
                        break
                    }
                }
            }
            if (candidates.isNullOrEmpty()) {
                return ExecutionResult(false, errorCode = "NODE_NOT_FOUND")
            }
            var tapped = false
            outer@ for (node in candidates) {
                if (node.isClickable) {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    if (ok) { tapped = true; break@outer }
                    continue
                }
                var ancestor = node.parent
                while (ancestor != null) {
                    if (ancestor.isClickable) {
                        val ok = ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        ancestor.recycle()
                        if (ok) tapped = true
                        break
                    }
                    val next = ancestor.parent
                    ancestor.recycle()
                    ancestor = next
                }
                node.recycle()
                if (tapped) break@outer
            }
            ExecutionResult(tapped, errorCode = if (tapped) null else "CLICK_FAILED")
        } finally {
            root.recycle()
        }
    }

    override fun dispatchTap(x: Float, y: Float): ExecutionResult {
        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .setDisplayId(android.view.Display.DEFAULT_DISPLAY)
            .build()
        val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { Log.i(TAG, "dispatchTap: onCompleted ($x, $y)") }
            override fun onCancelled(g: GestureDescription) { Log.w(TAG, "dispatchTap: onCancelled ($x, $y)") }
        }, null)
        if (!dispatched) {
            Log.e(TAG, "dispatchGesture returned false — service not connected?")
            return ExecutionResult(false, errorCode = "DISPATCH_FAILED")
        }
        Thread.sleep(200)
        return ExecutionResult(true)
    }

    override fun swipe(direction: String, screenWidth: Int, screenHeight: Int): ExecutionResult {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return ExecutionResult(false, errorCode = "BAD_SCREEN_SIZE")
        }
        val cx = screenWidth / 2f
        val (yStart, yEnd) = when (direction.lowercase()) {
            "up" -> screenHeight * 0.7f to screenHeight * 0.3f
            "down" -> screenHeight * 0.3f to screenHeight * 0.7f
            else -> {
                Log.w(TAG, "swipe: unknown direction '$direction' — defaulting to up")
                screenHeight * 0.7f to screenHeight * 0.3f
            }
        }
        val path = Path().apply { moveTo(cx, yStart); lineTo(cx, yEnd) }
        val durationMs = 400L
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .setDisplayId(android.view.Display.DEFAULT_DISPLAY)
            .build()
        val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { Log.i(TAG, "swipe: onCompleted dir=$direction") }
            override fun onCancelled(g: GestureDescription) { Log.w(TAG, "swipe: onCancelled dir=$direction") }
        }, null)
        if (!dispatched) {
            Log.e(TAG, "swipe: dispatchGesture returned false")
            return ExecutionResult(false, errorCode = "DISPATCH_FAILED")
        }
        Thread.sleep(durationMs + 100L)
        return ExecutionResult(true)
    }

    override fun pressHome(): ExecutionResult {
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Log.i(TAG, "pressHome → $ok")
        return ExecutionResult(ok, errorCode = if (ok) null else "HOME_FAILED")
    }

    fun dispatchText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "dispatchText('$text') → success=$success")
        return success
    }

    fun typeText(nodeText: String, text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return try {
            // Prefer an editable node matching nodeText; fall back to regex DFS across the tree
            var candidates = root.findAccessibilityNodeInfosByText(nodeText)
            if (candidates.isNullOrEmpty()) candidates = findNodesByTextDFS(root, nodeText)
            val matchedEditable = candidates?.firstOrNull { it.isEditable }
            val target = if (matchedEditable != null) {
                candidates?.filterNot { it === matchedEditable }?.forEach { it.recycle() }
                matchedEditable
            } else {
                candidates?.forEach { it.recycle() }
                findEditableByRegex(root, nodeText)
            }
            if (target == null) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                Log.w(TAG, "typeText: no editable field found for '$nodeText' — focused=${focused?.let { "cls=${it.className} text='${it.text}' desc='${it.contentDescription}' editable=${it.isEditable}" } ?: "none"}")
                if (focused != null && focused.isEditable) {
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    focused.recycle()
                    Log.i(TAG, "typeText: used focused node for '$text' → success=$success")
                    return success
                }
                focused?.recycle()
                return false
            }
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            target.recycle()
            Log.i(TAG, "typeText('$nodeText', '$text') → success=$success")
            success
        } finally {
            root.recycle()
        }
    }

    private fun findEditableByRegex(root: AccessibilityNodeInfo, nodeText: String): AccessibilityNodeInfo? {
        val pattern = nodeText.trim().split("\\s+".toRegex())
            .joinToString(".*") { Regex.escape(it) }
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        return findEditableByRegexDFS(root, regex)
    }

    private fun findEditableByRegexDFS(node: AccessibilityNodeInfo, regex: Regex): AccessibilityNodeInfo? {
        if (node.isEditable) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (regex.containsMatchIn(text) || regex.containsMatchIn(desc)) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableByRegexDFS(child, regex)
            child.recycle()
            if (found != null) return found
        }
        return null
    }


    fun typeTextFocused(text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused == null) {
                Log.w(TAG, "typeTextFocused: no input-focused node found")
                return false
            }
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle()
            Log.i(TAG, "typeTextFocused('$text') → success=$success")
            success
        } finally {
            root.recycle()
        }
    }

    override fun isScreenSecure(bitmap: Bitmap): Boolean {
        val cx = bitmap.width / 2
        val cy = bitmap.height / 2
        val center = bitmap.getPixel(cx, cy)
        if (Color.red(center) >= 30 || Color.green(center) >= 30 || Color.blue(center) >= 30) {
            Log.d(TAG, "isScreenSecure: center not near-black — fast false")
            return false
        }
        val fractions = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        var blackCount = 0
        for (fx in fractions) for (fy in fractions) {
            val px = bitmap.getPixel((bitmap.width * fx).toInt(), (bitmap.height * fy).toInt())
            if (Color.red(px) < 30 && Color.green(px) < 30 && Color.blue(px) < 30) blackCount++
        }
        val secure = blackCount >= 24
        Log.d(TAG, "isScreenSecure: grid blackCount=$blackCount/25 secure=$secure")
        return secure
    }
}
