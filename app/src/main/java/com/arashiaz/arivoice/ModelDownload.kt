package com.arashiaz.arivoice

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Explicit, foreground-only model download; runs on a worker thread. No hidden network fallback. */
object ModelDownload {
    fun download(url: String, destination: File, limit: Long, cancelled: () -> Boolean,
                 progress: (Int) -> Unit, expectedSha256: String? = null) {
        var current = URL(url)
        require(current.protocol == "https")
        destination.parentFile?.mkdirs()
        try {
            var connection: HttpURLConnection? = null
            for (i in 0..5) {
                connection?.disconnect()
                connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 40_000
                    setRequestProperty("User-Agent", "ARI-VOICE/1.0")
                }
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("نشانی انتقال مدل نامعتبر است")
                    current = URL(current, location)
                    require(current.protocol == "https") { "انتقال به اتصال غیرامن مجاز نیست" }
                    continue
                }
                require(code == 200) { "پاسخ دانلود مدل: $code" }
                val length = connection.contentLengthLong
                require(length < 0 || length <= limit) { "فایل بزرگ‌تر از حد مجاز است" }
                var total = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                var lastPercent = -1
                connection.inputStream.use { input -> destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) error("دانلود لغو شد")
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= limit) { "فایل بزرگ‌تر از حد مجاز است" }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        if (length > 0) {
                            val percent = (100L * total / length).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) { progress(percent); lastPercent = percent }
                        }
                    }
                } }
                require(total > 1_024) { "مدل ناقص دانلود شد" }
                if (length > 0) require(total == length) { "دانلود ناقص است" }
                if (expectedSha256 != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual.equals(expectedSha256, ignoreCase = true)) { "اثر انگشت فایل مدل تطابق ندارد" }
                }
                connection.disconnect()
                return
            }
            error("تعداد انتقال‌های دانلود بیش از حد است")
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }
}
