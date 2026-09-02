package com.writinghelp.rewrite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.writinghelp.rewrite.data.SettingsRepository
import com.writinghelp.rewrite.ui.SettingsScreen
import com.writinghelp.rewrite.ui.theme.WritingHelpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsRepository = SettingsRepository(applicationContext)

        setContent {
            WritingHelpTheme {
                SettingsScreen(settingsRepository)
            }
        }
    }
}
