package com.danielbrog.activitystats.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

private val Context.accountStore: DataStore<Preferences> by preferencesDataStore(name = "account")

/**
 * Keeps the sign-in state (AppAuth's AuthState, which holds the tokens) on the phone,
 * encrypted with an AES key in the Android Keystore. On a Pixel the key lives in secure
 * hardware and can't be copied off the phone; backups leave this file out (see
 * res/xml/data_extraction_rules.xml).
 */
class AuthStateStore(context: Context) {

    private val store = context.applicationContext.accountStore

    /** The saved state, or null if there is none or it can no longer be decrypted. */
    suspend fun load(): String? {
        val sealed = store.data.first()[AUTH_STATE] ?: return null
        return try {
            decrypt(sealed)
        } catch (e: Exception) {
            // The key is gone (app data restored elsewhere, Keystore reset): treat as signed out.
            Log.w(TAG, "Saved sign-in couldn't be read; signing out", e)
            clear()
            null
        }
    }

    suspend fun save(state: String) {
        val sealed = encrypt(state)
        store.edit { it[AUTH_STATE] = sealed }
    }

    suspend fun clear() {
        store.edit { it.remove(AUTH_STATE) }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val ivSize = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val body = bytes.copyOfRange(1 + ivSize, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: error("No key"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun key(): SecretKey = existingKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }
        .generateKey()

    private companion object {
        const val TAG = "AuthStateStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "activity_stats_sign_in"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AUTH_STATE = stringPreferencesKey("auth_state")
    }
}
