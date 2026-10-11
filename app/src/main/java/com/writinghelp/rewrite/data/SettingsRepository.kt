package com.writinghelp.rewrite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user's Claude API key and preferences in an encrypted SharedPreferences file
 * (backed by the Android Keystore), so the key isn't sitting in the app's storage as plain text.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** Whether the user has turned the floating bubble on (they may still need to grant permissions). */
    var bubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, value).apply()

    /** Keep the notification (and service) up at all times; tapping it shows/hides the bubble. */
    var keepInNotification: Boolean
        get() = prefs.getBoolean(KEY_KEEP_IN_NOTIFICATION, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_IN_NOTIFICATION, value).apply()

    var mainLanguage: String
        get() = prefs.getString(KEY_MAIN_LANGUAGE, DEFAULT_MAIN_LANGUAGE) ?: DEFAULT_MAIN_LANGUAGE
        set(value) = prefs.edit().putString(KEY_MAIN_LANGUAGE, value).apply()

    var secondLanguage: String
        get() = prefs.getString(KEY_SECOND_LANGUAGE, DEFAULT_SECOND_LANGUAGE) ?: DEFAULT_SECOND_LANGUAGE
        set(value) = prefs.edit().putString(KEY_SECOND_LANGUAGE, value).apply()

    val languages: LanguagePair
        get() = LanguagePair(mainLanguage, secondLanguage)

    fun hasApiKey(): Boolean = !apiKey.isNullOrBlank()

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "writinghelp_secure_prefs"
        const val KEY_API_KEY = "claude_api_key"
        const val KEY_MODEL = "claude_model"
        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        const val KEY_KEEP_IN_NOTIFICATION = "keep_in_notification"
        const val KEY_MAIN_LANGUAGE = "main_language"
        const val KEY_SECOND_LANGUAGE = "second_language"
    }
}
