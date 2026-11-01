package com.arashiaz.arivoice

/** Voice-friendly aliases for launcher labels. Only known, user-requested names are mapped. */
object AppNameMatcher {
    private data class Alias(val names: List<String>, val packages: List<String> = emptyList())

    private val aliases = mapOf(
        "تلگرام" to Alias(listOf("telegram"), listOf("org.telegram.messenger", "org.telegram.messenger.web")),
        "تل گرام" to Alias(listOf("telegram"), listOf("org.telegram.messenger", "org.telegram.messenger.web")),
        "تلگرام ایکس" to Alias(listOf("telegram x"), listOf("org.thunderdog.challegram")),
        "واتساپ" to Alias(listOf("whatsapp"), listOf("com.whatsapp")),
        "واتس اپ" to Alias(listOf("whatsapp"), listOf("com.whatsapp")),
        "اینستاگرام" to Alias(listOf("instagram"), listOf("com.instagram.android")),
        "اینستا" to Alias(listOf("instagram"), listOf("com.instagram.android")),
        "یوتیوب" to Alias(listOf("youtube"), listOf("com.google.android.youtube")),
        "یو تیوب" to Alias(listOf("youtube"), listOf("com.google.android.youtube")),
        "کروم" to Alias(listOf("chrome"), listOf("com.android.chrome")),
        "نقشه" to Alias(listOf("maps", "google maps"), listOf("com.google.android.apps.maps")),
        "جیمیل" to Alias(listOf("gmail"), listOf("com.google.android.gm")),
        "دوربین" to Alias(listOf("camera")),
        "ماشین حساب" to Alias(listOf("calculator")),
        "گالری" to Alias(listOf("gallery", "photos")),
        "عکس ها" to Alias(listOf("photos", "gallery"))
    )

    fun packageCandidates(request: String): List<String> = aliases[CommandParser.normalize(request)]?.packages.orEmpty()

    fun score(request: String, launcherLabel: String): Int {
        val name = CommandParser.normalize(request)
        val label = CommandParser.normalize(launcherLabel)
        if (name.isBlank() || label.isBlank()) return 0
        if (name == label) return 100
        val known = aliases[name]?.names.orEmpty()
        if (label in known) return 90
        if (known.any { label.startsWith("$it ") || label.startsWith("$it-") }) return 60
        if (name.length >= 3 && (label.startsWith("$name ") || label.startsWith("$name-"))) return 50
        // No substring guesses: 'گرام' must not unexpectedly open Instagram.
        return 0
    }
}
