package com.arcxya09.touch.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small secrets only. No plaintext fallback and no replacement of an unreadable key. */
class LocalVault(context: Context) {
    private val folder = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val alias = "touch.local.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(folder.listFiles().isNullOrEmpty()) { "本机加密密钥不可用，无法读取缓存" }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun read(name: String): ByteArray? {
        val file = AtomicFile(File(folder, name))
        if (!file.baseFile.exists() && !File(folder, "$name.bak").exists()) return null
        val bytes = file.readFully()
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "本机加密配置损坏" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(name.toByteArray())
            doFinal(bytes.copyOfRange(13, bytes.size))
        }
    }
    @Synchronized fun write(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray())
        }
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(value)
        val file = AtomicFile(File(folder, name))
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    @Synchronized fun secret(name: String, mayCreate: Boolean = true, create: () -> ByteArray = {
        ByteArray(32).also { SecureRandom().nextBytes(it) }
    }): ByteArray = read(name) ?: run {
        check(mayCreate) { "本机加密密钥缺失，已停止读取缓存" }
        create().also { write(name, it) }
    }
}
