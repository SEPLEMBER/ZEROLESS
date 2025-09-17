package com.nemesis.droidcrypt.utils.security

import android.content.SharedPreferences
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * SecCoreUtils
 *
 * - Uses PBKDF2WithHmacSHA256 for key derivation.
 * - AES/GCM/NoPadding for encryption.
 *
 * Format used for storing encrypted master key in SharedPreferences:
 *   "v1:iterKek:base64(saltKek):base64(iv):base64(ciphertext)"
 *
 * Notes:
 *  - Use CharArray for passwords and wipe them after use.
 *  - Wipe transient key byte arrays with wipe(byteArray) after use.
 */
object SecCoreUtils {

    // ----- Configurable parameters -----
    private const val AES_KEY_LEN_BITS = 256
    private const val AES_KEY_LEN_BYTES = AES_KEY_LEN_BITS / 8
    private const val GCM_IV_LEN_BYTES = 12
    private const val GCM_TAG_LEN_BITS = 128

    // PBKDF2 iteration counts per your design
    const val ITER_PBKDF2_PASSWORD = 250_000 // for deriving KEK from user's password
    const val ITER_PBKDF2_MASTER_TO_DB = 30_000 // for deriving DB keys from master key

    // salt lengths
    private const val SALT_LEN_BYTES = 16

    private val secureRandom = SecureRandom()

    // ----- Helpers -----

    private fun generateRandomBytes(len: Int): ByteArray {
        val b = ByteArray(len)
        secureRandom.nextBytes(b)
        return b
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun base64Decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // Публичный метод для очистки ByteArray
    fun wipe(bytes: ByteArray?) {
        if (bytes == null) return
        for (i in bytes.indices) bytes[i] = 0
    }

    // Публичный метод для очистки CharArray
    fun wipe(chars: CharArray?) {
        if (chars == null) return
        for (i in chars.indices) chars[i] = '\u0000' // Исправлено: используем нулевой символ
    }

    // ----- PBKDF2 derivation -----

    /**
     * Derive a key of [keyLenBytes] bytes from a password (CharArray) and salt with given iterations.
     * Caller should wipe the returned key byte array when finished.
     */
    @Throws(Exception::class)
    fun deriveKeyPBKDF2(password: CharArray, salt: ByteArray, iterations: Int, keyLenBytes: Int = AES_KEY_LEN_BYTES): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLenBytes * 8)
        return try {
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = skf.generateSecret(spec).encoded
            key
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Derive a key using a masterKey (byte array) as "password" for PBKDF2.
     * We convert masterKey bytes to UTF-8-safe base64 string and use its chars as password input.
     * (This avoids constructing PBEKeySpec directly with binary data.)
     */
    @Throws(Exception::class)
    fun deriveKeyFromMaster(masterKey: ByteArray, salt: ByteArray, iterations: Int = ITER_PBKDF2_MASTER_TO_DB, keyLenBytes: Int = AES_KEY_LEN_BYTES): ByteArray {
        // masterKey -> base64 string -> char array
        val masterBase64 = base64(masterKey)
        val pwChars = masterBase64.toCharArray()
        try {
            return deriveKeyPBKDF2(pwChars, salt, iterations, keyLenBytes)
        } finally {
            wipe(pwChars)
        }
    }

    // ----- AES-GCM encrypt/decrypt -----

    /**
     * Encrypt plaintext with given raw AES key bytes (256 bits recommended).
     * Returns (iv || ciphertext) as a single byte[].
     * Caller must ensure keyBytes length is appropriate (16/24/32).
     */
    @Throws(Exception::class)
    fun encryptAesGcm(plaintext: ByteArray, keyBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(keyBytes, "AES")
        val iv = generateRandomBytes(GCM_IV_LEN_BYTES)
        val spec = GCMParameterSpec(GCM_TAG_LEN_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherText = cipher.doFinal(plaintext)
        // return iv || cipherText
        return iv + cipherText
    }

    /**
     * Decrypt (iv || ciphertext) produced by encryptAesGcm using the same AES key bytes.
     */
    @Throws(Exception::class)
    fun decryptAesGcm(ivAndCipherText: ByteArray, keyBytes: ByteArray): ByteArray {
        require(ivAndCipherText.size > GCM_IV_LEN_BYTES) { "Input too short" }
        val iv = ivAndCipherText.copyOfRange(0, GCM_IV_LEN_BYTES)
        val cipherText = ivAndCipherText.copyOfRange(GCM_IV_LEN_BYTES, ivAndCipherText.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(keyBytes, "AES")
        val spec = GCMParameterSpec(GCM_TAG_LEN_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(cipherText)
    }

    // ----- Master-key wrapping / unwrapping -----

    /**
     * Wrap (encrypt) a masterKey using user's password.
     * Returns a single string suitable for storage (format version v1).
     *
     * Format:
     *   v1:iterKek:base64(saltKek):base64(iv):base64(ciphertext)
     *
     * - saltKek is the salt used for PBKDF2(password) -> KEK (ITER_PBKDF2_PASSWORD)
     * - iv is the AES-GCM IV used to encrypt the masterKey
     */
    @Throws(Exception::class)
    fun wrapMasterKey(masterKey: ByteArray, password: CharArray): String {
        // generate salt for KEK derivation
        val saltKek = generateRandomBytes(SALT_LEN_BYTES)
        val kek = deriveKeyPBKDF2(password, saltKek, ITER_PBKDF2_PASSWORD, AES_KEY_LEN_BYTES)
        try {
            val ivAndCipher = encryptAesGcm(masterKey, kek)
            val iv = ivAndCipher.copyOfRange(0, GCM_IV_LEN_BYTES)
            val cipherOnly = ivAndCipher.copyOfRange(GCM_IV_LEN_BYTES, ivAndCipher.size)
            // store as: v1:iter:salt:iv:cipher
            val parts = listOf(
                "v1",
                ITER_PBKDF2_PASSWORD.toString(),
                base64(saltKek),
                base64(iv),
                base64(cipherOnly)
            )
            return parts.joinToString(":")
        } finally {
            wipe(kek)
        }
    }

    /**
     * Unwrap (decrypt) masterKey string created by wrapMasterKey.
     * Returns masterKey byte[] (caller must wipe after use).
     * Throws Exception on failure (including auth failure).
     */
    @Throws(Exception::class)
    fun unwrapMasterKey(wrapped: String, password: CharArray): ByteArray {
        val parts = wrapped.split(":")
        if (parts.size < 5 || parts[0] != "v1") throw IllegalArgumentException("Unsupported wrapped format")
        val iter = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Bad iter")
        val saltKek = base64Decode(parts[2])
        val iv = base64Decode(parts[3])
        val cipherOnly = base64Decode(parts[4])
        val ivAndCipher = iv + cipherOnly
        val kek = deriveKeyPBKDF2(password, saltKek, iter, AES_KEY_LEN_BYTES)
        try {
            val master = decryptAesGcm(ivAndCipher, kek)
            return master
        } finally {
            wipe(kek)
        }
    }

    // ----- SharedPreferences helpers (simple) -----

    /**
     * Save wrapped master key string into SharedPreferences.
     * Key: prefsKey
     */
    fun saveWrappedMasterToPrefs(prefs: SharedPreferences, prefsKey: String, wrappedString: String) {
        prefs.edit().putString(prefsKey, wrappedString).apply()
    }

    /**
     * Load wrapped master key string from SharedPreferences (or null).
     */
    fun loadWrappedMasterFromPrefs(prefs: SharedPreferences, prefsKey: String): String? {
        return prefs.getString(prefsKey, null)
    }

    /**
     * Remove wrapped master key from prefs
     */
    fun removeWrappedMasterFromPrefs(prefs: SharedPreferences, prefsKey: String) {
        prefs.edit().remove(prefsKey).apply()
    }

    /**
     * Convenience: create a new random master key, wrap with password and store to prefs.
     * Returns the raw masterKey (caller should wipe after using it).
     */
    @Throws(Exception::class)
    fun createAndStoreNewMaster(prefs: SharedPreferences, prefsKey: String, password: CharArray): ByteArray {
        val masterKey = generateRandomBytes(AES_KEY_LEN_BYTES)
        try {
            val wrapped = wrapMasterKey(masterKey, password)
            saveWrappedMasterToPrefs(prefs, prefsKey, wrapped)
            return masterKey
        } catch (ex: Exception) {
            wipe(masterKey)
            throw ex
        }
    }

    /**
     * Convenience: load wrapped master from prefs, unwrap with password, return master key bytes.
     */
    @Throws(Exception::class)
    fun loadMasterFromPrefs(prefs: SharedPreferences, prefsKey: String, password: CharArray): ByteArray? {
        val wrapped = loadWrappedMasterFromPrefs(prefs, prefsKey) ?: return null
        return unwrapMasterKey(wrapped, password)
    }

    /**
     * Derive a per-database (or per-file) AES key from masterKey and a provided salt.
     * saltDb should be random and stored alongside encrypted DB (or derived deterministically from filename).
     */
    @Throws(Exception::class)
    fun deriveDbKey(masterKey: ByteArray, saltDb: ByteArray, iterations: Int = ITER_PBKDF2_MASTER_TO_DB, keyLenBytes: Int = AES_KEY_LEN_BYTES): ByteArray {
        return deriveKeyFromMaster(masterKey, saltDb, iterations, keyLenBytes)
    }

    // ----- Utility: XOR (optional) -----
    /**
     * Optional helper: constant-time XOR two arrays into a new array (useful for small combiners)
     */
    fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        val out = ByteArray(len)
        for (i in 0 until len) out[i] = a[i] xor b[i]
        return out
    }
}
