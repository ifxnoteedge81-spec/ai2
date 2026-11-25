# Security model

| Level | Examples | Behaviour |
|---|---|---|
| 1 Safe | read public pages, create files in workspace, search, `ls`/`cat` | automatic |
| 2 Confirm | overwrite/delete files, `curl`/`pip`/`apk` in shell, submit/send/buy clicks, any device control, start under memory pressure | dialog: allow once / allow this type for this task / deny |
| 3 Restricted | Android system shell tools (`pm`,`am`,`input`,`settings`…), `su`, other apps' private data paths, pipe-to-shell, password/OTP/card/CAPTCHA fields, banking/wallet/password-manager/system-settings/installer/own-app screens, localhost/LAN requests | refused, the model is told |

Controls: canonical-path workspace sandbox; `CommandGuard` (regex, defence in depth, not a boundary: the Android app sandbox is);
SSRF guard on every HTTP hop; tool output treated as untrusted (prompt-injection mitigation); keys redacted from logs/UI;
memory rejects secrets; `allowBackup=false`; cleartext HTTP disabled; WebView file access off, non-http(s) navigation blocked.

Residual risks: a model can still be tricked into harmful-but-permitted actions inside Level 1/approved categories; "allow for this task"
widens that. Accessibility can see everything on screen in non-protected apps while a task runs: only enable it if you accept that.
