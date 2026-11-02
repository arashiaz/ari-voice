package com.arashiaz.arivoice

import org.junit.Assert.*
import org.junit.Test

class WorkflowHistoryTest {
    @Test fun appOpeningIsNotSearchSuccess() {
        val history = WorkflowHistory()
        history.begin(1, workflowTitle(VoiceCommand.FindInApp("اینستاگرام", "ardalan")))
        assertTrue(history.advance(1, WorkflowHistory.Stage.APP_OPENED, "برنامه باز شد"))
        assertFalse(history.current!!.finished)
        assertEquals(WorkflowHistory.Stage.APP_OPENED, history.current!!.steps.last().stage)
        assertTrue(history.advance(1, WorkflowHistory.Stage.SUBMITTED, "درخواست جستجو ارسال شد", true))
        assertTrue(history.current!!.finished)
        assertFalse(history.advance(1, WorkflowHistory.Stage.APP_OPENED, "تکرار قدیمی"))
        assertFalse(history.current!!.title.contains("ardalan"))
    }

    @Test fun newCommandCancelsOldAndRejectsLateCallbacks() {
        val history = WorkflowHistory()
        history.begin(1, "جستجو")
        history.begin(2, "فرمان جدید")
        assertEquals(WorkflowHistory.Stage.CANCELLED, history.recent[1].steps.last().stage)
        assertFalse(history.advance(1, WorkflowHistory.Stage.SUBMITTED, "نتیجهٔ دیرهنگام", true))
        assertTrue(history.cancel(2))
        assertFalse(history.advance(2, WorkflowHistory.Stage.SUBMITTED, "بعد از لغو", true))
    }

    @Test fun cannotClaimSearchBeforeTheTargetAppWasOpened() {
        val history = WorkflowHistory()
        history.begin(7, "جستجوی درون‌برنامه‌ای")
        assertFalse(history.advance(7, WorkflowHistory.Stage.SUBMITTED, "ارسال شد", true))
        assertFalse(history.advance(7, WorkflowHistory.Stage.APP_OPENED, "باز شد", true))
        assertTrue(history.advance(7, WorkflowHistory.Stage.APP_OPENED, "باز شد"))
        assertTrue(history.advance(7, WorkflowHistory.Stage.SEARCH_OPENED, "دکمهٔ جستجو باز شد"))
        assertTrue(history.advance(7, WorkflowHistory.Stage.ENTERED, "متن نوشته شد", true))
        assertFalse(history.advance(7, WorkflowHistory.Stage.SUBMITTED, "ارسال نامطمئن", true))
    }

    @Test fun historyIsBoundedAndContainsNoRawCommandPayload() {
        val history = WorkflowHistory(2)
        history.begin(1, "اول")
        history.begin(2, "دوم")
        history.begin(3, "سوم")
        assertEquals(listOf("سوم", "دوم"), history.recent.map { it.title })
        assertEquals("نوشتن در برنامه", workflowTitle(VoiceCommand.Type("رمز خصوصی")))
        assertEquals("دانلود با تأیید", workflowTitle(VoiceCommand.Download("https://example.org/secret")))
        history.clear()
        assertTrue(history.recent.isEmpty())
    }
}
