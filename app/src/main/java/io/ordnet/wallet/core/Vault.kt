package io.ordnet.wallet.core

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Encrypted-at-rest key storage — the Android counterpart of the iOS Keychain
 * vault. The vault JSON is encrypted with AES-256-GCM under a hardware-backed
 * Android Keystore key (the key material never leaves the secure hardware), and
 * every unlock / backup-reveal is gated by BiometricPrompt (fingerprint/face,
 * with the device PIN/pattern as fallback — the same UX as Face ID + passcode).
 *
 * The Keystore key is deliberately NOT biometric-bound: Android invalidates
 * biometric-bound keys when the user adds a new fingerprint/face, which would
 * silently destroy the wallet. Hardware-backed encryption + a mandatory
 * biometric gate gives iOS-equivalent protection without that data-loss risk.
 * Backups/device-transfer are fully excluded (data_extraction_rules), matching
 * the extension's "keys live ONLY on this device".
 */
object Vault {
    private const val KEY_ALIAS = "ordplug_vault_key"
    private const val VAULT_FILE = "ordplug_vault_v11.bin"
    private const val KEYSTORE = "AndroidKeyStore"

    class VaultException(message: String) : Exception(message)

    private fun vaultFile(context: Context): File = File(context.filesDir, VAULT_FILE)

    fun vaultExists(context: Context): Boolean = vaultFile(context).exists()

    fun deleteVault(context: Context) {
        vaultFile(context).delete()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** Save (or replace) the encrypted vault. */
    fun saveVault(context: Context, data: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        val out = ByteArray(1 + iv.size + ciphertext.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ciphertext, 0, out, 1 + iv.size, ciphertext.size)
        val f = vaultFile(context)
        f.writeBytes(out)
    }

    /** Decrypt the vault (call AFTER authenticate()). */
    fun readVault(context: Context): ByteArray {
        val f = vaultFile(context)
        if (!f.exists()) throw VaultException("No wallet on this device yet.")
        val blob = f.readBytes()
        if (blob.size < 14) throw VaultException("Vault is corrupted.")
        val ivLen = blob[0].toInt()
        if (ivLen <= 0 || blob.size < 1 + ivLen + 1) throw VaultException("Vault is corrupted.")
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ciphertext = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw VaultException("Could not decrypt the wallet vault (${e.message ?: "unknown"}).")
        }
    }

    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /**
     * Re-authenticate the user (unlock + backup reveal, exactly like the
     * Face ID gate on iOS). Biometrics with PIN/pattern/password fallback.
     */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String = "") {
        val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            throw VaultException("Set a screen lock (PIN, pattern or biometrics) in Android settings to protect your wallet.")
        }
        val can = BiometricManager.from(activity).canAuthenticate(allowedAuthenticators())
        if (can != BiometricManager.BIOMETRIC_SUCCESS &&
            can != BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            // fall through and try anyway — DEVICE_CREDENTIAL is available on secure devices
        }

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resumeWithException(
                                VaultException(
                                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                        errorCode == BiometricPrompt.ERROR_CANCELED
                                    ) "Authentication was cancelled."
                                    else errString.toString().ifEmpty { "Authentication failed." }
                                )
                            )
                        }
                        // onAuthenticationFailed = a bad attempt; the prompt stays up
                    })
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .apply { if (subtitle.isNotEmpty()) setSubtitle(subtitle) }
                    .setAllowedAuthenticators(allowedAuthenticators())
                    .setConfirmationRequired(false)
                    .build()
                prompt.authenticate(info)
                cont.invokeOnCancellation {
                    // cancellation may come from any thread; FragmentManager needs main
                    ContextCompat.getMainExecutor(activity).execute { prompt.cancelAuthentication() }
                }
            }
        }
    }
}
