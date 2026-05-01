package com.orion.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.orion.core.ComparisonSession
import com.orion.core.KnownApp
import com.orion.core.ParsedGoal

private const val TAG = "Orion.ComparisonOverlay"
private val mainHandler = Handler(Looper.getMainLooper())

object ComparisonOverlay {

    private var overlayView: android.view.View? = null

    // Call from any thread. onBook receives the KnownApp the user chose.
    fun show(context: Context, session: ComparisonSession, onBook: (KnownApp) -> Unit) {
        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot show overlay")
            return
        }
        val appCtx = context.applicationContext
        mainHandler.post {
            dismiss()
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = buildView(appCtx, session, onBook)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM }
            wm.addView(view, params)
            overlayView = view
            Log.i(TAG, "Overlay shown: ${session.collectedFares.size} results")
        }
    }

    fun dismiss() {
        val view = overlayView ?: return
        try {
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "dismiss: ${e.message}")
        }
        overlayView = null
    }

    private fun buildView(
        context: Context,
        session: ComparisonSession,
        onBook: (KnownApp) -> Unit,
    ): android.view.View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2000000.toInt())
            setPadding(32, 24, 32, 48)
        }

        val titleText = when (val g = session.parsedGoal) {
            is ParsedGoal.RideRequest -> "Fares to \"${g.destination}\""
            is ParsedGoal.FoodOrder ->
                "Prices from \"${g.restaurant}\"${g.item?.let { " for ${it}" } ?: ""}"
        }
        root.addView(TextView(context).apply {
            text = titleText
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(0, 0, 0, 16)
        })

        val cheapest = session.cheapestApp
        val fastest = session.fastestApp

        for ((pkg, fare) in session.collectedFares) {
            val app = session.apps.firstOrNull { it.packageName == pkg } ?: continue
            val badges = buildList {
                if (app == cheapest) add("CHEAPEST")
                if (app == fastest && fastest != cheapest) add("FASTEST")
            }.joinToString(" · ")
            val badgeSuffix = if (badges.isNotEmpty()) "  [$badges]" else ""
            val etaText = fare.eta?.let { " · ${it}min" } ?: ""

            root.addView(TextView(context).apply {
                text = "${app.displayName}: ${fare.price}$etaText$badgeSuffix"
                setTextColor(if (app == cheapest) 0xFF7CFC00.toInt() else 0xFFCCCCCC.toInt())
                textSize = 15f
                setPadding(0, 6, 0, 6)
            })
        }

        val bookTarget = cheapest
            ?: session.apps.firstOrNull { session.collectedFares.containsKey(it.packageName) }
        if (bookTarget != null) {
            root.addView(Button(context).apply {
                text = "Book with ${bookTarget.displayName}"
                setOnClickListener { dismiss(); onBook(bookTarget) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
            })
        }

        root.addView(Button(context).apply {
            text = "Dismiss"
            setOnClickListener { dismiss() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        })

        return root
    }
}
