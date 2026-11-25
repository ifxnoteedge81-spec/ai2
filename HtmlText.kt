package com.personal.aicomputer.tools

/** Small HTML-to-text converter for web_fetch (no external dependency). */
object HtmlText {
    private val drop = Regex("(?is)<(script|style|noscript|svg|head|template)\\b.*?</\\1>")
    private val comments = Regex("(?s)<!--.*?-->")
    private val blockTags = Regex("(?i)</?(p|div|br|li|ul|ol|tr|table|h[1-6]|section|article|header|footer|nav|blockquote|pre|form)\\b[^>]*>")
    private val anyTag = Regex("(?s)<[^>]*>")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val blankLines = Regex("\\n\\s*\\n\\s*\\n+")
    private val title = Regex("(?is)<title[^>]*>(.*?)</title>")

    fun title(html: String): String = title.find(html)?.groupValues?.get(1)?.let { decode(it).trim().replace(spaces, " ") }.orEmpty()

    fun toText(html: String): String {
        var s = comments.replace(html, "")
        s = drop.replace(s, " ")
        s = blockTags.replace(s, "\n")
        s = anyTag.replace(s, "")
        s = decode(s)
        s = s.lines().joinToString("\n") { it.replace(spaces, " ").trim() }
        s = blankLines.replace(s, "\n\n")
        return s.trim()
    }

    fun decode(s: String): String {
        var out = s
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&ndash;", "–").replace("&mdash;", "—")
        out = Regex("&#(\\d{1,6});").replace(out) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it.coerceIn(0, 0x10FFFF))) } ?: m.value }
        out = Regex("&#x([0-9a-fA-F]{1,6});").replace(out) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it.coerceIn(0, 0x10FFFF))) } ?: m.value }
        return out
    }
}
