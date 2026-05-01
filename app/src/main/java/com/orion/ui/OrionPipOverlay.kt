package com.orion.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

private const val TAG = "Orion.PipOverlay"
private val mainHandler = Handler(Looper.getMainLooper())

object OrionPipOverlay {

    private var pillView: android.view.View? = null
    @Volatile
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
            val dp = appCtx.resources.displayMetrics.density
            val size = (48 * dp).toInt()
            val view = buildDot(appCtx, size)
            val metrics = appCtx.resources.displayMetrics
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = metrics.widthPixels - size - (24 * dp).toInt()
                y = metrics.heightPixels - size - (120 * dp).toInt()
            }
            makeDraggable(view, wm, params)
            wm.addView(view, params)
            pillView = view
            isShowing = true
            Log.i(TAG, "Pip shown")
        }
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        val view = pillView ?: return
        try {
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "dismiss: view not attached — ${e.message}")
        }
        pillView = null
        isShowing = false
    }

    private fun buildDot(context: Context, size: Int): android.view.View {
        val dp = context.resources.displayMetrics.density
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF97316.toInt(), 0xFFEF4444.toInt())
            ).apply { shape = GradientDrawable.OVAL }
            elevation = 8 * dp
            addView(TextView(context).apply {
                text = "✦"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener {
                val intent = Intent(context, com.orion.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(intent)
            }
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
                    params.y = startParamY + (event.rawY - startY).toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }
}
