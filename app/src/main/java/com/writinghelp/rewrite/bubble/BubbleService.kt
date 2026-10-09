package com.writinghelp.rewrite.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.writinghelp.rewrite.MainActivity
import com.writinghelp.rewrite.R
import com.writinghelp.rewrite.data.REFINEMENT_CHIPS
import com.writinghelp.rewrite.data.RewriteMode
import com.writinghelp.rewrite.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the floating bubble overlay. The bubble is never focusable,
 * so the text field the user is working in keeps input focus and
 * [WritingAccessibilityService] can read/replace it.
 *
 * With "Keep in notification bar" on, the service (and its notification) stays up even while
 * the bubble is hidden, and tapping the notification shows or hides the bubble.
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var controller: BubbleController
    private lateinit var settings: SettingsRepository
    private var overlayShown = false

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapseToBubble() }

    private lateinit var panel: LinearLayout
    private lateinit var menu: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var stripStatus: TextView
    private lateinit var stripActions: LinearLayout
    private lateinit var btnRedo: Button
    private lateinit var btnUndo: Button

    /** Which screen edge the bubble is resting against; drives how the panel expands. */
    private var pinnedRightEdge = true

    override fun onCreate() {
        super.onCreate()
        // Must call startForeground() promptly after startForegroundService(), even on the
        // bail-out path, or the system kills us with a "did not call startForeground" crash.
        settings = SettingsRepository(applicationContext)
        startForegroundNotification()

        isRunning = true
        controller = BubbleController(settings)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.bubbleEnabled = false
                settings.keepInNotification = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> settings.bubbleEnabled = !settings.bubbleEnabled
        }
        applyState()
        return START_STICKY
    }

    /** Shows or hides the bubble to match settings and refreshes the notification, or stops. */
    private fun applyState() {
        val show = settings.bubbleEnabled && Settings.canDrawOverlays(this)
        if (!show && !settings.keepInNotification) {
            stopSelf()
            return
        }
        if (show && !overlayShown) addOverlay()
        if (!show && overlayShown) removeOverlay()
        startForegroundNotification()
    }

    /** Long-pressing the bubble hides it (and stops the service unless it's kept in the bar). */
    private fun hideBubble() {
        settings.bubbleEnabled = false
        applyState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        if (overlayShown) runCatching { windowManager.removeView(rootView) }
        overlayShown = false
        super.onDestroy()
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacksAndMessages(null)
        controller.reset()
        runCatching { windowManager.removeView(rootView) }
        overlayShown = false
    }

    // ---- overlay setup ---------------------------------------------------------------------

    private fun addOverlay() {
        rootView = LayoutInflater.from(this).inflate(R.layout.bubble_overlay, null)
        panel = rootView.findViewById(R.id.panel)
        menu = rootView.findViewById(R.id.menu)
        strip = rootView.findViewById(R.id.strip)
        stripStatus = rootView.findViewById(R.id.strip_status)
        stripActions = rootView.findViewById(R.id.strip_actions)
        btnRedo = rootView.findViewById(R.id.btn_redo)
        btnUndo = rootView.findViewById(R.id.btn_undo)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start snug against the right edge, roughly one bubble-width in so it's fully visible.
            x = resources.displayMetrics.widthPixels - (64 * resources.displayMetrics.density).toInt()
            y = resources.displayMetrics.heightPixels / 3
        }

        windowManager.addView(rootView, params)
        overlayShown = true
        rootView.post { snapToEdge() }

        rootView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                collapseToBubble()
                true
            } else false
        }

        attachBubbleTouch(rootView.findViewById(R.id.bubble))

        rootView.findViewById<Button>(R.id.btn_fix).setOnClickListener { beginRewrite(RewriteMode.FIX_GRAMMAR) }
        rootView.findViewById<Button>(R.id.btn_rewrite).setOnClickListener { beginRewrite(RewriteMode.REWRITE) }
        rootView.findViewById<Button>(R.id.btn_translate).setOnClickListener { beginRewrite(RewriteMode.TRANSLATE) }
        btnRedo.setOnClickListener { runStep { controller.redo() } }
        btnUndo.setOnClickListener { runStep { controller.undo() } }

        collapseToBubble()
    }

    private fun attachBubbleTouch(bubble: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startParamX = 0
        var startParamY = 0
        var dragging = false
        val longPress = Runnable { hideBubble() }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startParamX = params.x; startParamY = params.y
                    dragging = false
                    mainHandler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = (startParamX + dx).toInt()
                        params.y = (startParamY + dy).toInt()
                        windowManager.updateViewLayout(rootView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    if (dragging) snapToEdge() else onBubbleTapped()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val screenW = resources.displayMetrics.widthPixels
        val mid = screenW / 2
        pinnedRightEdge = params.x + rootView.width / 2 >= mid
        params.x = if (pinnedRightEdge) screenW - rootView.width else 0
        windowManager.updateViewLayout(rootView, params)
    }

    /**
     * After the panel expands the view is wider; keep it fully on screen. When the bubble is
     * pinned to the right edge the window must slide left by the panel's width (it grows
     * rightward from a fixed x otherwise, pushing the options off-screen).
     */
    private fun repositionForPanel() {
        rootView.post {
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val bubbleW = (72 * resources.displayMetrics.density).toInt()

            // Keep the whole expanded view within the screen: if the action row is wider than
            // the space left of the bubble, cap the scroll view so it scrolls instead of clipping.
            val maxPanelW = screenW - bubbleW
            val scroll = rootView.findViewById<View>(R.id.strip_scroll)
            if (scroll.width > maxPanelW && maxPanelW > 0) {
                scroll.layoutParams = scroll.layoutParams.apply { width = maxPanelW }
                scroll.requestLayout()
                rootView.post { repositionForPanel() }
                return@post
            }

            val w = rootView.width
            val h = rootView.height
            params.x = (if (pinnedRightEdge) screenW - w else 0).coerceIn(0, maxOf(0, screenW - w))
            params.y = params.y.coerceIn(0, maxOf(0, screenH - h))
            windowManager.updateViewLayout(rootView, params)
        }
    }

    // ---- interaction --------------------------------------------------------------------

    private fun onBubbleTapped() {
        if (panel.visibility == View.VISIBLE) {
            collapseToBubble()
        } else if (controller.hasActiveResult) {
            showStrip(getString(R.string.bubble_hint_done), actions = true)
        } else {
            showMenu()
        }
    }

    private fun beginRewrite(mode: RewriteMode) {
        val acc = WritingAccessibilityService.instance
        if (acc == null) {
            showStrip(getString(R.string.bubble_status_needs_accessibility), actions = false)
            return
        }
        val field = acc.captureFocusedField()
        if (field == null) {
            showStrip(getString(R.string.bubble_hint_no_field), actions = false)
            return
        }
        showStrip(getString(R.string.bubble_working), actions = false)
        scope.launch { handleOutcome(controller.start(mode, field)) }
    }

    /** Shared path for Redo / Undo / tone-chip buttons. */
    private fun runStep(block: suspend () -> BubbleController.Outcome) {
        showStrip(getString(R.string.bubble_working), actions = false)
        scope.launch { handleOutcome(block()) }
    }

    private fun handleOutcome(outcome: BubbleController.Outcome) {
        when (outcome) {
            is BubbleController.Outcome.Write -> {
                val ok = WritingAccessibilityService.instance?.writeText(
                    outcome.newFullText, outcome.cursorPos
                ) ?: false
                if (ok) {
                    showStrip(
                        getString(outcome.statusRes),
                        actions = controller.hasActiveResult,
                        chips = controller.hasActiveResult && controller.mode == RewriteMode.REWRITE
                    )
                    if (!controller.hasActiveResult) scheduleCollapse(COLLAPSE_SHORT_MS)
                } else {
                    showStrip(getString(R.string.bubble_hint_lost_field), actions = false)
                }
            }
            is BubbleController.Outcome.Message -> {
                showStrip(
                    outcome.rawMessage ?: getString(outcome.statusRes),
                    actions = controller.hasActiveResult,
                    chips = controller.hasActiveResult && controller.mode == RewriteMode.REWRITE
                )
            }
        }
    }

    // ---- view states ------------------------------------------------------------------

    private fun showMenu() {
        panel.visibility = View.VISIBLE
        menu.visibility = View.VISIBLE
        strip.visibility = View.GONE
        repositionForPanel()
        scheduleCollapse(COLLAPSE_IDLE_MS)
    }

    private fun showStrip(status: String, actions: Boolean, chips: Boolean = false) {
        panel.visibility = View.VISIBLE
        menu.visibility = View.GONE
        strip.visibility = View.VISIBLE
        // Let the action row size to its content again; repositionForPanel() re-caps if needed.
        rootView.findViewById<View>(R.id.strip_scroll).apply {
            if (layoutParams.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                layoutParams = layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
            }
        }
        stripStatus.text = status
        btnRedo.visibility = if (actions) View.VISIBLE else View.GONE
        btnUndo.visibility = if (actions) View.VISIBLE else View.GONE
        rebuildChips(chips)
        repositionForPanel()
        scheduleCollapse(COLLAPSE_IDLE_MS)
    }

    private fun rebuildChips(show: Boolean) {
        // strip_actions holds [redo, undo, <chips...>] — drop anything past the two fixed buttons.
        while (stripActions.childCount > 2) stripActions.removeViewAt(2)
        if (!show) return
        REFINEMENT_CHIPS.forEach { chip ->
            val b = Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
                text = chip.label
                setTextColor(0xFF1565C0.toInt())
                setOnClickListener { runStep { controller.refine(chip.instruction) } }
            }
            stripActions.addView(b)
        }
    }

    private fun collapseToBubble() {
        mainHandler.removeCallbacks(collapseRunnable)
        panel.visibility = View.GONE
        menu.visibility = View.GONE
        strip.visibility = View.GONE
        controller.reset()
        // The window shrank back to just the bubble; re-seat it flush against its edge.
        rootView.post {
            val screenW = resources.displayMetrics.widthPixels
            params.x = if (pinnedRightEdge) screenW - rootView.width else 0
            windowManager.updateViewLayout(rootView, params)
        }
    }

    private fun scheduleCollapse(delayMs: Long) {
        mainHandler.removeCallbacks(collapseRunnable)
        mainHandler.postDelayed(collapseRunnable, delayMs)
    }

    // ---- foreground notification ----------------------------------------------------

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bubble_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, BubbleService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (settings.keepInNotification) {
            // Tap toggles the bubble. Without the overlay permission it can't be shown, so the
            // tap opens the app instead, where the user can grant it.
            val canOverlay = Settings.canDrawOverlays(this)
            val text = when {
                !canOverlay -> R.string.notif_needs_permission
                overlayShown -> R.string.notif_bubble_shown
                else -> R.string.notif_bubble_hidden
            }
            builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(text))
                .setContentIntent(if (canOverlay) toggleIntent else openIntent)
                .addAction(0, getString(R.string.notif_turn_off), stopIntent)
                .addAction(0, getString(R.string.notif_open_app), openIntent)
        } else {
            builder
                .setContentTitle(getString(R.string.bubble_notification_title))
                .setContentText(getString(R.string.bubble_notification_text))
                .setContentIntent(stopIntent)
                .addAction(0, getString(R.string.bubble_notification_text), stopIntent)
                .addAction(0, getString(R.string.app_name), openIntent)
        }
        val notification: Notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_STOP = "com.writinghelp.rewrite.bubble.STOP"
        const val ACTION_TOGGLE = "com.writinghelp.rewrite.bubble.TOGGLE"
        private const val CHANNEL_ID = "writinghelp_bubble"
        private const val NOTIFICATION_ID = 4711
        private const val LONG_PRESS_MS = 600L
        private const val COLLAPSE_IDLE_MS = 8_000L
        private const val COLLAPSE_SHORT_MS = 1_500L

        /** Starts the service, or tells a running one to re-read its settings. */
        fun start(context: Context) {
            val intent = Intent(context, BubbleService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}
