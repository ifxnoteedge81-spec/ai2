package com.personal.aicomputer.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.personal.aicomputer.ai.KeyProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys are encrypted with an AES-256-GCM key that lives in the Android Keystore (non-exportable),
 * and only the ciphertext is stored in SharedPreferences. Keys are never logged and never leave the
 * device except in requests to the provider the user selected.
 */
class SecureKeyStore(context: Context) : KeyProvider {
    private val prefs = context.getSharedPreferences("pac_secure_keys", Context.MODE_PRIVATE)
    private val alias = "pac_api_key_wrapper"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    override fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return try {
            val (ivB64, ctB64) = blob.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null // Keystore key lost (e.g. after restore) -> user must re-enter the key.
        }
    }

    fun has(name: String): Boolean = !get(name).isNullOrBlank()

    fun delete(name: String) {
        prefs.edit().remove(name).apply()
    }

    /** Shown in the UI instead of the key: e.g. "AIza…x9Qf". */
    fun masked(name: String): String? {
        val v = get(name) ?: return null
        return if (v.length <= 8) "••••" else v.take(4) + "…" + v.takeLast(4)
    }
}
