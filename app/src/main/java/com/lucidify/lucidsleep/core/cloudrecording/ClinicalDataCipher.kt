package com.lucidify.lucidsleep.core.cloudrecording

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound authenticated encryption for temporary clinical data.
 *
 * File format:
 *   magic (4 bytes) = LCX1
 *   IV length (1 byte)
 *   IV
 *   AES-GCM ciphertext and authentication tag
 *
 * The AES key is generated inside Android Keystore and is not exportable.
 * User authentication is deliberately not required for every key operation so
 * an active recording can continue across a temporary network outage or screen lock.
 */
class ClinicalDataCipher {
	private val key: SecretKey by lazy { loadOrCreateKey() }

	fun isEncrypted(file: File): Boolean {
		if (!file.isFile || file.length() < MAGIC.size) return false
		FileInputStream(file).use { input ->
			val header = ByteArray(MAGIC.size)
			if (input.read(header) != header.size) return false
			return header.contentEquals(MAGIC)
		}
	}

	fun encryptFile(source: File, destination: File) {
		require(source.isFile) { "Encryption source does not exist: ${source.absolutePath}" }
		destination.parentFile?.mkdirs()
		val cipher = Cipher.getInstance(TRANSFORMATION).apply {
			init(Cipher.ENCRYPT_MODE, key)
		}
		val iv = cipher.iv ?: error("Android Keystore did not provide an encryption IV")
		FileInputStream(source).use { input ->
			FileOutputStream(destination).use { output ->
				output.write(MAGIC)
				output.write(iv.size)
				output.write(iv)
				val buffer = ByteArray(BUFFER_BYTES)
				while (true) {
					val count = input.read(buffer)
					if (count < 0) break
					if (count > 0) cipher.update(buffer, 0, count)?.let(output::write)
				}
				cipher.doFinal()?.let(output::write)
				output.flush()
				output.fd.sync()
			}
		}
	}

	fun decryptFile(source: File, destination: File) {
		require(source.isFile) { "Decryption source does not exist: ${source.absolutePath}" }
		if (!isEncrypted(source)) {
			source.inputStream().use { input ->
				FileOutputStream(destination).use { output ->
					input.copyTo(output, BUFFER_BYTES)
					output.flush()
					output.fd.sync()
				}
			}
			return
		}
		FileInputStream(source).use { input ->
			val magic = ByteArray(MAGIC.size)
			require(input.read(magic) == magic.size && magic.contentEquals(MAGIC)) {
				"Invalid encrypted clinical-data header"
			}
			val ivLength = input.read()
			require(ivLength in 12..32) { "Invalid encrypted clinical-data IV length" }
			val iv = ByteArray(ivLength)
			require(input.read(iv) == ivLength) { "Truncated encrypted clinical-data IV" }
			val cipher = Cipher.getInstance(TRANSFORMATION).apply {
				init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
			}
			FileOutputStream(destination).use { output ->
				val buffer = ByteArray(BUFFER_BYTES)
				while (true) {
					val count = input.read(buffer)
					if (count < 0) break
					if (count > 0) cipher.update(buffer, 0, count)?.let(output::write)
				}
				cipher.doFinal()?.let(output::write)
				output.flush()
				output.fd.sync()
			}
		}
	}

	fun encryptBytes(plain: ByteArray): ByteArray {
		val cipher = Cipher.getInstance(TRANSFORMATION).apply {
			init(Cipher.ENCRYPT_MODE, key)
		}
		val iv = cipher.iv ?: error("Android Keystore did not provide an encryption IV")
		return MAGIC + byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
	}

	fun decryptBytes(stored: ByteArray): ByteArray {
		if (stored.size < MAGIC.size || !stored.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return stored
		val ivLength = stored[MAGIC.size].toInt() and 0xff
		require(ivLength in 12..32 && stored.size > MAGIC.size + 1 + ivLength) {
			"Invalid encrypted clinical-data payload"
		}
		val ivStart = MAGIC.size + 1
		val iv = stored.copyOfRange(ivStart, ivStart + ivLength)
		val encrypted = stored.copyOfRange(ivStart + ivLength, stored.size)
		val cipher = Cipher.getInstance(TRANSFORMATION).apply {
			init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
		}
		return cipher.doFinal(encrypted)
	}

	private fun loadOrCreateKey(): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
		(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setRandomizedEncryptionRequired(true)
				.build()
		)
		return generator.generateKey()
	}

	companion object {
		private val MAGIC = byteArrayOf('L'.code.toByte(), 'C'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())
		private const val KEY_ALIAS = "lucidify_clinical_data_v1"
		private const val ANDROID_KEYSTORE = "AndroidKeyStore"
		private const val TRANSFORMATION = "AES/GCM/NoPadding"
		private const val TAG_BITS = 128
		private const val BUFFER_BYTES = 64 * 1024
	}
}