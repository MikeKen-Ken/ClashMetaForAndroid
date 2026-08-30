package com.github.kr328.clash.common.secret

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretString {
    private const val PREFIX = "ks1:"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "clash.ui.secret.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12

    fun canWrap(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    fun isWrapped(stored: String): Boolean = stored.startsWith(PREFIX)

    fun wrap(plain: String): String {
        if (plain.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return plain
        }
        return runCatching { PREFIX + encrypt(plain) }.getOrDefault(plain)
    }

    fun unwrap(stored: String): String {
        if (stored.isEmpty()) {
            return ""
        }
        if (!isWrapped(stored)) {
            return stored
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return stored
        }
        return runCatching { decrypt(stored.substring(PREFIX.length)) }.getOrDefault(stored)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val payload = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + payload.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(payload, 0, packed, iv.size, payload.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_SIZE) { "truncated secret" }
        val iv = packed.copyOfRange(0, IV_SIZE)
        val payload = packed.copyOfRange(IV_SIZE, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
