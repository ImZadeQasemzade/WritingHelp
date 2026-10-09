package com.writinghelp.rewrite

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.writinghelp.rewrite.bubble.BubbleService
import com.writinghelp.rewrite.bubble.WritingAccessibilityService
import com.writinghelp.rewrite.data.SettingsRepository
import com.writinghelp.rewrite.ui.BubbleUiState
import com.writinghelp.rewrite.ui.SettingsScreen
import com.writinghelp.rewrite.ui.theme.WritingHelpTheme

class MainActivity : ComponentActivity() {

    private val settings by lazy { SettingsRepository(applicationContext) }
    private val bubbleState = mutableStateOf(BubbleUiState())

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onPermissionChanged()
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WritingHelpTheme {
                SettingsScreen(
                    settingsRepository = settings,
                    bubble = bubbleState.value,
                    onBubbleToggle = ::onBubbleToggle,
                    onKeepInNotificationToggle = ::onKeepInNotificationToggle,
                    onGrantOverlay = ::requestOverlayPermission,
                    onGrantAccessibility = ::openAccessibilitySettings
                )
            }
        }
        refreshBubbleState()
    }

    override fun onResume() {
        super.onResume()
        refreshBubbleState()
    }

    private fun onBubbleToggle(on: Boolean) {
        settings.bubbleEnabled = on
        if (on) {
            requestNotificationPermission()
            when {
                !Settings.canDrawOverlays(this) -> requestOverlayPermission()
                !isAccessibilityEnabled() -> openAccessibilitySettings()
            }
        }
        refreshBubbleState()
    }

    private fun onKeepInNotificationToggle(on: Boolean) {
        settings.keepInNotification = on
        if (on) requestNotificationPermission()
        refreshBubbleState()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Called when we come back from a system permission screen. */
    private fun onPermissionChanged() = refreshBubbleState()

    private fun refreshBubbleState() {
        val canOverlay = Settings.canDrawOverlays(this)
        val accOn = isAccessibilityEnabled()
        val wants = settings.bubbleEnabled
        val keep = settings.keepInNotification

        // The service runs while the bubble is up, or always when it's kept in the bar.
        // Starting it again just makes it re-read the settings.
        if (keep || (wants && canOverlay && accOn)) {
            BubbleService.start(this)
        } else if (BubbleService.isRunning) {
            BubbleService.stop(this)
        }

        bubbleState.value = BubbleUiState(
            enabled = wants,
            running = wants && canOverlay && accOn,
            canOverlay = canOverlay,
            accessibilityOn = accOn,
            keepInNotification = keep
        )
    }

    private fun requestOverlayPermission() {
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(this, WritingAccessibilityService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == target }
    }
}
