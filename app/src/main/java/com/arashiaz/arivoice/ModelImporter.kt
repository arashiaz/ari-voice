package com.arashiaz.arivoice

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/** Imports a user-selected Vosk ZIP into private storage; never downloads anything. Call off the UI thread. */
object ModelImporter {
    fun installedModel(context: Context) = File(context.filesDir, "model-active")
    fun isInstalled(context: Context) = File(installedModel(context), "am/final.mdl").isFile

    fun import(context: Context, uri: Uri) {
        val staging = File(context.filesDir, "model-staging")
        val current = installedModel(context)
        val backup = File(context.filesDir, "model-backup")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("فایل باز نشد")
            input.use { stream -> ZipInputStream(stream.buffered()).use { zip ->
                var count = 0
                var total = 0L
                var entry = zip.nextEntry
                while (entry != null) {
                    count++
                    require(count <= 5000) { "فایل ZIP ورودی بیش از حد بزرگ است" }
                    val file = File(staging, entry.name)
                    require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        "مسیر نامعتبر در فایل ZIP"
                    }
                    if (entry.isDirectory) file.mkdirs() else {
                        file.parentFile?.mkdirs()
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= 1_000_000_000L) { "مدل بیش از حد بزرگ است" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            } }
            // Official Vosk archives contain one top-level model directory; also accept a flat ZIP.
            val modelDir = if (File(staging, "am/final.mdl").isFile) staging
                           else staging.listFiles()?.filter { it.isDirectory && File(it, "am/final.mdl").isFile }?.singleOrNull()
            require(modelDir != null) { "این ZIP مدل Vosk نیست (am/final.mdl پیدا نشد)" }
            backup.deleteRecursively()
            if (current.exists()) require(current.renameTo(backup)) { "انتقال مدل قبلی ناموفق بود" }
            if (!modelDir.renameTo(current)) {
                if (backup.exists()) backup.renameTo(current)
                error("ذخیره مدل جدید ناموفق بود")
            }
            backup.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }
}
