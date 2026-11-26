package com.balanceone.nativeapp.migration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Private, encrypted, offline copy of the complete original backup.
 * No web writes, cloud uploads, or plaintext files. Call on an IO dispatcher.
 * AtomicFile preserves the previous file if a write fails.
 */
internal object LocalSnapshotStore {
    private const val ALIAS = "balanceone.original.snapshot.v1"
    private const val MAX_BYTES = 64 * 1024 * 1024
    private val magic = byteArrayOf(66, 79, 83, 1)
    private fun file(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, "original-snapshot.enc"))
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing != null) return existing as SecretKey
        check(create) { "Snapshot key missing" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized
    fun load(context: Context): OriginalBackup? {
        val storage = file(context)
        val stream = try { storage.openRead() } catch (e: FileNotFoundException) {
            if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return null
            throw e
        }
        val encoded = stream.use {
            require(it.channel.size() in 32L..(MAX_BYTES + 32L))
            it.readBytes()
        }
        require(encoded.copyOfRange(0,4).contentEquals(magic))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encoded.copyOfRange(4,16)))
        cipher.updateAAD(magic)
        val plain = cipher.doFinal(encoded,16,encoded.size - 16)
        return try { OriginalBackup.parse(plain.toString(Charsets.UTF_8)) }
        finally { plain.fill(0) }
    }

    @Synchronized
    fun save(context: Context, snapshot: OriginalBackup) {
        val plain = snapshot.root.toString().toByteArray(Charsets.UTF_8)
        val encoded = try {
            require(plain.size <= MAX_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            require(cipher.iv.size == 12)
            cipher.updateAAD(magic)
            magic + cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        val storage = file(context)
        val output = storage.startWrite()
        try {
            output.write(encoded)
            storage.finishWrite(output)
        } catch (e: Exception) {
            storage.failWrite(output)
            throw e
        }
    }

    @Synchronized
    fun clear(context: Context) {
        // Only remove this device's copy, never the selected document or the web.
        val storage = file(context)
        storage.delete()
        check(!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()
            && !File(storage.baseFile.path + ".new").exists())
    }
}
