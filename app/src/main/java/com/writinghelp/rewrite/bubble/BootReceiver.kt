package com.writinghelp.rewrite.bubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.writinghelp.rewrite.data.SettingsRepository

/** Puts the WritingHelp notification back after a restart or app update, if it's kept in the bar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (SettingsRepository(context.applicationContext).keepInNotification) {
            runCatching { BubbleService.start(context) }
        }
    }
}
