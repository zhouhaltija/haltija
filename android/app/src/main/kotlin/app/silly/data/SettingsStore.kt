package app.silly.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 设置的持久化。
 *
 * **API Key 走 [EncryptedSharedPreferences]**，不明文落盘 ——
 * 密钥泄露的代价是用户自己掏钱，不能图省事。
 *
 * 其余字段（provider / baseUrl / model）不敏感，但也一并存在这里，
 * 省得维护两套存储。
 */
class SettingsStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): AppSettings {
        val providerId = prefs.getString(KEY_PROVIDER, null) ?: "openai"
        val defaults = AppSettings.defaultsFor(providerId)
        return AppSettings(
            providerId = providerId,
            baseUrl = prefs.getString(KEY_BASE_URL, null) ?: defaults.baseUrl,
            apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
            model = prefs.getString(KEY_MODEL, null) ?: defaults.model,
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, defaults.maxTokens),
            temperature = prefs.getFloat(KEY_TEMPERATURE, defaults.temperature.toFloat()).toDouble(),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.providerId)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putInt(KEY_MAX_TOKENS, settings.maxTokens)
            .putFloat(KEY_TEMPERATURE, settings.temperature.toFloat())
            .apply()
    }

    private companion object {
        const val FILE_NAME = "sillyapp_settings"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TEMPERATURE = "temperature"
    }
}
