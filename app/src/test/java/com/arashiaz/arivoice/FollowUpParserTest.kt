package com.arashiaz.arivoice

import org.junit.Assert.*
import org.junit.Test

class FollowUpParserTest {
    @Test fun contextualSearchAndProfileRequireAnExplicitReference() {
        assertEquals(VoiceCommand.FindInApp("اینستاگرام", "اردلان"),
            FollowUpParser.parse("حالا اردلان رو جستجو کن", "اینستاگرام"))
        assertEquals(VoiceCommand.FindInApp("فروشگاه من", "نوشابه"),
            FollowUpParser.parse("همونجا جستجو کن نوشابه", "فروشگاه من"))
        assertEquals(VoiceCommand.ProfileInApp("اینستاگرام", "arash"),
            FollowUpParser.parse("حالا پروفایل @arash را جستجو کن", "اینستاگرام"))
        assertTrue(FollowUpParser.referencesPreviousApp("حالا اردلان رو جستجو کن"))
        assertFalse(FollowUpParser.referencesPreviousApp("حالا ساعت چنده؟"))
        assertNull(FollowUpParser.parse("اردلان رو جستجو کن", "اینستاگرام"))
    }

    @Test fun unsupportedTasksNeverBecomeBlindActions() {
        assertNull(FollowUpParser.parse("حالا بخر", "فروشگاه من"))
        assertNull(FollowUpParser.parse("حالا روی پرداخت بزن", "فروشگاه من"))
        assertNull(FollowUpParser.parse("حالا جستجو کن", "اینستاگرام"))
        assertNull(FollowUpParser.parse("حالا اردلان رو جستجو کن", ""))
    }
}
