package com.personal.aicomputer.security

/**
 * Static validation of shell commands before they run (spec: "Never execute arbitrary AI-generated
 * commands without security checks").
 *
 * This is defence in depth, not a security boundary: the real boundary is the Android app sandbox
 * (and PRoot when installed). A regex guard can never be complete, so unknown commands default to
 * running inside the workspace sandbox while anything destructive or networked asks the user first.
 */
object CommandGuard {
    private data class Rule(val regex: Regex, val reason: String)

    private val sepStart = "(?:^|[;&|(`\\n]\\s*|\\$\\(\\s*)"

    private val restricted = listOf(
        Rule(Regex("(?i)\\brm\\s+(?:-\\S+\\s+)*(?:/|/\\*|~|~/\\*|\\\$HOME|\\.\\.|\\*)(?:\\s|$)"), "recursive delete of a root, home or wildcard path"),
        Rule(Regex(":\\s*\\(\\s*\\)\\s*\\{"), "fork bomb"),
        Rule(Regex("(?i)\\bmkfs(\\.\\w+)?\\b"), "formatting a filesystem"),
        Rule(Regex("(?i)\\bdd\\b[^\\n]*\\bof=/dev/"), "raw write to a device"),
        Rule(Regex("(?i)>\\s*/dev/(?!null\\b|zero\\b|stdout\\b|stderr\\b|tty\\b)"), "writing to a device node"),
        Rule(Regex("(?i)$sepStart(su|sudo|doas|setenforce|mount|umount|insmod|rmmod|chroot)\\b"), "privilege escalation or system-level command"),
        Rule(Regex("/data/(data|user|user_de|system|misc|app)/(?!com\\.personal\\.aicomputer)"), "access to other apps' or system private data"),
        Rule(Regex("/sdcard/Android/(data|obb)"), "access to other apps' storage"),
        Rule(Regex("(?i)(curl|wget|fetch)\\b[^\\n]*\\|\\s*(sudo\\s+)?(sh|bash|zsh|ash|dash|python\\d*|node|perl)\\b"), "piping a download straight into an interpreter"),
        Rule(Regex("(?i)base64\\s+(-d|--decode)[^\\n]*\\|\\s*(sh|bash|ash)\\b"), "obfuscated command execution"),
        Rule(Regex("(?i)\\b(nc|ncat|netcat|socat)\\b[^\\n]*\\s-(e|c)\\b|/dev/tcp/"), "reverse-shell style network command"),
        Rule(
            Regex("(?i)$sepStart(pm|am|cmd|settings|svc|input|screencap|screenrecord|dumpsys|content|wm|appops|run-as|logcat|service|setprop|app_process)\\b"),
            "Android system tools are not allowed in the shell; use the device_* tools (they require your approval)",
        ),
    )

    private val confirm = listOf(
        Rule(Regex("(?i)$sepStart(rm|rmdir|unlink|shred|truncate)\\b"), "deletes files"),
        Rule(Regex("(?i)$sepStart(mv|cp\\s+-f|chmod|chown|chgrp|ln\\s+-sf)\\b"), "moves, overwrites or changes permissions"),
        Rule(Regex("(?i)$sepStart(kill|killall|pkill)\\b"), "terminates processes"),
        Rule(Regex("(?i)\\b(curl|wget|git\\s+clone|ftp|scp|rsync)\\b"), "uses the network to download or upload"),
        Rule(Regex("(?i)\\b(pip3?|pipx|npm|npx|yarn|pnpm|gem|cargo|go)\\s+(install|i|add|get)\\b"), "installs packages from the internet"),
        Rule(Regex("(?i)\\b(apk|apt|apt-get|dnf|yum|pkg)\\s+(add|del|install|remove|upgrade|update)\\b"), "installs or removes system packages"),
        Rule(Regex("\\.\\./"), "uses path traversal"),
        Rule(Regex("(?i)\\bnohup\\b|&\\s*$|\\bhttp\\.server\\b|\\bnc\\s+-l"), "starts a long-running or background process"),
        Rule(Regex("(?i)(>|>>)\\s*/(?!dev/null)"), "writes to an absolute path"),
        Rule(Regex("(?i)\\b(eval|exec)\\b"), "dynamic command execution"),
    )

    const val MAX_LENGTH = 4000

    fun classify(command: String): Decision {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Decision(Risk.RESTRICTED, "empty command", "shell")
        if (cmd.length > MAX_LENGTH) return Decision(Risk.CONFIRM, "unusually long command (${cmd.length} chars)", "shell")
        for (r in restricted) {
            if (r.regex.containsMatchIn(cmd)) return Decision(Risk.RESTRICTED, r.reason, "shell")
        }
        for (r in confirm) {
            if (r.regex.containsMatchIn(cmd)) return Decision(Risk.CONFIRM, r.reason, "shell")
        }
        return Decision(Risk.SAFE, "", "shell")
    }
}
