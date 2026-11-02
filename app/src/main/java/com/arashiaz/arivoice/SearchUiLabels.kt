package com.arashiaz.arivoice

/** Conservative labels for navigating to search. A non-search control must never be clicked. */
internal object SearchUiLabels {
    private val search = setOf(
        "search", "search tab", "search products", "جستجو", "جست و جو", "سرچ", "جستجوی محصولات"
    )
    private val instagramExplore = setOf(
        "explore", "explore tab", "search and explore", "explore and search", "اکسپلور", "کاوش", "جستجو و کاوش"
    )

    fun isSearchEntry(label: String, targetPackage: String): Boolean {
        val normalized = CommandParser.normalize(label)
        return normalized in search ||
            (targetPackage == "com.instagram.android" && normalized in instagramExplore)
    }
}
