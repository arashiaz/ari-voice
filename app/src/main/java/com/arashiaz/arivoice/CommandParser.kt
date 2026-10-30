package com.arashiaz.arivoice

/** Deliberately deterministic: no command is sent to an external AI or executed as shell code. */
sealed interface VoiceCommand {
    data class Search(val query: String) : VoiceCommand
    data class OpenApp(val name: String) : VoiceCommand
    data class Tap(val label: String) : VoiceCommand
    data class Type(val text: String) : VoiceCommand
    data class Scroll(val down: Boolean) : VoiceCommand
    data object Back : VoiceCommand
    data object Home : VoiceCommand
    data object Notifications : VoiceCommand
}

object CommandParser {
    fun normalize(text: String): String = text.lowercase()
        .replace('ي', 'ی').replace('ك', 'ک').replace('ة', 'ه')
        .replace(Regex("[\\u064b-\\u065f\\u0670]"), "")
        .replace('\u200c', ' ').replace(Regex("\\s+"), " ").trim()

    fun parse(input: String): VoiceCommand? {
        val s = normalize(input).trimEnd('؟', '?', '.', '،')
        if (s.isBlank()) return null
        if (s in listOf("برو عقب", "برگرد", "عقب", "back")) return VoiceCommand.Back
        if (s in listOf("برو خانه", "صفحه اصلی", "خانه", "home")) return VoiceCommand.Home
        if (s in listOf("اعلان ها", "اعلان ها را باز کن", "نوتیفیکیشن ها")) return VoiceCommand.Notifications
        if (s in listOf("اسکرول پایین", "برو پایین", "پایین برو", "scroll down")) return VoiceCommand.Scroll(true)
        if (s in listOf("اسکرول بالا", "برو بالا", "بالا برو", "scroll up")) return VoiceCommand.Scroll(false)

        for (prefix in listOf("جستجو کن ", "جستجوی ", "جستجو برای ", "سرچ کن ", "سرچ ", "گوگل کن ", "search ")) {
            if (s.startsWith(prefix)) return value(s.removePrefix(prefix)) { VoiceCommand.Search(it) }
        }
        Regex("^(.+?) (?:را|رو) جستجو کن$").matchEntire(s)?.let {
            return value(it.groupValues[1]) { query -> VoiceCommand.Search(query) }
        }
        for (prefix in listOf("باز کن ", "برنامه ", "اپلیکیشن ", "اجرای ", "open ")) {
            if (s.startsWith(prefix)) return value(s.removePrefix(prefix)) { VoiceCommand.OpenApp(it) }
        }
        Regex("^(.+?) (?:را|رو) باز کن$").matchEntire(s)?.let {
            return value(it.groupValues[1]) { name -> VoiceCommand.OpenApp(name) }
        }
        for (prefix in listOf("روی ", "کلیک روی ", "tap ")) {
            if (s.startsWith(prefix)) {
                val label = s.removePrefix(prefix).removeSuffix(" بزن").removeSuffix(" کلیک کن")
                return value(label) { VoiceCommand.Tap(it) }
            }
        }
        // Keep the user's original casing and punctuation in dictated text.
        val original = input.trim().replace('\u200c', ' ').replace('ي', 'ی').replace('ك', 'ک')
        val typed = Regex("^(?:بنویس|تایپ کن|متن|type)\\s+(.+)$", RegexOption.IGNORE_CASE)
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
