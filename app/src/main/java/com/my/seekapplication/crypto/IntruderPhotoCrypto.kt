package com.my.seekapplication.crypto

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * IntruderPhotoCrypto: Asymmetric Hybrid Public-Key Encryption for Intruder Selfies.
 *
 * Security Model:
 * 1. RSA-2048 keypair is initialized during setup.
 * 2. Public key (intruder_pub.der) is saved in plaintext inside app private storage.
 * 3. Private key (intruder_priv.enc) is encrypted with the Real Vault Master Key.
 * 4. When an intruder triggers a failed unlock, photo bytes are encrypted with an ephemeral
 *    AES-256-GCM key, and that key is encrypted with the RSA Public Key.
 *    -> Crucial: The photo can be securely encrypted while the vault is 100% LOCKED,
 *       without requiring the Master Key in volatile memory!
 * 5. Photos can only be decrypted after a successful REAL vault unlock using the RSA Private Key.
 * 6. Hard storage cap: Max 50 photos (oldest removed first).
 */
object IntruderPhotoCrypto {

    private const val TAG = "IntruderPhotoCrypto"
    private const val MAGIC = 0x494E5452 // "INTR"
    private const val MAX_STORED_SELFIES = 50

    private const val RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    private val secureRandom = SecureRandom()

    var customBaseDir: File? = null

    private fun getMetaDir(context: Context? = null): File {
        val root = customBaseDir ?: context?.filesDir ?: File(".")
        val dir = File(root, "vault_meta")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getSelfiesDir(context: Context? = null): File {
        val root = customBaseDir ?: context?.filesDir ?: File(".")
        val dir = File(root, "intruder_selfies")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPublicKeyFile(context: Context? = null) = File(getMetaDir(context), "intruder_pub.der")
    fun getPrivateKeyFile(context: Context? = null) = File(getMetaDir(context), "intruder_priv.enc")

    /**
     * Initializes RSA-2048 keypair, saving public key and encrypting private key with master key.
     */
    fun initializeKeypair(context: Context? = null, masterKey: SecretKey) {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        // 1. Save public key
        getPublicKeyFile(context).writeBytes(keyPair.public.encoded)

        // 2. Encrypt and save private key with master key
        val privBytes = keyPair.private.encoded
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(privBytes)

        val out = FileOutputStream(getPrivateKeyFile(context))
        out.use {
            it.write(iv)
            it.write(ciphertext)
        }
    }

    fun hasKeys(context: Context? = null): Boolean {
        return getPublicKeyFile(context).exists() && getPrivateKeyFile(context).exists()
    }

    fun loadPublicKey(context: Context? = null): PublicKey? {
        val file = getPublicKeyFile(context)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val spec = X509EncodedKeySpec(bytes)
            KeyFactory.getInstance("RSA").generatePublic(spec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading public key: ${e.message}", e)
            null
        }
    }

    fun loadPrivateKey(context: Context? = null, masterKey: SecretKey): PrivateKey? {
        val file = getPrivateKeyFile(context)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size < GCM_IV_LENGTH) return null
            val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)

            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val privBytes = cipher.doFinal(ciphertext)

            val spec = PKCS8EncodedKeySpec(privBytes)
            KeyFactory.getInstance("RSA").generatePrivate(spec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed decrypting private key: ${e.message}", e)
            null
        }
    }

    /**
     * Encrypts photo bytes with ephemeral AES key and wraps it with the RSA Public Key.
     * Enforces the 50-photo cap.
     */
    fun encryptAndSavePhoto(context: Context? = null, jpegBytes: ByteArray): File? {
        val pubKey = loadPublicKey(context) ?: run {
            Log.e(TAG, "Cannot encrypt selfie: RSA public key not found")
            return null
        }

        try {
            // Generate ephemeral 256-bit AES key
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val aesKey = keyGen.generateKey()

            // 1. Encrypt JPEG bytes with AES-256-GCM
            val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
            val gcmCipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            gcmCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val encryptedPhoto = gcmCipher.doFinal(jpegBytes)

            // 2. Wrap ephemeral AES key with RSA-2048 Public Key
            val rsaCipher = Cipher.getInstance(RSA_ALGORITHM)
            rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey)
            val wrappedAesKey = rsaCipher.doFinal(aesKey.encoded)

            // Enforce max photos cap (50 photos max)
            enforceStorageCap(context)

            // 3. Write package to disk
            val targetFile = File(getSelfiesDir(context), "selfie_${System.currentTimeMillis()}.enc")
            FileOutputStream(targetFile).use { fos ->
                val header = ByteBuffer.allocate(4 + 2).order(ByteOrder.BIG_ENDIAN)
                header.putInt(MAGIC)
                header.putShort(wrappedAesKey.size.toShort())
                fos.write(header.array())
                fos.write(wrappedAesKey)
                fos.write(iv)
                fos.write(encryptedPhoto)
            }

            return targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting intruder selfie: ${e.message}", e)
            return null
        }
    }

    /**
     * Decrypts an encrypted selfie file using the RSA Private Key.
     */
    fun decryptPhoto(file: File, privateKey: PrivateKey): ByteArray? {
        return try {
            FileInputStream(file).use { fis ->
                val headerBytes = ByteArray(6)
                if (fis.read(headerBytes) != 6) return null
                val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
                val magic = buf.int
                if (magic != MAGIC) return null
                val wrappedKeyLen = buf.short.toInt() and 0xFFFF

                val wrappedKey = ByteArray(wrappedKeyLen)
                if (fis.read(wrappedKey) != wrappedKeyLen) return null

                val iv = ByteArray(GCM_IV_LENGTH)
                if (fis.read(iv) != GCM_IV_LENGTH) return null

                val encryptedPhoto = fis.readBytes()

                // Unwrap AES key
                val rsaCipher = Cipher.getInstance(RSA_ALGORITHM)
                rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
                val aesKeyBytes = rsaCipher.doFinal(wrappedKey)
                val aesKey = SecretKeySpec(aesKeyBytes, "AES")

                // Decrypt photo
                val gcmCipher = Cipher.getInstance(AES_GCM_ALGORITHM)
                gcmCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                gcmCipher.doFinal(encryptedPhoto)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting selfie ${file.name}: ${e.message}", e)
            null
        }
    }

    private fun enforceStorageCap(context: Context? = null) {
        val dir = getSelfiesDir(context)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".enc") }
            ?.sortedBy { it.lastModified() } ?: return

        if (files.size >= MAX_STORED_SELFIES) {
            val toRemove = files.size - MAX_STORED_SELFIES + 1
            for (i in 0 until toRemove) {
                files[i].delete()
            }
        }
    }

    fun getAllEncryptedSelfies(context: Context? = null): List<File> {
        return getSelfiesDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".enc") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
