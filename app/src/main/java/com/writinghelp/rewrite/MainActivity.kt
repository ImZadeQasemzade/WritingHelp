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
        if (!on) {
            BubbleService.stop(this)
            refreshBubbleState()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        when {
            !Settings.canDrawOverlays(this) -> requestOverlayPermission()
            !isAccessibilityEnabled() -> openAccessibilitySettings()
            else -> BubbleService.start(this)
        }
        refreshBubbleState()
    }

    /** Called when we come back from a system permission screen. */
    private fun onPermissionChanged() = refreshBubbleState()

    private fun refreshBubbleState() {
        val canOverlay = Settings.canDrawOverlays(this)
        val accOn = isAccessibilityEnabled()
        val wants = settings.bubbleEnabled

        if (wants && canOverlay && accOn && !BubbleService.isRunning) {
            BubbleService.start(this)
        } else if (!wants && BubbleService.isRunning) {
            BubbleService.stop(this)
        }

        bubbleState.value = BubbleUiState(
            enabled = wants,
            running = BubbleService.isRunning && canOverlay && accOn,
            canOverlay = canOverlay,
            accessibilityOn = accOn
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
