package com.orion.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.orion.R
import com.orion.core.ComparisonSession
import com.orion.core.KnownApp
import com.orion.core.ParsedGoal
import com.orion.core.Preference

private const val TAG = "Orion.ComparisonOverlay"
private val mainHandler = Handler(Looper.getMainLooper())

object ComparisonOverlay {

    @Volatile
    private var overlayView: View? = null

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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        val view = overlayView ?: return
        try {
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "dismiss: ${e.message}")
        }
        overlayView = null
    }

    private fun buildView(
        context: Context,
        session: ComparisonSession,
        onBook: (KnownApp) -> Unit,
    ): View {
        val dp = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    // Extend bottom past view.height so bottom corners are clipped away, leaving only top corners rounded
                    outline.setRoundRect(0, 0, view.width, view.height + (24 * dp).toInt(), 24 * dp)
                }
            }
            clipToOutline = true
            val h = (20 * dp).toInt()
            setPadding(h, (20 * dp).toInt(), h, (48 * dp).toInt())
        }

        // Drag handle
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * dp).toInt()
            }
            background = GradientDrawable().apply {
                setColor(0xFFE5E7EB.toInt())
                cornerRadius = 2 * dp
            }
        })

        // Title
        val titleText = when (val g = session.parsedGoal) {
            is ParsedGoal.RideRequest -> "Fares to \"${g.destination}\""
            is ParsedGoal.FoodOrder ->
                "Prices from \"${g.restaurant}\"${g.item?.let { " for $it" } ?: ""}"
        }
        root.addView(TextView(context).apply {
            text = titleText
            setTextColor(0xFF1F2937.toInt())
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * dp).toInt() }
        })

        // Subtitle
        root.addView(TextView(context).apply {
            text = "Orion compared ${session.collectedFares.size} apps for you"
            setTextColor(0xFF6B7280.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() }
        })

        val cheapest = session.cheapestApp
        val fastest = session.fastestApp
        val preference = when (session.parsedGoal) {
            is ParsedGoal.RideRequest -> session.parsedGoal.preference
            is ParsedGoal.FoodOrder -> session.parsedGoal.preference
        }
        val preferredApp = when (preference) {
            Preference.FASTEST -> fastest ?: cheapest
            else -> cheapest
        }

        // Result rows — each row is tappable to book that app
        for ((pkg, fare) in session.collectedFares) {
            val app = session.apps.firstOrNull { it.packageName == pkg } ?: continue
            val isCheapest = app == cheapest
            val isFastest = app == fastest && fastest != cheapest
            val isPreferred = app == preferredApp

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (isPreferred) 0xFFF0FDF4.toInt() else 0xFFF9FAFB.toInt())
                    if (isPreferred) setStroke((1 * dp).toInt(), 0xFFBBF7D0.toInt())
                    cornerRadius = 10 * dp
                }
                val p = (12 * dp).toInt()
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
                isClickable = true
                isFocusable = true
                setOnClickListener { dismiss(); onBook(app) }
            }

            val leftCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            leftCol.addView(TextView(context).apply {
                text = app.displayName
                setTextColor(0xFF1F2937.toInt())
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            // Always show cheapest/fastest badges; preferred one shown in bold color
            if (isCheapest) {
                leftCol.addView(TextView(context).apply {
                    text = "✓ CHEAPEST"
                    setTextColor(if (preference != Preference.FASTEST) 0xFF16A34A.toInt() else 0xFF6B7280.toInt())
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                })
            }
            if (isFastest) {
                leftCol.addView(TextView(context).apply {
                    text = "⚡ FASTEST"
                    setTextColor(if (preference == Preference.FASTEST) 0xFF2563EB.toInt() else 0xFF6B7280.toInt())
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                })
            }
            row.addView(leftCol)

            val rightCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            rightCol.addView(TextView(context).apply {
                text = fare.price
                setTextColor(0xFF1F2937.toInt())
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.END
            })
            fare.eta?.let { eta ->
                rightCol.addView(TextView(context).apply {
                    text = "~$eta min"
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 12f
                    gravity = Gravity.END
                })
            }
            rightCol.addView(TextView(context).apply {
                text = "Book →"
                setTextColor(if (isPreferred) 0xFF16A34A.toInt() else 0xFFF97316.toInt())
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.END
            })
            row.addView(rightCol)
            root.addView(row)
        }

        // Primary CTA — book the preferred app (fastest or cheapest based on user intent)
        val bookTarget = preferredApp
            ?: session.apps.firstOrNull { session.collectedFares.containsKey(it.packageName) }
        if (bookTarget != null) {
            val ctaLabel = when (preference) {
                Preference.FASTEST -> "Book fastest — ${bookTarget.displayName} →"
                Preference.CHEAPEST -> "Book cheapest — ${bookTarget.displayName} →"
                Preference.NONE -> "Book with ${bookTarget.displayName} →"
            }
            root.addView(Button(context).apply {
                text = ctaLabel
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_gradient_brand)
                setOnClickListener { dismiss(); onBook(bookTarget) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (16 * dp).toInt()
                    bottomMargin = (8 * dp).toInt()
                }
            })
        }

        // Dismiss link
        root.addView(TextView(context).apply {
            text = "Dismiss"
            setTextColor(0xFF6B7280.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { dismiss() }
        })

        return root
    }
}
