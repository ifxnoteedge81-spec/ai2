package com.personal.aicomputer

import com.personal.aicomputer.agent.AgentAction
import com.personal.aicomputer.agent.AgentProtocol
import com.personal.aicomputer.ai.ChatMessage
import com.personal.aicomputer.ai.ContextTrim
import com.personal.aicomputer.ai.Role
import com.personal.aicomputer.browser.BrowserController
import com.personal.aicomputer.tools.HtmlText
import com.personal.aicomputer.tools.NetGuard
import com.personal.aicomputer.tools.WebClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AgentTest {
    @Test fun parsesToolCallInsideFencesAndProse() {
        val a = AgentProtocol.parse("Sure!\n```json\n{\"thought\":\"look\",\"tool\":\"fs_list\",\"args\":{\"path\":\".\"},\"plan\":[\"a\",\"b\"]}\n```") as AgentAction.Call
        assertEquals("fs_list", a.tool)
        assertEquals(".", a.args.getString("path"))
        assertEquals(listOf("a", "b"), a.plan)
    }

    @Test fun parsesFinal() {
        val a = AgentProtocol.parse("{\"final\":\"done {ok}\",\"success\":true,\"evidence\":[1,2]}") as AgentAction.Final
        assertEquals("done {ok}", a.report)
        assertEquals(listOf(1, 2), a.evidence)
        assertFalse(a.answerOnly)
    }

    @Test fun rejectsGarbage() {
        assertNull(AgentProtocol.parse("no json here"))
        assertNull(AgentProtocol.parse("{\"hello\":1}"))
        assertNull(AgentProtocol.extractJsonText("{ unbalanced"))
    }

    @Test fun trimKeepsGoalAndLatest() {
        val msgs = listOf(ChatMessage(Role.SYSTEM, "sys")) + (0 until 20).map { ChatMessage(if (it % 2 == 0) Role.USER else Role.ASSISTANT, "m$it " + "x".repeat(500)) }
        val out = ContextTrim.trimToBudget(msgs, 3000)
        assertTrue(out.sumOf { it.content.length } <= 3200)
        assertEquals("sys", out.first().content)
        assertTrue(out[1].content.startsWith("m0"))
        assertTrue(out.last().content.startsWith("m19"))
    }

    @Test fun netGuardBlocksPrivateAddresses() {
        listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fd00::1").forEach {
            assertFalse(it, NetGuard.isPublicAddress(InetAddress.getByName(it)))
        }
        assertTrue(NetGuard.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertNotNull(NetGuard.checkUrl("file:///etc/passwd"))
        assertNotNull(NetGuard.checkUrl("http://localhost:8080/"))
        assertNotNull(NetGuard.checkUrl("http://127.0.0.1/"))
    }

    @Test fun htmlToText() {
        val t = HtmlText.toText("<html><head><title>T</title><style>p{}</style></head><body><h1>Hi &amp; bye</h1><script>x()</script><p>One</p><p>Two</p></body></html>")
        assertEquals("Hi & bye\nOne\nTwo", t.replace(Regex("\n+"), "\n"))
        assertEquals("T", HtmlText.title("<title> T </title>"))
    }

    @Test fun parsesDuckDuckGoResults() {
        val html = """<a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=x">Example <b>A</b></a>
            <a class="result__snippet" href="x">A snippet</a>"""
        val hits = WebClient.parseDuckDuckGo(html)
        assertEquals(1, hits.size)
        assertEquals("https://example.com/a", hits[0].url)
        assertEquals("Example A", hits[0].title)
    }

    @Test fun decodesJsResults() {
        assertEquals("hello \"x\"\nline", BrowserController.decodeJsResult("\"hello \\\"x\\\"\\nline\""))
        assertEquals("", BrowserController.decodeJsResult("null"))
        assertEquals("5", BrowserController.decodeJsResult("5"))
    }
}
