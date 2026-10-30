package com.arashiaz.arivoice

import android.content.Context
import android.net.Uri
import java.io.File

object ChatModel {
    fun file(context: Context) = File(context.filesDir, "assistant.gguf")
    fun installed(context: Context) = valid(file(context))

    private fun valid(file: File): Boolean {
        if (!file.isFile || file.length() < 100_000_000L) return false
        return file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && String(header, Charsets.US_ASCII) == "GGUF"
        }
    }

    /** Copy user selected GGUF into private storage. Call off the UI thread, never while inference is active. */
    fun import(context: Context, uri: Uri) {
        val staging = File(context.filesDir, "assistant-partial.gguf")
        val backup = File(context.filesDir, "assistant-backup.gguf")
        val current = file(context)
        staging.delete()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("فایل باز نشد")
            input.use { stream -> staging.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= 4_000_000_000L) { "فایل مدل بیش از حد بزرگ است" }
                    output.write(buffer, 0, count)
                }
            } }
            require(valid(staging)) { "فایل GGUF معتبر نیست یا ناقص است" }
            backup.delete()
            if (current.exists()) require(current.renameTo(backup)) { "انتقال مدل قبلی ناموفق بود" }
            if (!staging.renameTo(current)) {
                if (backup.exists()) backup.renameTo(current)
                error("ذخیره مدل ناموفق بود")
            }
            backup.delete()
        } finally { staging.delete() }
    }
}
