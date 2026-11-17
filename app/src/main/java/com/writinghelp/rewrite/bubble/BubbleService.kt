package com.writinghelp.rewrite.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the floating bubble overlay. The bubble is normally not
 * focusable, so the text field the user is working in keeps input focus and
 * [WritingAccessibilityService] can read/replace it. The one exception is the "Write for me"
 * box: while the user types a request the overlay takes focus, and gives it back on submit.
 *
 * A second overlay window, the "Why these changes" card, opens above or below the bubble when
 * the user taps "Why?" after Fix Grammar / AI Rewrite.
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
    private lateinit var menuEmpty: TextView
    private lateinit var writeBox: LinearLayout
    private lateinit var writeInput: EditText
    private lateinit var strip: LinearLayout
    private lateinit var stripStatus: TextView
    private lateinit var stripActions: LinearLayout
    private lateinit var btnRedo: Button
    private lateinit var btnUndo: Button
    private lateinit var btnExplain: Button
    private lateinit var menuButtons: Map<RewriteMode, Button>

    /** Which screen edge the bubble is resting against; drives how the panel expands. */
    private var pinnedRightEdge = true

    /** Field captured when "Write for me" was tapped (null: no field, result goes to the clipboard). */
    private var pendingWriteField: WritingAccessibilityService.FocusedField? = null
    private var inWriteBox = false
    private var yBeforeWriteBox: Int? = null

    private var explainView: View? = null
    private var explainParams: WindowManager.LayoutParams? = null
    private var explainJob: Job? = null

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
        closeExplanation()
        scope.cancel()
        if (overlayShown) runCatching { windowManager.removeView(rootView) }
        overlayShown = false
        super.onDestroy()
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacksAndMessages(null)
        closeExplanation()
        controller.reset()
        runCatching { windowManager.removeView(rootView) }
        overlayShown = false
    }

    // ---- overlay setup ---------------------------------------------------------------------

    private fun addOverlay() {
        rootView = LayoutInflater.from(this).inflate(R.layout.bubble_overlay, null)
        panel = rootView.findViewById(R.id.panel)
        menu = rootView.findViewById(R.id.menu)
        menuEmpty = rootView.findViewById(R.id.menu_empty)
        writeBox = rootView.findViewById(R.id.write_box)
        writeInput = rootView.findViewById(R.id.write_input)
        strip = rootView.findViewById(R.id.strip)
        stripStatus = rootView.findViewById(R.id.strip_status)
        stripActions = rootView.findViewById(R.id.strip_actions)
        btnRedo = rootView.findViewById(R.id.btn_redo)
        btnUndo = rootView.findViewById(R.id.btn_undo)
        btnExplain = rootView.findViewById(R.id.btn_explain)
        menuButtons = mapOf(
            RewriteMode.FIX_GRAMMAR to rootView.findViewById(R.id.btn_fix),
            RewriteMode.REWRITE to rootView.findViewById(R.id.btn_rewrite),
            RewriteMode.TRANSLATE to rootView.findViewById(R.id.btn_translate),
            RewriteMode.WRITE to rootView.findViewById(R.id.btn_write)
        )

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // Touches outside the overlay always reach the app behind, even while the
                // Write box has made the overlay focusable.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start snug against the right edge, roughly one bubble-width in so it's fully visible.
            x = resources.displayMetrics.widthPixels - dp(64)
            y = resources.displayMetrics.heightPixels / 3
        }

        windowManager.addView(rootView, params)
        overlayShown = true
        rootView.post { snapToEdge() }

        rootView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                // A touch on the "Why" card also counts as outside this window; keep the panel.
                if (explainView == null) collapseToBubble()
                true
            } else false
        }

        attachBubbleTouch(rootView.findViewById(R.id.bubble))

        menuButtons.forEach { (mode, button) ->
            button.setOnClickListener {
                if (mode == RewriteMode.WRITE) openWriteBox() else beginRewrite(mode)
            }
        }
        rootView.findViewById<Button>(R.id.btn_write_go).setOnClickListener { submitWrite() }
        rootView.findViewById<Button>(R.id.btn_write_cancel).setOnClickListener { collapseToBubble() }
        writeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitWrite()
                true
            } else false
        }
        writeInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                collapseToBubble()
                true
            } else false
        }
        btnRedo.setOnClickListener { runStep { controller.redo() } }
        btnUndo.setOnClickListener { runStep { controller.undo() } }
        btnExplain.setOnClickListener { toggleExplanation() }

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
        positionExplanation()
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
            val bubbleW = dp(72)

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
            positionExplanation()
        }
    }

    // ---- interaction --------------------------------------------------------------------

    private fun onBubbleTapped() {
        if (panel.visibility == View.VISIBLE) {
            collapseToBubble()
        } else if (controller.hasActiveResult) {
            showStrip(getString(R.string.bubble_hint_done), actions = true, chips = chipsWanted())
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

    /**
     * "Write for me": remember where the user's cursor is (before we take focus away from that
     * field), then show the request box and bring up the keyboard for it.
     */
    private fun openWriteBox() {
        pendingWriteField = WritingAccessibilityService.instance?.captureFocusedField()
        controller.reset()
        closeExplanation()
        mainHandler.removeCallbacks(collapseRunnable)

        panel.visibility = View.VISIBLE
        menu.visibility = View.GONE
        strip.visibility = View.GONE
        writeBox.visibility = View.VISIBLE
        writeInput.setText("")
        inWriteBox = true

        // Move the overlay into the upper part of the screen so the keyboard doesn't cover it.
        val topZone = (resources.displayMetrics.heightPixels * 0.15f).toInt()
        if (params.y > topZone) {
            yBeforeWriteBox = params.y
            params.y = topZone
        }
        setOverlayFocusable(true)
        repositionForPanel()

        mainHandler.postDelayed({
            if (!inWriteBox) return@postDelayed
            writeInput.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(writeInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun submitWrite() {
        val request = writeInput.text?.toString()?.trim().orEmpty()
        if (request.isEmpty()) return
        val field = pendingWriteField
        leaveWriteBox()
        showStrip(getString(R.string.bubble_working), actions = false)
        scope.launch { handleOutcome(controller.startWrite(request, field)) }
    }

    /** Hides the keyboard and hands input focus back to the app behind the overlay. */
    private fun leaveWriteBox() {
        if (!inWriteBox) return
        inWriteBox = false
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(writeInput.windowToken, 0)
        writeInput.clearFocus()
        writeBox.visibility = View.GONE
        yBeforeWriteBox?.let { params.y = it }
        yBeforeWriteBox = null
        setOverlayFocusable(false)
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        if (overlayShown) runCatching { windowManager.updateViewLayout(rootView, params) }
    }

    /** Shared path for Redo / Undo / tone-chip buttons. */
    private fun runStep(block: suspend () -> BubbleController.Outcome) {
        closeExplanation()
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
                        chips = chipsWanted()
                    )
                    if (!controller.hasActiveResult) scheduleCollapse(COLLAPSE_SHORT_MS)
                } else {
                    showStrip(getString(R.string.bubble_hint_lost_field), actions = false)
                }
            }
            is BubbleController.Outcome.Copy -> {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("WritingHelp", outcome.text))
                showStrip(getString(R.string.bubble_hint_copied), actions = false)
                scheduleCollapse(COLLAPSE_COPIED_MS)
            }
            is BubbleController.Outcome.Message -> {
                showStrip(
                    outcome.rawMessage ?: getString(outcome.statusRes),
                    actions = controller.hasActiveResult,
                    chips = chipsWanted()
                )
            }
        }
    }

    /** Tone chips refine AI Rewrite and Write-for-me results. */
    private fun chipsWanted(): Boolean =
        controller.hasActiveResult &&
            (controller.mode == RewriteMode.REWRITE || controller.mode == RewriteMode.WRITE)

    // ---- view states ------------------------------------------------------------------

    private fun showMenu() {
        leaveWriteBox()
        panel.visibility = View.VISIBLE
        menu.visibility = View.VISIBLE
        strip.visibility = View.GONE
        writeBox.visibility = View.GONE
        var anyOn = false
        menuButtons.forEach { (mode, button) ->
            val on = settings.isModeEnabled(mode)
            button.visibility = if (on) View.VISIBLE else View.GONE
            anyOn = anyOn || on
        }
        menuEmpty.visibility = if (anyOn) View.GONE else View.VISIBLE
        repositionForPanel()
        scheduleCollapse(COLLAPSE_IDLE_MS)
    }

    private fun showStrip(status: String, actions: Boolean, chips: Boolean = false) {
        panel.visibility = View.VISIBLE
        menu.visibility = View.GONE
        writeBox.visibility = View.GONE
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
        btnExplain.visibility = if (actions && controller.canExplain) View.VISIBLE else View.GONE
        rebuildChips(chips)
        repositionForPanel()
        // While the "Why" card is open the panel stays until the card is closed.
        if (explainView == null) scheduleCollapse(COLLAPSE_IDLE_MS)
    }

    private fun rebuildChips(show: Boolean) {
        // strip_actions holds [redo, undo, why, <chips...>]; drop anything past the fixed buttons.
        while (stripActions.childCount > FIXED_STRIP_BUTTONS) stripActions.removeViewAt(FIXED_STRIP_BUTTONS)
        if (!show) return
        REFINEMENT_CHIPS.forEach { chip ->
            val b = Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
                text = chip.label
                setTextColor(0xFF4F46E5.toInt())
                setOnClickListener { runStep { controller.refine(chip.instruction) } }
            }
            stripActions.addView(b)
        }
    }

    private fun collapseToBubble() {
        mainHandler.removeCallbacks(collapseRunnable)
        leaveWriteBox()
        closeExplanation()
        pendingWriteField = null
        panel.visibility = View.GONE
        menu.visibility = View.GONE
        writeBox.visibility = View.GONE
        strip.visibility = View.GONE
        controller.reset()
        // The window shrank back to just the bubble; re-seat it flush against its edge.
        rootView.post {
            if (!overlayShown) return@post
            val screenW = resources.displayMetrics.widthPixels
            params.x = if (pinnedRightEdge) screenW - rootView.width else 0
            windowManager.updateViewLayout(rootView, params)
        }
    }

    private fun scheduleCollapse(delayMs: Long) {
        mainHandler.removeCallbacks(collapseRunnable)
        mainHandler.postDelayed(collapseRunnable, delayMs)
    }

    // ---- "Why these changes" card ------------------------------------------------------

    private fun toggleExplanation() {
        if (explainView != null) {
            closeExplanation()
            scheduleCollapse(COLLAPSE_IDLE_MS)
        } else {
            showExplanation()
        }
    }

    private fun showExplanation() {
        mainHandler.removeCallbacks(collapseRunnable)
        val view = LayoutInflater.from(this).inflate(R.layout.explain_overlay, null)
        val screenW = resources.displayMetrics.widthPixels
        val lp = WindowManager.LayoutParams(
            minOf(screenW - 2 * dp(CARD_MARGIN_DP), dp(CARD_MAX_WIDTH_DP)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = params.y
        }
        view.findViewById<View>(R.id.explain_close).setOnClickListener { toggleExplanation() }
        // Invisible until it has been measured and placed next to the bubble.
        view.alpha = 0f
        windowManager.addView(view, lp)
        explainView = view
        explainParams = lp
        positionExplanation()

        explainJob = scope.launch {
            val result = controller.explain()
            val card = explainView ?: return@launch
            card.findViewById<View>(R.id.explain_loading).visibility = View.GONE
            val text = card.findViewById<TextView>(R.id.explain_text)
            when (result) {
                is BubbleController.Explanation.Bullets ->
                    text.text = ExplainText.render(result.bullets)
                is BubbleController.Explanation.Error -> {
                    text.text = result.rawMessage ?: getString(result.statusRes)
                    text.setTextColor(0xFFB3261E.toInt())
                }
            }
            card.findViewById<View>(R.id.explain_scroll).visibility = View.VISIBLE
            positionExplanation()
        }
    }

    /** Sizes the card (scrolls past ~45% of the screen) and puts it above or below the bubble. */
    private fun positionExplanation() {
        val card = explainView ?: return
        card.post {
            val view = explainView ?: return@post
            val lp = explainParams ?: return@post
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val maxH = (screenH * 0.45f).toInt()

            val scroll = view.findViewById<View>(R.id.explain_scroll)
            if (scroll.visibility == View.VISIBLE && view.height > maxH) {
                val header = view.findViewById<View>(R.id.explain_header)
                scroll.layoutParams = scroll.layoutParams.apply {
                    height = (maxH - header.height - view.paddingTop - view.paddingBottom).coerceAtLeast(dp(80))
                }
                scroll.requestLayout()
                view.post { positionExplanation() }
                return@post
            }

            // Place it against the whole bubble widget (bubble + any open panel) so it never
            // covers the Redo / Undo strip.
            val (x, y) = ExplainText.placeCard(
                screenW = screenW,
                screenH = screenH,
                bubbleTop = params.y,
                bubbleBottom = params.y + rootView.height,
                pinnedRight = pinnedRightEdge,
                cardW = lp.width,
                cardH = view.height,
                gap = dp(8),
                margin = dp(CARD_MARGIN_DP)
            )
            lp.x = x
            lp.y = y
            runCatching { windowManager.updateViewLayout(view, lp) }
            view.alpha = 1f
        }
    }

    private fun closeExplanation() {
        explainJob?.cancel()
        explainJob = null
        val view = explainView ?: return
        explainView = null
        explainParams = null
        runCatching { windowManager.removeView(view) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                // Android 14+ lets the user swipe/clear even a foreground-service notification.
                // When that happens we're told via this intent and post it again right away.
                .setDeleteIntent(
                    PendingIntent.getService(
                        this, 3,
                        Intent(this, BubbleService::class.java).setAction(ACTION_REPOST),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
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
        /** Sent when the user dismisses the kept notification; applyState() re-posts it. */
        private const val ACTION_REPOST = "com.writinghelp.rewrite.bubble.REPOST"
        private const val CHANNEL_ID = "writinghelp_bubble"
        private const val NOTIFICATION_ID = 4711
        private const val LONG_PRESS_MS = 600L
        private const val COLLAPSE_IDLE_MS = 8_000L
        private const val COLLAPSE_SHORT_MS = 1_500L
        private const val COLLAPSE_COPIED_MS = 4_000L
        private const val FIXED_STRIP_BUTTONS = 3
        private const val CARD_MARGIN_DP = 12
        private const val CARD_MAX_WIDTH_DP = 380

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
