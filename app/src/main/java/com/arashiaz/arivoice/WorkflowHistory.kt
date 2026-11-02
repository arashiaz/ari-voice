package com.arashiaz.arivoice

/** Session-only, privacy-preserving action history. Never stores dictated text or screen contents. */
internal class WorkflowHistory(private val capacity: Int = 12) {
    enum class Stage { RECOGNIZED, WAITING, APP_OPENED, SEARCH_OPENED, ENTERED, SUBMITTED, HANDOFF, STOPPED, CANCELLED }
    data class Step(val stage: Stage, val description: String)
    data class Entry(val id: Int, val title: String, val steps: List<Step>, val finished: Boolean)

    private val entries = ArrayDeque<Entry>()
    val recent: List<Entry> get() = entries.toList().asReversed()
    val current: Entry? get() = entries.lastOrNull()

    fun begin(id: Int, title: String) {
        current?.takeIf { !it.finished }?.let { replace(it.copy(
            steps = it.steps + Step(Stage.CANCELLED, "فرمان جدید جایگزین شد"), finished = true
        )) }
        entries.addLast(Entry(id, title, listOf(Step(Stage.RECOGNIZED, "فرمان شناسایی شد")), false))
        while (entries.size > capacity) entries.removeFirst()
    }

    fun advance(id: Int, stage: Stage, description: String, terminal: Boolean = false): Boolean {
        val entry = current ?: return false
        if (entry.id != id || entry.finished || !validNext(entry.steps.last().stage, stage)) return false
        val isTerminal = stage in setOf(Stage.ENTERED, Stage.SUBMITTED, Stage.HANDOFF, Stage.STOPPED, Stage.CANCELLED)
        if (terminal != isTerminal) return false
        replace(entry.copy(steps = entry.steps + Step(stage, description), finished = terminal))
        return true
    }

    private fun validNext(previous: Stage, next: Stage): Boolean = when (previous) {
        Stage.RECOGNIZED, Stage.WAITING -> next in setOf(Stage.WAITING, Stage.APP_OPENED, Stage.HANDOFF, Stage.STOPPED, Stage.CANCELLED)
        Stage.APP_OPENED -> next in setOf(Stage.SEARCH_OPENED, Stage.ENTERED, Stage.SUBMITTED, Stage.HANDOFF, Stage.STOPPED, Stage.CANCELLED)
        Stage.SEARCH_OPENED -> next in setOf(Stage.SEARCH_OPENED, Stage.ENTERED, Stage.SUBMITTED, Stage.STOPPED, Stage.CANCELLED)
        Stage.ENTERED, Stage.SUBMITTED, Stage.HANDOFF, Stage.STOPPED, Stage.CANCELLED -> false
    }

    fun cancel(id: Int): Boolean = advance(id, Stage.CANCELLED, "با درخواست شما متوقف شد", true)
    fun clear() = entries.clear()

    private fun replace(entry: Entry) {
        entries.removeLast()
        entries.addLast(entry)
    }
}

/** No raw search terms, dictated text, URLs or usernames are retained in the activity list. */
internal fun workflowTitle(command: VoiceCommand): String = when (command) {
    is VoiceCommand.FindInApp -> "جستجوی درون‌برنامه‌ای"
    is VoiceCommand.ProfileInApp -> "جستجوی پروفایل"
    is VoiceCommand.OpenApp -> "بازکردن برنامه"
    is VoiceCommand.Search -> "جستجوی وب"
    is VoiceCommand.Media, is VoiceCommand.MediaInApp -> "کنترل رسانه"
    is VoiceCommand.Download, VoiceCommand.DownloadPrompt -> "دانلود با تأیید"
    is VoiceCommand.Type -> "نوشتن در برنامه"
    is VoiceCommand.Tap -> "لمس کنترل"
    is VoiceCommand.Scroll -> "پیمایش صفحه"
    VoiceCommand.Back, VoiceCommand.Home, VoiceCommand.Notifications -> "کنترل گوشی"
}
