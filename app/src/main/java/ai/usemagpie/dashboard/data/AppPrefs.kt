package ai.usemagpie.dashboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Connection settings storage. Uses Keystore-backed EncryptedSharedPreferences; falls back to plain
 * preferences only when the device has no usable Keystore implementation.
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "connection_secure",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("connection_plain", Context.MODE_PRIVATE)
    }

    var baseUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) {
            prefs.edit().putString(KEY_URL, value).apply()
        }

    var apiKey: String?
        get() = prefs.getString(KEY_KEY, null)
        set(value) {
            prefs.edit().putString(KEY_KEY, value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_URL = "base_url"
        const val KEY_KEY = "api_key"
    }
}
