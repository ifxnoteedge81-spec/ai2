# Architecture

```
UI (Compose, Material 3)  ──►  AgentController (queue: 1 task at a time, pause/resume/cancel, foreground service)
                                   │
                              AgentEngine  ── plan → tool → permission → execute → observe → verify → report
                                   │                 │                │
                              AiGateway          ToolRegistry     ApprovalCenter (Level 2 dialog)
                       (Gemini | OpenRouter,           │
                        retry, fallback, trim)   ┌─────┴──────────────────────────────┐
                                                 │ FileTools  ShellTools  WebTools     │
                                                 │ BrowserTools  DeviceTools  Memory   │
                                                 └───┬──────────┬──────────┬──────────┘
                                          Workspace  │          │          └ HandsController → HandsService (Accessibility)
                                  ShellExecutor ◄ RuntimeManager (native sh | PRoot Alpine)    BrowserController (WebView + JS)
```

## Two execution environments (kept separate on purpose)
- **Internal virtual environment**: `Workspace` (private folder), `ShellExecutor`, `RuntimeManager`, `BrowserController`. Everything is confined to `filesDir/pac/workspace`; paths are canonicalised so symlinks cannot escape.
- **Android device automation**: `HandsController` via an AccessibilityService the user enables manually. Separate approval category (`device_control`), protected-app blocklist (`SensitiveApps`).

## Agent loop (AgentEngine)
Each turn the model returns ONE JSON object: a tool call or a final report. The engine classifies the call
(`SAFE`/`CONFIRM`/`RESTRICTED`), asks the user when needed, runs it with a timeout, records a `StepRecord`
(what really happened) and feeds back the observation (marked as untrusted data). A `final` with `success:true`
is only **verified** if it cites step numbers of successful tool steps (or is flagged `answer_only`); otherwise the
engine pushes back twice and finally labels the report "NOT VERIFIED". The UI distinguishes executed actions from AI text.

## Task states
PENDING → PLANNING → RUNNING ⇄ WAITING_PERMISSION / PAUSED → COMPLETED | FAILED | CANCELLED. Tasks interrupted by an app restart are marked FAILED.

## AI gateway
Order: selected model → OpenRouter fallback models → other provider (if keyed and enabled). Retryable errors (429/5xx/network) back off 2 times; auth and bad-request errors move on immediately. Context is trimmed to a budget derived from the resource policy. Streaming (plain chat) uses SSE for both providers.

## Persistence
Local JSON files (`memory/tasks.json`, `memory/memory.json`) and SharedPreferences; API keys are AES-GCM encrypted with an Android Keystore key. Room is a planned migration, not used in v1.

## Resource policy
`ResourcePolicy` maps the RAM budget + mode + live memory pressure to: context chars, tool output cap, max steps, shell `ulimit -v`, concurrency (always 1).
