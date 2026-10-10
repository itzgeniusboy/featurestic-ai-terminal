# Memory — Featurestic AI Terminal

> Managed by opencode. This is your project's memory — edit it freely.

## Project
- **Goal:** Rebrand TX_Dost → "Featurestic AI Terminal". Remove all local/GGUF on-device models. Replace cloud layer with free OpenAI-compatible providers (free-model-only lists) + keep Ollama Cloud. Build APK via GitHub Actions.
- **Last worked:** 2026-10-11
- **Repo:** local git at `~/opencode/featurestic-ai-terminal` (local user itzgeniusboy). GitHub remote TBD.

## Progress Log
- 2026-10-11: Set up repo copy + `git init`. Rebranded strings.xml, settings.gradle.kts, WebSearchManager UA, CrashHandler header.
- 2026-10-11: Build fixes — Gradle 8.13, root clean task, app/build.gradle kotlin block, removed MediaPipe dep.
- 2026-10-11: Created `Provider.kt` (7-provider registry + FreeFilter + ModelInfo) and `LlmClient.kt` (OpenAI SSE + Ollama NDJSON + proxy + free-filter). Deleted OllamaApi, all GGUF/Local model files + LocalApiServer + models_catalog.json. Rewrote `AppPreferences.kt` (per-provider keys/models, datastore "featurestic_settings").
- 2026-10-11: Rewired `ChatViewModel.kt` (llm provider-aware collection, proxy/temperature collectors, removed aiMode/local/GGUF, rebrand). Removed local/GGUF nav + rebranded `MainActivity.kt`, `ChatScreen.kt`, `TxDostService.kt`. Rewrote `SettingsScreen.kt` (provider picker, per-provider key, refresh free models, model dropdown, Termux proxy, kept web search + temperature). Full repo grep clean of removed symbols.

## Decisions
- 7 providers: OpenRouter, Groq, Gemini, Mistral, NVIDIA, OpenCode Zen (free ids), Ollama Cloud.
- All providers show FREE models only, fetched live; providers without a free flag show all chat models.
- Separate API key + model per provider.
- Models proxy: direct by default, optional Termux proxy toggle (base URL + X-Provider-Base header).
- Tool calling: KEEP existing tag-based action system ([TERMUX:], [INSTALL:], <<<WRITE:>>>, [BG:], [STOP:], [WEB_SEARCH:]) as the automatic agentic layer — no new function-calling loop.
- Keep package `com.txdost` and class `TxDostService`.
- Cannot compile locally (no Android SDK) → GitHub Actions builds the APK.

- 2026-10-11: Regenerated launcher icons (drawable PNG 512 + mipmap webp all dpis) from the SUN source jpg with pillow 12.3. Added `.gitignore`.
- 2026-10-11: Added `.github/workflows/android-build.yml` (JDK 17, preinstalled runner SDK — do NOT add android-actions/setup-android, its default `tools` package fails). Created public repo `itzgeniusboy/featurestic-ai-terminal`, pushed, CI build SUCCESS (run 38080301678). APK copied to `/storage/emulated/0/Download/Featurestic-AI-Terminal-debug.apk`.
- 2026-10-11: Verified built APK — label "Featurestic AI Terminal", new icon embedded, zero `TX_Dost`/`TX_DOST` display strings (only `com.txdost` package/`TxDostService` kept by design).

## Next Steps
- Provide docs/README for the Termux proxy server (script not in repo; app expects passthrough at 127.0.0.1:8080/v1 with `X-Provider-Base`).
- Optionally test each provider's live free-model fetch on-device.
- Optional: bump versionCode/versionName for release; add signed release build.

## Open Questions
- Termux proxy server implementation (X-Provider-Base passthrough) — user must supply.
- Whether to rename package `com.txdost` / class `TxDostService` (currently kept intentionally).
