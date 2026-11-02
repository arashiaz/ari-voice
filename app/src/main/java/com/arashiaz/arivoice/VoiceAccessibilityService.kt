package com.arashiaz.arivoice

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.os.Build

/** Only used after the user explicitly enables the service in system settings. */
class VoiceAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var active: VoiceAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() { active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    enum class SearchStep { WAIT_FOR_APP, OPENED_SEARCH, WAIT_FOR_FIELD, ENTERED_QUERY, SUBMITTED, AMBIGUOUS, NO_SEARCH_UI }

    /** Search in an explicitly selected app. Never click a product, purchase, checkout or payment control.
     * The mutable set belongs to ONE user command: at most two distinct, named search controls can be clicked. */
    fun searchInApp(targetPackage: String, query: String, clickedSearchControls: MutableSet<String>): SearchStep {
        val root = rootInActiveWindow ?: return SearchStep.WAIT_FOR_APP
        if (root.packageName?.toString() != targetPackage) return SearchStep.WAIT_FOR_APP
        if (query.isBlank() || query.length > 200) return SearchStep.NO_SEARCH_UI
        val fields = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, fields)
        val marked = fields.filter { isSearchField(it) }
        val field = when {
            marked.size == 1 -> marked.single()
            marked.size > 1 -> return SearchStep.AMBIGUOUS
            clickedSearchControls.isNotEmpty() && fields.size == 1 && fields.single().isFocused -> fields.single()
            else -> null
        }
        if (field != null) {
            val success = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            })
            if (!success) return SearchStep.NO_SEARCH_UI
            // Only submit a field explicitly marked as search or revealed by a Search control.
            if (Build.VERSION.SDK_INT >= 30 &&
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return SearchStep.SUBMITTED
            }
            return SearchStep.ENTERED_QUERY
        }
        // Some apps (notably Instagram) have an Explore tab followed by a separate Search bar.
        // Do not click any control without a unique, explicit search/explore label.
        if (clickedSearchControls.size >= 2) return SearchStep.WAIT_FOR_FIELD
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectSearchButtons(root, targetPackage, buttons)
        val fresh = buttons.filter { searchControlKey(it) !in clickedSearchControls }
        if (fresh.size > 1) return SearchStep.AMBIGUOUS
        if (fresh.size == 1 && fresh.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            clickedSearchControls.add(searchControlKey(fresh.single()))
            return SearchStep.OPENED_SEARCH
        }
        return if (clickedSearchControls.isNotEmpty()) SearchStep.WAIT_FOR_FIELD else SearchStep.NO_SEARCH_UI
    }

    private fun searchControlKey(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "${node.viewIdResourceName.orEmpty()}:${bounds.flattenToString()}"
    }

    private fun collectEditable(node: AccessibilityNodeInfo, fields: MutableList<AccessibilityNodeInfo>) {
        if (!node.isVisibleToUser) return
        if (node.isEditable && !node.isPassword) fields.add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { collectEditable(it, fields) }
    }

    private fun isSearchField(node: AccessibilityNodeInfo): Boolean = listOfNotNull(
        node.hintText, node.contentDescription, node.viewIdResourceName
    ).any { CommandParser.normalize(it.toString()).let { hint ->
        hint.contains("search") || hint.contains("جستجو") || hint.contains("جست و جو") || hint.contains("سرچ")
    } }

    private fun collectSearchButtons(node: AccessibilityNodeInfo, targetPackage: String,
                                     buttons: MutableList<AccessibilityNodeInfo>) {
        if (!node.isVisibleToUser) return
        val names = listOfNotNull(node.text, node.contentDescription)
        if (names.any { SearchUiLabels.isSearchEntry(it.toString(), targetPackage) }) {
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            val target = clickable
            if (target != null && target.isVisibleToUser && !target.isPassword &&
                buttons.none { it == target }) buttons.add(target)
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let {
            collectSearchButtons(it, targetPackage, buttons)
        }
    }

    fun execute(command: VoiceCommand): Boolean = when (command) {
        is VoiceCommand.Tap -> withRoot { root ->
            if (SensitiveActionPolicy.isUnsafeClick(command.label)) return@withRoot false
            val matches = mutableListOf<AccessibilityNodeInfo>()
            collectMatches(root, CommandParser.normalize(command.label), matches)
            // Never guess when multiple controls with the same label are visible.
            if (matches.size != 1) false else {
                val selected = matches.single()
                // A harmless-looking child could sit inside a payment or send button.
                var ancestor: AccessibilityNodeInfo? = selected
                var unsafe = false
                while (ancestor != null) {
                    if (ancestor.isPassword || listOfNotNull(ancestor.text, ancestor.contentDescription).any {
                        SensitiveActionPolicy.isUnsafeClick(it.toString())
                    }) unsafe = true
                    ancestor = ancestor.parent
                }
                if (unsafe) false else {
                    var node: AccessibilityNodeInfo? = selected
                    var clicked = false
                    while (node != null && !clicked) {
                        if (node.isClickable && node.isVisibleToUser && !node.isPassword)
                            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node = node.parent
                    }
                    clicked
                }
            }
        }
        is VoiceCommand.Type -> withRoot { root ->
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            // Never overwrite password fields or arbitrary controls. Require a focused edit field.
            if (focused == null || !focused.isEditable || focused.isPassword || !focused.isVisibleToUser) false
            else focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.text)
            })
        }
        is VoiceCommand.Scroll -> withRoot { root ->
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val action = if (command.down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                         else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            // Prefer a scrollable container around the focused control, then a visible container.
            var parent = focused
            var done = false
            while (parent != null && !done) {
                if (parent.isScrollable) done = parent.performAction(action)
                parent = parent.parent
            }
            done || scrollFirst(root, action)
        }
        VoiceCommand.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        VoiceCommand.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        VoiceCommand.Notifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }

    private inline fun withRoot(block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        // Avoid operating on this assistant's own screen when switching apps takes longer than expected.
        if (root.packageName == packageName) return false
        return block(root)
    }

    private fun collectMatches(node: AccessibilityNodeInfo, label: String, result: MutableList<AccessibilityNodeInfo>) {
        if (result.size > 1) return
        if (node.isVisibleToUser && !node.isPassword) {
            val text = node.text?.toString()?.let(CommandParser::normalize)
            val description = node.contentDescription?.toString()?.let(CommandParser::normalize)
            if (text == label || description == label) result.add(node)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectMatches(it, label, result) }
    }

    private fun scrollFirst(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollFirst(child, action)) return true
        }
        return false
    }
}
