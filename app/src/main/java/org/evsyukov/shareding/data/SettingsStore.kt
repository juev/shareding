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

data class Settings(
    val serverUrl: String = "",
    val defaultTags: String = "",
    val unread: Boolean = true,
    val archived: Boolean = false,
    val lastSync: Long = 0,
    val lastError: String = "",
    val hasToken: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val tokenCipher = TokenCipher()
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
    )

    fun save(serverUrl: String, token: String?, tags: String, unread: Boolean, archived: Boolean) {
        val editor = prefs.edit().putString("server_url", serverUrl)
            .putString("tags", tags).putBoolean("unread", unread).putBoolean("archived", archived)
        check(editor.commit()) { "Cannot save settings" }
        if (token != null) {
            check(secrets.edit().putString("token", tokenCipher.encrypt(token)).commit()) { "Cannot save API token" }
        }
        mutable.value = read()
    }

    fun token(): String? = secrets.getString("token", null)?.let(tokenCipher::decrypt)

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

private class TokenCipher {
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
