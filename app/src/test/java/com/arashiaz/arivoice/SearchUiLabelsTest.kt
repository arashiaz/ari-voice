package com.arashiaz.arivoice

import org.junit.Assert.*
import org.junit.Test

class SearchUiLabelsTest {
    @Test fun searchEntriesAcrossCompatibleApps() {
        assertTrue(SearchUiLabels.isSearchEntry("Search", "org.telegram.messenger"))
        assertTrue(SearchUiLabels.isSearchEntry("Search tab", "com.instagram.android"))
        assertTrue(SearchUiLabels.isSearchEntry("جستجو", "com.instagram.android"))
        assertTrue(SearchUiLabels.isSearchEntry("search products", "com.example.store"))
    }

    @Test fun instagramExploreIsScopedAndSensitiveControlsAreRejected() {
        assertTrue(SearchUiLabels.isSearchEntry("Search and Explore", "com.instagram.android"))
        assertTrue(SearchUiLabels.isSearchEntry("Explore", "com.instagram.android"))
        assertTrue(SearchUiLabels.isSearchEntry("اکسپلور", "com.instagram.android"))
        assertFalse(SearchUiLabels.isSearchEntry("Explore", "com.example.store"))
        assertFalse(SearchUiLabels.isSearchEntry("Checkout", "com.instagram.android"))
        assertFalse(SearchUiLabels.isSearchEntry("Buy now", "com.instagram.android"))
        assertFalse(SearchUiLabels.isSearchEntry("Confirm", "com.instagram.android"))
    }
}
