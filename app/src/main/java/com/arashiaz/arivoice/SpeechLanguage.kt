package com.arashiaz.arivoice

/** Speech model selection is independent of the phone's UI locale. */
enum class SpeechLanguage(val code: String, val recognitionTag: String, val modelFolder: String) {
    PERSIAN("fa", "fa-IR", "model-active"),
    ENGLISH("en", "en-US", "model-en");

    companion object {
        fun fromCode(value: String?): SpeechLanguage = entries.firstOrNull { it.code == value } ?: PERSIAN
    }
}
