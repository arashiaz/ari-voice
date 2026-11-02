package com.arashiaz.arivoice

/** Explicit, short-lived follow-up only. This parser cannot issue purchases or arbitrary taps. */
internal object FollowUpParser {
    private val references = listOf("حالا ", "همونجا ", "همانجا ", "در همان برنامه ", "توی همون برنامه ", "now ")

    fun referencesPreviousApp(input: String): Boolean {
        val text = CommandParser.normalize(input)
        val intro = references.firstOrNull { text.startsWith(it) } ?: return false
        val task = text.removePrefix(intro)
        return listOf("جستجو", "سرچ", "پیدا کن", "search ").any { task.contains(it) }
    }

    fun parse(input: String, app: String): VoiceCommand? {
        if (app.isBlank()) return null
        val normalized = CommandParser.normalize(input).trimEnd('؟', '?', '.', '،')
        val intro = references.firstOrNull { normalized.startsWith(it) } ?: return null
        val task = normalized.removePrefix(intro).trim()
        if (task in listOf("جستجو کن", "سرچ کن", "search for", "search")) return null
        val query = listOf("جستجو کن ", "جستجو ", "سرچ کن ", "سرچ ", "search for ", "search ")
            .firstOrNull { task.startsWith(it) }?.let { task.removePrefix(it) }
            ?: Regex("^(.+?) (?:را |رو )?(?:جستجو کن|سرچ کن|پیدا کن)$")
                .matchEntire(task)?.groupValues?.get(1)
            ?: return null
        val cleaned = query.trim().removeSuffix(" را").removeSuffix(" رو").trim()
        if (cleaned.isEmpty()) return null
        val profile = Regex("^(?:پروفایل|profile) (.+)$").matchEntire(cleaned)?.groupValues?.get(1)
        return if (profile != null) VoiceCommand.ProfileInApp(app, profile.removePrefix("@"))
               else VoiceCommand.FindInApp(app, cleaned)
    }
}
