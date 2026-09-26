package org.evsyukov.shareding.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.evsyukov.shareding.network.ProxyConfig

data class Settings(
    val serverUrl: String = "",
    val defaultTags: String = "",
    val unread: Boolean = true,
    val archived: Boolean = false,
    val lastSync: Long = 0,
    val lastError: String = "",
    val hasToken: Boolean = false,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val proxyUsername: String = "",
    val hasProxyPassword: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val secretCipher = SecretCipher()
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<Settings> = mutable

    private fun read() = Settings(
        serverUrl = prefs.getString("server_url", "") ?: "",
        defaultTags = prefs.getString("tags", "") ?: "",
        unread = prefs.getBoolean("unread", true),
        archived = prefs.getBoolean("archived", false),
        lastSync = prefs.getLong("last_sync", 0),
        lastError = prefs.getString("last_error", "") ?: "",
        hasToken = secrets.contains("token"),
        proxyEnabled = prefs.getBoolean("proxy_enabled", false),
        proxyHost = prefs.getString("proxy_host", "") ?: "",
        proxyPort = prefs.getInt("proxy_port", 8080),
        proxyUsername = prefs.getString("proxy_username", "") ?: "",
        hasProxyPassword = secrets.contains("proxy_password"),
    )

    fun resolveProxy(enabled: Boolean, host: String, port: String, username: String,
                     password: String): ProxyConfig {
        val cleanUsername = username.trim()
        val secret = password.ifBlank {
            if (cleanUsername == state.value.proxyUsername) proxyPassword().orEmpty() else ""
        }
        return ProxyConfig(enabled, host.trim(), port.toIntOrNull() ?: if (enabled) 0 else 8080,
            cleanUsername, secret)
            .validated()
    }

    fun proxyConfig(): ProxyConfig {
        val saved = state.value
        return ProxyConfig(saved.proxyEnabled, saved.proxyHost, saved.proxyPort,
            saved.proxyUsername, proxyPassword().orEmpty()).validated()
    }

    fun save(serverUrl: String, token: String?, tags: String, unread: Boolean, archived: Boolean,
             proxy: ProxyConfig? = null) {
        val config = (proxy ?: proxyConfig()).validated()
        if (token != null) {
            check(secrets.edit().putString("token", secretCipher.encrypt(token)).commit()) { "Cannot save API token" }
        }
        val passwordEditor = secrets.edit()
        if (config.username.isBlank() || config.password.isBlank()) passwordEditor.remove("proxy_password")
        else passwordEditor.putString("proxy_password", secretCipher.encrypt(config.password))
        check(passwordEditor.commit()) { "Cannot save proxy password" }
        val editor = prefs.edit().putString("server_url", serverUrl)
            .putString("tags", tags).putBoolean("unread", unread).putBoolean("archived", archived)
            .putBoolean("proxy_enabled", config.enabled).putString("proxy_host", config.host)
            .putInt("proxy_port", config.port).putString("proxy_username", config.username)
        check(editor.commit()) { "Cannot save settings" }
        mutable.value = read()
    }

    fun token(): String? = secrets.getString("token", null)?.let(secretCipher::decrypt)

    private fun proxyPassword(): String? =
        secrets.getString("proxy_password", null)?.let(secretCipher::decrypt)

    fun recordSuccess() {
        check(prefs.edit().putLong("last_sync", System.currentTimeMillis())
            .putString("last_error", "").commit())
        mutable.value = read()
    }

    fun recordError(message: String) {
        check(prefs.edit().putString("last_error", message).commit())
        mutable.value = read()
    }
}

private class SecretCipher {
    private val alias = "shareding-api-token"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Invalid encrypted token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
