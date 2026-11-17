package com.writinghelp.rewrite

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.writinghelp.rewrite.data.Feature
import com.writinghelp.rewrite.data.SettingsRepository

/**
 * Keeps the text-selection popup in step with Settings: an action that's switched off has its
 * ACTION_PROCESS_TEXT activity disabled, so it disappears from the Copy/Paste menu too.
 */
object FeatureComponents {

    private val ACTIVITIES = mapOf(
        Feature.FIX_GRAMMAR to FixGrammarActivity::class.java,
        Feature.REWRITE to RewriteActivity::class.java,
        Feature.TRANSLATE to TranslateActivity::class.java
    )

    fun sync(context: Context, settings: SettingsRepository) {
        val pm = context.packageManager
        ACTIVITIES.forEach { (feature, activity) ->
            val component = ComponentName(context, activity)
            val wanted = if (settings.isEnabled(feature)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val current = pm.getComponentEnabledSetting(component)
            val effectivelyEnabled = current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val wantEnabled = wanted == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (effectivelyEnabled != wantEnabled) {
                pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
