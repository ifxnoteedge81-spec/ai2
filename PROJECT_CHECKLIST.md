# Project checklist (v1)

Legend: ✅ done · 🧪 compiled+unit-tested in authoring session · ✍ written, not yet compiled · ⏳ planned

## Modules
- 🧪 CommandGuard, Redactor, SensitiveFilter, SensitiveApps, Workspace sandbox, TarExtractor, ContextTrim, NetGuard, HtmlText (14 JUnit tests passing locally)
- ✍ AI gateway, Gemini + OpenRouter providers (streaming, retry, fallback), Keystore key store, usage counters
- ✍ Agent engine, tool registry/executor, approvals, task states, pause/resume/cancel, memory, history, foreground service
- ✍ Tools: fs_*, shell_run, web_search/web_fetch/download_file, browser_*, device_*, memory_*, runtime_info, wait
- ✍ Runtime manager (native sandbox; PRoot+Alpine install flow), shell executor
- ✍ BrowserController (WebView + JS), HandsService/HandsController (Accessibility)
- ✍ Resource monitor/policy/UI; screens: Home, Agent, Terminal, Browser, Files, Memory, History, AI Settings, Resources, Permissions, About
- ✍ GitHub Actions workflow (YAML parsed; version.json step simulated locally), netlify.toml, download site
- ⏳ Scheduler/WorkManager, Virtual Desktop, App Manager/APK install, skills (job/research/YouTube/files), screenshots/vision/OCR, copy-paste/drag gestures, Room

## Known limitations
See README "Honest limits".

## Build/test log
- Gradle/Android build: NOT RUN (sandbox blocks Google/Maven). First run = GitHub Actions.
- kotlinc 2.0.21 compile of pure sources: OK. JUnit (SecurityTest, RuntimeTest minus manifest-parse test, AgentTest minus provider/WebView tests): 14/14 pass.
- Device tests: none.
