# API keys

You use your own keys; nothing is bundled. Keys are encrypted with the Android Keystore and sent only to the provider you select.

**Gemini**: create a key in Google AI Studio, open *AI Model Settings → Google Gemini*, paste, *Save key*, then *Discover models* and pick one (default id: `gemini-2.5-flash`; change it if Google retires it).

**OpenRouter**: create a key at openrouter.ai, paste it under *OpenRouter*, tap *Discover models*, toggle *Free only* if you want free models, pick one. Add comma-separated fallback model ids. Free models can be rate limited or removed without notice.

Use *Send test request* to confirm. If the first provider fails the gateway retries, then falls back (if enabled). Prompts and tool output the agent includes are sent to the provider: don't point the agent at data you don't want to share with it.
