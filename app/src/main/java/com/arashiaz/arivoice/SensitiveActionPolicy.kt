package com.arashiaz.arivoice

/** Conservative voice-click guard. UI semantics vary; it is not a substitute for user review. */
object SensitiveActionPolicy {
    private val blocked = listOf(
        "پرداخت", "خرید", "ثبت سفارش", "نهایی کردن", "تایید سفارش", "تأیید سفارش",
        "ارسال", "تایید", "تأیید", "pay", "checkout", "purchase", "place order",
        "confirm order", "send", "confirm"
    )

    fun isUnsafeClick(label: String): Boolean {
        val text = CommandParser.normalize(label)
        return blocked.any { marker ->
            val normalized = CommandParser.normalize(marker)
            if (normalized.any { it in 'a'..'z' }) {
                Regex("(^|[^a-z])${Regex.escape(normalized)}($|[^a-z])").containsMatchIn(text)
            } else text.contains(normalized)
        }
    }
}
