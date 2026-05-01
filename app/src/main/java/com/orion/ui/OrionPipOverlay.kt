package com.orion.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.orion.R

private const val TAG = "Orion.PipOverlay"
private val mainHandler = Handler(Looper.getMainLooper())

object OrionPipOverlay {

    private var pillView: android.view.View? = null
    var isShowing: Boolean = false
        private set

    fun show(context: Context, statusText: String = "Orion is working…") {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — pip not shown")
            return
        }
        val appCtx = context.applicationContext
        mainHandler.post {
            dismiss()
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = buildPill(appCtx, statusText)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (80 * appCtx.resources.displayMetrics.density).toInt()
            }
            makeDraggable(view, wm, params)
            wm.addView(view, params)
            pillView = view
            isShowing = true
            Log.i(TAG, "Pip shown")
        }
    }

    fun dismiss() {
        val view = pillView ?: return
        try {
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "dismiss: ${e.message}")
        }
        pillView = null
        isShowing = false
    }

    private fun buildPill(context: Context, text: String): android.view.View {
        val dp = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_pip_pill)
            val hPad = (20 * dp).toInt()
            val vPad = (12 * dp).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (8 * dp).toInt(), (8 * dp).toInt()
                ).apply { marginEnd = (10 * dp).toInt() }
                setBackgroundResource(R.drawable.bg_pip_dot)
            })
            addView(TextView(context).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            })
        }
    }

    private fun makeDraggable(
        view: android.view.View,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
    ) {
        var startX = 0f
        var startY = 0f
        var startParamX = 0
        var startParamY = 0
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    startParamX = params.x; startParamY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startParamX + (event.rawX - startX).toInt()
                    params.y = startParamY - (event.rawY - startY).toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }
}
