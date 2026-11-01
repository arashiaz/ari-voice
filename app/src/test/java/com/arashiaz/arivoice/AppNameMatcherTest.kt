package com.arashiaz.arivoice

import org.junit.Assert.*
import org.junit.Test

class AppNameMatcherTest {
    @Test fun persianNamesResolveEnglishLauncherLabels() {
        assertEquals(90, AppNameMatcher.score("تلگرام", "Telegram"))
        assertEquals(90, AppNameMatcher.score("واتساپ", "WhatsApp"))
        assertEquals(90, AppNameMatcher.score("اینستا", "Instagram"))
        assertEquals(90, AppNameMatcher.score("یوتیوب", "YouTube"))
        assertEquals(90, AppNameMatcher.score("نقشه", "Google Maps"))
    }
    @Test fun explicitPackagesAndSafeFallbacks() {
        assertEquals("org.telegram.messenger", AppNameMatcher.packageCandidates("تلگرام").first())
        assertEquals(100, AppNameMatcher.score("Telegram X", "Telegram X"))
        assertEquals(60, AppNameMatcher.score("تلگرام", "Telegram X"))
        assertEquals(0, AppNameMatcher.score("گرام", "Instagram"))
        assertEquals(0, AppNameMatcher.score("تلگرام", "Instagram"))
        assertTrue(AppNameMatcher.packageCandidates("برنامه ناشناس").isEmpty())
    }
}
