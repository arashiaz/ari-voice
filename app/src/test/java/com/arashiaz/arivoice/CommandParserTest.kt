package com.arashiaz.arivoice

import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {
    @Test fun searchAndOpen() {
        assertEquals(VoiceCommand.Search("آب و هوا"), CommandParser.parse("جستجو کن آب و هوا"))
        assertEquals(VoiceCommand.Search("هوا"), CommandParser.parse("هوا را جستجو کن"))
        assertEquals(VoiceCommand.OpenApp("تلگرام"), CommandParser.parse("باز كن تلگرام"))
        assertEquals(VoiceCommand.OpenApp("واتساپ"), CommandParser.parse("واتساپ رو باز کن"))
    }
    @Test fun appActions() {
        assertEquals(VoiceCommand.Tap("تنظیمات"), CommandParser.parse("روی تنظیمات بزن"))
        assertEquals(VoiceCommand.Type("سلام"), CommandParser.parse("بنویس سلام"))
        assertEquals(VoiceCommand.Type("Hello World!"), CommandParser.parse("type Hello World!"))
        assertEquals(VoiceCommand.Scroll(true), CommandParser.parse("اسکرول پایین"))
        assertEquals(VoiceCommand.Back, CommandParser.parse("برو عقب"))
    }
    @Test fun conversationalEnglishCommands() {
        assertEquals(VoiceCommand.Search("weather in new york"), CommandParser.parse("Search for weather in New York"))
        assertEquals(VoiceCommand.OpenApp("calculator"), CommandParser.parse("Open the calculator app"))
        assertEquals(VoiceCommand.OpenApp("camera"), CommandParser.parse("Launch camera"))
        assertEquals(VoiceCommand.Tap("settings"), CommandParser.parse("Tap on settings"))
        assertEquals(VoiceCommand.Type("Hello World!"), CommandParser.parse("write Hello World!"))
        assertEquals(VoiceCommand.Back, CommandParser.parse("go back"))
    }

    @Test fun colloquialPersianCommands() {
        assertEquals(VoiceCommand.OpenApp("تلگرام"), CommandParser.parse("تلگرام رو باز كن"))
        assertEquals(VoiceCommand.Search("هوا"), CommandParser.parse("تو گوگل هوا"))
        assertEquals(VoiceCommand.Tap("تایید"), CommandParser.parse("گزینه تایید رو بزن"))
        assertEquals(VoiceCommand.Scroll(true), CommandParser.parse("صفحه را پایین ببر"))
    }

    @Test fun languageSelection() {
        assertEquals(SpeechLanguage.PERSIAN, SpeechLanguage.fromCode("fa"))
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.fromCode("en"))
        assertEquals(SpeechLanguage.PERSIAN, SpeechLanguage.fromCode("invalid"))
    }

    @Test fun noGuessedActions() {
        assertNull(CommandParser.parse(""))
        assertNull(CommandParser.parse("باز کن"))
        assertNull(CommandParser.parse("سلام خوبی"))
        assertNull(CommandParser.parse("پیام را ارسال کن"))
    }
}
