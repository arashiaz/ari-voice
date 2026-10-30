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
    @Test fun noGuessedActions() {
        assertNull(CommandParser.parse(""))
        assertNull(CommandParser.parse("باز کن"))
        assertNull(CommandParser.parse("سلام خوبی"))
        assertNull(CommandParser.parse("پیام را ارسال کن"))
    }
}
