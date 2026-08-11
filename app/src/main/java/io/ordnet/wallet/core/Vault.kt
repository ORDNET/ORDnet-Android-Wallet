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
 * H6 (external audit, 11 Aug 2026) — the Keystore key is now created with
 * setUserAuthenticationRequired(true). Previously it was not, so the biometric
 * prompt was UI only: readVault() decrypted with a key that required no
 * authentication, and any code path that skipped authenticate() (or an
 * attacker with code execution) could decrypt directly. Now the Keystore
 * itself refuses vault crypto unless the user authenticated within a short
 * validity window (AUTH_WINDOW_SECONDS): unlock() authenticates before
 * readVault(); create/import authenticate before the first save; a save whose
 * window has lapsed re-prompts (WalletStore.saveAccounts). This matches the
 * intent of iOS's kSecAttrAccessControl: .userPresence without prompting on
 * every unrelated write.
 *
 * The data-loss concern that originally motivated the unbound key is handled
 * WITHOUT reintroducing it: the key does NOT set
 * setInvalidatedByBiometricEnrollment(true) and allows DEVICE_CREDENTIAL, so
 * enrolling a new fingerprint/face does not destroy the wallet. Backups /
 * device-transfer remain fully excluded (data_extraction_rules), matching the
 * extension's "keys live ONLY on this device".
 */
object Vault {
    private const val KEY_ALIAS = "ordplug_vault_key"
    private const val VAULT_FILE = "ordplug_vault_v11.bin"
    private const val KEYSTORE = "AndroidKeyStore"
    // H6 — authentication validity window (seconds). One biometric/credential
    // auth authorises vault crypto for this long, so operations clustered
    // around an unlock don't re-prompt, while a cold operation with no recent
    // auth is refused by the Keystore.
    private const val AUTH_WINDOW_SECONDS = 30

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
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // H6 — the key now REQUIRES user authentication. Before this, the
            // biometric prompt was UI only and readVault() decrypted with a key
            // that required nothing, so any path that skipped authenticate()
            // could read the wallet. A short authentication VALIDITY WINDOW is
            // used rather than a per-operation CryptoObject: unlock() (or any
            // save/reveal) authenticates once, and operations within the window
            // proceed — so renaming an account seconds after unlocking does not
            // demand a second fingerprint, but a cold readVault() with no recent
            // auth is rejected by the Keystore itself.
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: window in seconds, biometric OR device credential.
            // Not invalidating on new biometric enrolment keeps the original
            // data-loss protection (adding a fingerprint must not wipe the key).
            builder.setUserAuthenticationParameters(
                AUTH_WINDOW_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    /**
     * Save (or replace) the encrypted vault. Requires a recent authentication
     * (within AUTH_WINDOW_SECONDS): the Keystore refuses the encrypt operation
     * otherwise. Callers authenticate() first (e.g. right after unlock).
     */
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

    /**
     * Decrypt the vault. Requires a recent authentication (within
     * AUTH_WINDOW_SECONDS) — with the auth-required key (H6), the Keystore
     * throws UserNotAuthenticatedException if no recent auth exists, so this
     * can no longer succeed on an un-prompted call. Call authenticate() first.
     */
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
     *
     * H6 — with the vault key now auth-required (validity window), a
     * successful prompt here authorises vault crypto for AUTH_WINDOW_SECONDS.
     * Callers do authenticate() immediately before saveVault()/readVault(); a
     * vault operation with no recent auth is rejected by the Keystore itself,
     * so the prompt is no longer merely cosmetic.
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
