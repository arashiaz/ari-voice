package com.arashiaz.arivoice

import org.junit.Assert.*
import org.junit.Test

class WorkflowParserTest {
    @Test fun shoppingStopsAtSearch() {
        assertEquals(VoiceCommand.FindInApp("فروشگاه من", "نوشابه", true),
            CommandParser.parse("برو فروشگاه من و نوشابه بخر"))
        assertEquals(VoiceCommand.FindInApp("دیجی کالا", "نوشابه", true),
            CommandParser.parse("دیجی کالا برو و نوشابه رو بخر"))
        assertEquals(VoiceCommand.FindInApp("دیجی کالا", "نوشابه"),
            CommandParser.parse("برو دیجی کالا و نوشابه را پیدا کن"))
        assertEquals(VoiceCommand.FindInApp("مرورگر", "آب و هوا"),
            CommandParser.parse("برو مرورگر و آب و هوا را جستجو کن"))
    }

    @Test fun profileAndMedia() {
        assertEquals(VoiceCommand.ProfileInApp("اینستاگرام", "arash"),
            CommandParser.parse("برو اینستاگرام و پروفایل arash رو باز کن"))
        assertEquals(VoiceCommand.ProfileInApp("اینستاگرام", null),
            CommandParser.parse("برو اینستاگرام و پروفایل باز کن"))
        assertEquals(VoiceCommand.MediaInApp("اسپاتیفای", MediaAction.PLAY),
            CommandParser.parse("برو اسپاتیفای و موزیک پخش کن"))
        assertEquals(VoiceCommand.Media(MediaAction.NEXT), CommandParser.parse("آهنگ بعدی"))
        assertEquals(VoiceCommand.Media(MediaAction.PLAY), CommandParser.parse("موزیک پلی کن"))
        assertEquals(VoiceCommand.MediaInApp("اسپاتیفای", MediaAction.PLAY),
            CommandParser.parse("برو اسپاتیفای و موزیک پلی کن"))
    }

    @Test fun spokenCommandsWithoutConjunctionStayTwoStep() {
        val expected = VoiceCommand.FindInApp("اینستاگرام", "ardalan")
        assertEquals(expected, CommandParser.parse("اینستاگرام جستجو ardalan"))
        assertEquals(expected, CommandParser.parse("باز کن اینستاگرام جستجو ardalan"))
        assertEquals(expected, CommandParser.parse("برو اینستاگرام جستجو ardalan"))
        assertEquals(expected, CommandParser.parse("برو تو اینستاگرام جستجو ardalan"))
        assertEquals(expected, CommandParser.parse("برو اینستاگرام و جستجو ardalan"))
        assertEquals(VoiceCommand.ProfileInApp("اینستاگرام", "ardalan"),
            CommandParser.parse("اینستاگرام پروفایل ardalan رو باز کن"))
        assertEquals(VoiceCommand.ProfileInApp("اینستاگرام", "ardalan"),
            CommandParser.parse("برو اینستاگرام و پروفایل ardalan را جستجو کن"))
        assertEquals(VoiceCommand.OpenApp("اینستاگرام"), CommandParser.parse("باز کن اینستاگرام"))
        assertEquals(VoiceCommand.Search("ardalan"), CommandParser.parse("جستجو کن ardalan"))
    }

    @Test fun safeDownloadAndUnknownTasks() {
        assertEquals(VoiceCommand.Download("https://example.org/music.mp3"),
            CommandParser.parse("دانلود کن https://example.org/music.mp3"))
        assertEquals(VoiceCommand.DownloadPrompt,
            CommandParser.parse("دانلود کن http://example.org/file.apk"))
        assertEquals(VoiceCommand.DownloadPrompt, CommandParser.parse("دانلود کن"))
        assertNull(CommandParser.parse("برو فروشگاه و پرداخت کن"))
    }
}
