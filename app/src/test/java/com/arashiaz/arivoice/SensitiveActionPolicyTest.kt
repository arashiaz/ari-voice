package com.arashiaz.arivoice

import org.junit.Assert.*
import org.junit.Test

class SensitiveActionPolicyTest {
    @Test fun paymentAndSendCannotBeVoiceClicked() {
        assertTrue(SensitiveActionPolicy.isUnsafeClick("پرداخت نهایی"))
        assertTrue(SensitiveActionPolicy.isUnsafeClick("تأیید سفارش"))
        assertTrue(SensitiveActionPolicy.isUnsafeClick("Place Order"))
        assertTrue(SensitiveActionPolicy.isUnsafeClick("ارسال پیام"))
        assertTrue(SensitiveActionPolicy.isUnsafeClick("checkout"))
    }
    @Test fun navigationRemainsPossible() {
        assertFalse(SensitiveActionPolicy.isUnsafeClick("جستجو"))
        assertFalse(SensitiveActionPolicy.isUnsafeClick("پروفایل"))
        assertFalse(SensitiveActionPolicy.isUnsafeClick("نمایش سبد"))
    }
}
