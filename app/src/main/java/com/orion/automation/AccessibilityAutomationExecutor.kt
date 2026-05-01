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
                Log.w(TAG, "typeText: no editable field found for '$nodeText'")
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
