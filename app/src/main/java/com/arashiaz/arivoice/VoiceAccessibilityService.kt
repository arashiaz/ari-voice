package com.arashiaz.arivoice

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

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

    fun execute(command: VoiceCommand): Boolean = when (command) {
        is VoiceCommand.Tap -> withRoot { root ->
            val matches = mutableListOf<AccessibilityNodeInfo>()
            collectMatches(root, CommandParser.normalize(command.label), matches)
            // Never guess when multiple controls with the same label are visible.
            if (matches.size != 1) false else {
                var node: AccessibilityNodeInfo? = matches.single()
                var clicked = false
                while (node != null && !clicked) {
                    if (node.isClickable && node.isVisibleToUser && !node.isPassword) {
                        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    node = node.parent
                }
                clicked
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
