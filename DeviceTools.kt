package com.personal.aicomputer.tools

import com.personal.aicomputer.accessibility.HandsController
import com.personal.aicomputer.agent.SimpleTool
import com.personal.aicomputer.agent.Tool
import com.personal.aicomputer.agent.ToolResult
import com.personal.aicomputer.agent.boolOr
import com.personal.aicomputer.agent.intOr
import com.personal.aicomputer.agent.ok
import com.personal.aicomputer.agent.requireStr
import com.personal.aicomputer.agent.str
import com.personal.aicomputer.security.Decision
import com.personal.aicomputer.security.Risk
import org.json.JSONObject

/** Virtual Hands and Feet: tools that operate other Android apps through the Accessibility service. */
class DeviceTools(private val hands: HandsController) {
    fun all(): List<Tool> = listOf(status(), observe(), tap(), longPress(), swipe(), scroll(), type(), key(), launch())

    /** If Virtual Hands is not enabled nothing can happen, so no approval is needed to learn that. */
    private fun gate(): (JSONObject) -> Decision = {
        if (hands.isEnabled()) Decision(Risk.CONFIRM, "lets the agent see and operate other apps on your phone", "device_control") else Decision(Risk.SAFE)
    }

    private fun res(o: HandsController.Outcome): ToolResult = ToolResult(o.ok, o.text)

    private fun status() = SimpleTool("device_status", "Check whether Android device control (Virtual Hands) is enabled and which app is in front.", "(none)") { _, _ -> ok(hands.status()) }

    private fun observe() = SimpleTool(
        "device_observe", "Read the current screen of the foreground Android app as a numbered list of text and controls. Always do this before and after acting.", "(none)",
        classifier = gate(),
    ) { _, _ -> res(hands.observe()) }

    private fun tap() = SimpleTool(
        "device_tap", "Tap on the Android screen. Provide ONE of: index (node number from device_observe), text (visible label), or x and y. count=2 double-taps.",
        "index (int) | text (string) | x,y (int), count (1|2)",
        classifier = gate(),
    ) { a, _ ->
        val count = a.intOr("count", 1)
        res(
            when {
                a.has("index") -> hands.tapNode(a.getInt("index"))
                a.str("text") != null -> hands.tapText(a.requireStr("text"))
                a.has("x") && a.has("y") -> hands.tapXY(a.getInt("x"), a.getInt("y"), count)
                else -> HandsController.Outcome(false, "Provide index, text, or x and y.")
            },
        )
    }

    private fun longPress() = SimpleTool(
        "device_long_press", "Long-press on the Android screen by node index or x,y.", "index (int) | x,y (int)",
        classifier = gate(),
    ) { a, _ ->
        res(
            when {
                a.has("index") -> hands.tapNode(a.getInt("index"), longPress = true)
                a.has("x") && a.has("y") -> hands.tapXY(a.getInt("x"), a.getInt("y"), 1, 700)
                else -> HandsController.Outcome(false, "Provide index, or x and y.")
            },
        )
    }

    private fun swipe() = SimpleTool(
        "device_swipe", "Swipe/drag from (x1,y1) to (x2,y2) in pixels.", "x1,y1,x2,y2 (int), duration_ms (int, default 350)",
        classifier = gate(),
    ) { a, _ -> res(hands.swipe(a.getInt("x1"), a.getInt("y1"), a.getInt("x2"), a.getInt("y2"), a.intOr("duration_ms", 350).toLong())) }

    private fun scroll() = SimpleTool(
        "device_scroll", "Scroll the foreground app's scrollable view.", "direction (down|up)",
        classifier = gate(),
    ) { a, _ -> res(hands.scroll((a.str("direction") ?: "down").lowercase() != "up")) }

    private fun type() = SimpleTool(
        "device_type", "Type text into a text field (given node index, or the focused/first field). Never for passwords or codes (blocked).",
        "text (string), index (int, optional), append (bool, default false)",
        classifier = gate(),
    ) { a, _ -> res(hands.typeText(a.str("text").orEmpty(), if (a.has("index")) a.getInt("index") else null, a.boolOr("append", false))) }

    private fun key() = SimpleTool(
        "device_key", "Press a system navigation key.", "key (back|home|recents)",
        classifier = gate(),
    ) { a, _ -> res(hands.globalKey(a.requireStr("key"))) }

    private fun launch() = SimpleTool(
        "device_launch_app", "Launch an installed app by name or package id (protected apps are refused).", "app (string)",
        classifier = gate(),
    ) { a, _ -> res(hands.launchApp(a.requireStr("app"))) }
}
