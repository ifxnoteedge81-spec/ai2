package com.personal.aicomputer.tools

import com.personal.aicomputer.agent.SimpleTool
import com.personal.aicomputer.agent.Tool
import com.personal.aicomputer.agent.boolOr
import com.personal.aicomputer.agent.fail
import com.personal.aicomputer.agent.intOr
import com.personal.aicomputer.agent.ok
import com.personal.aicomputer.agent.requireStr
import com.personal.aicomputer.agent.str
import com.personal.aicomputer.browser.BrowserController
import com.personal.aicomputer.resources.ResourcePolicy
import com.personal.aicomputer.security.Decision
import com.personal.aicomputer.security.Risk

/** Browser automation tools. The agent drives the same WebView the user sees on the Browser screen. */
class BrowserTools(private val browser: BrowserController, private val policy: ResourcePolicy) {
    fun all(): List<Tool> = listOf(open(), read(), elements(), click(), type(), scroll(), back())

    private val risky = Regex(
        "(?i)\\b(submit|apply|send|buy|pay|purchase|order|checkout|confirm|delete|remove|publish|post|upload|sign ?up|register|subscribe|donate|transfer|book|place order|accept|agree|পাঠান|জমা|কিনুন|অর্ডার)\\b",
    )

    private fun open() = SimpleTool(
        "browser_open", "Open a URL (or search terms) in the in-app browser and wait for it to load.", "url (string)",
        timeoutMs = 50_000,
    ) { a, _ ->
        val r = browser.open(a.requireStr("url"))
        if (r.startsWith("ERROR")) fail(r) else ok(r)
    }

    private fun read() = SimpleTool(
        "browser_read", "Read the visible text of the current page.", "max_chars (int, default 8000)",
    ) { a, _ ->
        ok(browser.readPage(a.intOr("max_chars", 8000).coerceIn(500, policy.outputCapChars())))
    }

    private fun elements() = SimpleTool(
        "browser_elements", "List clickable/typable elements on the current page with numeric ids. Call this before browser_click / browser_type.", "(none)",
    ) { _, _ -> ok(browser.listElements()) }

    private fun click() = SimpleTool(
        "browser_click", "Click an element by its id from browser_elements, a CSS selector, or visible text. Clicks that look like submit/buy/send/delete need approval.",
        "target (string)",
        classifier = { a ->
            val t = a.str("target").orEmpty()
            val label = t.toIntOrNull()?.let { browser.lastLabels[it] } ?: t
            if (risky.containsMatchIn(label)) Decision(Risk.CONFIRM, "click on \"${label.take(60)}\" may submit, send, buy, publish or delete", "browser_submit")
            else Decision(Risk.SAFE)
        },
    ) { a, _ -> ok(browser.click(a.requireStr("target"))) }

    private fun type() = SimpleTool(
        "browser_type", "Type text into an input by id/selector/label. Never use for passwords, OTPs, card numbers or CAPTCHAs (blocked). submit=true presses enter/submits the form (needs approval).",
        "target (string), text (string), submit (bool, default false)",
        classifier = { a ->
            if (a.boolOr("submit", false)) Decision(Risk.CONFIRM, "submits a form on the page", "browser_submit") else Decision(Risk.SAFE)
        },
    ) { a, _ -> ok(browser.type(a.requireStr("target"), a.str("text").orEmpty(), a.boolOr("submit", false))) }

    private fun scroll() = SimpleTool(
        "browser_scroll", "Scroll the page.", "direction (up|down|top|bottom)",
    ) { a, _ -> ok(browser.scroll(a.str("direction") ?: "down")) }

    private fun back() = SimpleTool("browser_back", "Go back to the previous page.", "(none)") { _, _ -> ok(browser.back()) }
}
