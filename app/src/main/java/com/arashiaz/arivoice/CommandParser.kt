package com.arashiaz.arivoice

/** Deliberately deterministic: no command is sent to an external AI or executed as shell code. */
sealed interface VoiceCommand {
    data class Search(val query: String) : VoiceCommand
    data class OpenApp(val name: String) : VoiceCommand
    data class Tap(val label: String) : VoiceCommand
    data class Type(val text: String) : VoiceCommand
    /** Only finds a product/profile; it never selects items, orders, or pays. */
    data class FindInApp(val app: String, val query: String, val purchaseIntent: Boolean = false) : VoiceCommand
    data class ProfileInApp(val app: String, val username: String?) : VoiceCommand
    data class Media(val action: MediaAction) : VoiceCommand
    data class MediaInApp(val app: String, val action: MediaAction) : VoiceCommand
    data class Download(val url: String) : VoiceCommand
    data object DownloadPrompt : VoiceCommand
    data class Scroll(val down: Boolean) : VoiceCommand
    data object Back : VoiceCommand
    data object Home : VoiceCommand
    data object Notifications : VoiceCommand
}

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

object CommandParser {
    fun normalize(text: String): String = text.lowercase()
        .replace('ي', 'ی').replace('ك', 'ک').replace('ة', 'ه')
        .replace(Regex("[\\u064b-\\u065f\\u0670]"), "")
        .replace('\u200c', ' ').replace(Regex("\\s+"), " ").trim()

    fun parse(input: String): VoiceCommand? {
        val s = normalize(input).trimEnd('؟', '?', '.', '،')
        if (s.isBlank()) return null
        if (s in listOf("موزیک پخش کن", "موزیک رو پخش کن", "آهنگ پخش کن", "پخش موزیک", "play music", "play song")) return VoiceCommand.Media(MediaAction.PLAY)
        if (s in listOf("موزیک رو قطع کن", "موزیک را قطع کن", "آهنگ رو قطع کن", "موزیک مکث", "pause music", "pause song")) return VoiceCommand.Media(MediaAction.PAUSE)
        if (s in listOf("آهنگ بعدی", "موزیک بعدی", "برو آهنگ بعدی", "next song", "next track")) return VoiceCommand.Media(MediaAction.NEXT)
        if (s in listOf("آهنگ قبلی", "موزیک قبلی", "previous song", "previous track")) return VoiceCommand.Media(MediaAction.PREVIOUS)
        Regex("^(?:دانلود کن|دانلود|download)\\s+(https://\\S+)$", RegexOption.IGNORE_CASE).matchEntire(input.trim())?.let {
            return VoiceCommand.Download(it.groupValues[1].trimEnd('.', '،', '؛'))
        }
        if (s == "دانلود کن" || s == "دانلود" || s == "download" ||
            s.startsWith("دانلود کن ") || s.startsWith("download ")) return VoiceCommand.DownloadPrompt
        // Multi-step requests must be interpreted before generic "open app" commands.
        val compound = Regex("^(?:برو (?:تو |داخل |به )?|باز کن |open )(.*?)(?: و | and )(.*)$")
            .matchEntire(s) ?: Regex("^(.+?) برو و (.+)$").matchEntire(s)
        if (compound != null) {
            val app = compound.groupValues[1].trim()
            val task = compound.groupValues[2].trim()
            if (app.isNotEmpty()) {
                Regex("^پروفایل(?:\\s+(.+?))?\\s*(?:را|رو)?\\s*باز کن$").matchEntire(task)?.let {
                    return VoiceCommand.ProfileInApp(app, it.groupValues[1].ifBlank { null })
                }
                if (task == "پروفایل باز کن") return VoiceCommand.ProfileInApp(app, null)
                val buy = Regex("^(.+?)\\s+(?:را|رو)?\\s*بخر$").matchEntire(task)
                if (buy != null) return value(buy.groupValues[1]) { VoiceCommand.FindInApp(app, it, true) }
                val find = Regex("^(.+?)\\s+(?:را|رو)?\\s*(?:پیدا کن|جستجو کن|سرچ کن)$").matchEntire(task)
                if (find != null) return value(find.groupValues[1]) { VoiceCommand.FindInApp(app, it) }
                if (task.startsWith("search for ")) return value(task.removePrefix("search for ")) { VoiceCommand.FindInApp(app, it) }
                if (task.startsWith("find ")) return value(task.removePrefix("find ")) { VoiceCommand.FindInApp(app, it) }
                if (task.startsWith("open profile ")) return value(task.removePrefix("open profile ")) { VoiceCommand.ProfileInApp(app, it) }
                if (task in listOf("موزیک پخش کن", "آهنگ پخش کن", "play music")) return VoiceCommand.MediaInApp(app, MediaAction.PLAY)
                if (task in listOf("موزیک رو قطع کن", "موزیک را قطع کن", "pause music")) return VoiceCommand.MediaInApp(app, MediaAction.PAUSE)
            }
            // Never reinterpret an unrecognized two-step command as an app name.
            return null
        }
        if (s in listOf("برو عقب", "برگرد", "برگشت", "عقب", "back", "go back")) return VoiceCommand.Back
        if (s in listOf("برو خانه", "صفحه اصلی", "خانه", "home", "go home")) return VoiceCommand.Home
        if (s in listOf("اعلان ها", "اعلان ها را باز کن", "نوتیفیکیشن ها", "notifications", "show notifications")) return VoiceCommand.Notifications
        if (s in listOf("اسکرول پایین", "برو پایین", "پایین برو", "صفحه را پایین ببر", "scroll down")) return VoiceCommand.Scroll(true)
        if (s in listOf("اسکرول بالا", "برو بالا", "بالا برو", "صفحه را بالا ببر", "scroll up")) return VoiceCommand.Scroll(false)

        for (prefix in listOf("جستجو کن ", "جستجوی ", "جستجو برای ", "سرچ کن ", "سرچ ", "گوگل کن ", "تو گوگل ", "در گوگل ", "search for ", "search ", "google ")) {
            if (s.startsWith(prefix)) return value(s.removePrefix(prefix)) { VoiceCommand.Search(it) }
        }
        Regex("^(.+?) (?:را|رو) جستجو کن$").matchEntire(s)?.let {
            return value(it.groupValues[1]) { query -> VoiceCommand.Search(query) }
        }
        for (prefix in listOf("باز کن ", "برنامه ", "اپلیکیشن ", "اجرای ", "اجرا کن ", "open the ", "launch ", "open ")) {
            if (s.startsWith(prefix)) {
                val name = s.removePrefix(prefix).removeSuffix(" app").removeSuffix(" application")
                return value(name) { VoiceCommand.OpenApp(it) }
            }
        }
        Regex("^(.+?) (?:را|رو) باز کن$").matchEntire(s)?.let {
            return value(it.groupValues[1]) { name -> VoiceCommand.OpenApp(name) }
        }
        for (prefix in listOf("روی ", "کلیک روی ", "گزینه ", "tap on ", "click on ", "tap ", "click ", "press ")) {
            if (s.startsWith(prefix)) {
                val label = s.removePrefix(prefix).removeSuffix(" را بزن").removeSuffix(" رو بزن")
                    .removeSuffix(" بزن").removeSuffix(" کلیک کن")
                return value(label) { VoiceCommand.Tap(it) }
            }
        }
        // Keep the user's original casing and punctuation in dictated text.
        val original = input.trim().replace('\u200c', ' ').replace('ي', 'ی').replace('ك', 'ک')
        val typed = Regex("^(?:بنویس|تایپ کن|متن|type|write|enter)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(original)?.groupValues?.get(1)
        if (typed != null) {
            val text = typed.replace(Regex("\\s+(?:را|رو) بنویس$"), "")
            return value(text) { VoiceCommand.Type(it) }
        }
        return null
    }

    private inline fun <T> value(value: String, create: (String) -> T): T? =
        value.trim().takeIf { it.isNotEmpty() }?.let(create)
}
