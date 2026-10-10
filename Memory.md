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

- 2026-10-11: Added `termux/server.py` (bridge v6.0 — rebranded, keeps all v5.1 endpoints + NEW OpenAI model-proxy `/v1/chat/completions` & `/v1/models` using `X-Provider-Base` header) and `termux/install.sh` (one-line installer: permission + python/curl + download server + ~/.bashrc autostart + start + ping). Verified locally on Termux: /ping, /run, /info, /write, /file, and proxy streaming through a mock provider all pass; missing-header returns 400.

- 2026-10-11: Added in-app `TermuxSetupScreen` (Settings → TERMUX SETUP / CONNECT). Server script bundled as asset `app/src/main/assets/termux_install.sh` (self-contained — server.py embedded, no GitHub needed). Screen: 1-TAP CONNECT button (Termux RUN_COMMAND intent, script sent base64) + manual fallback (download to Downloads, copy `termux-setup-storage` and `bash ~/storage/downloads/featurestic-install.sh`). Added `TermuxBridge.runCommandViaTermux`, WRITE_EXTERNAL_STORAGE (maxSdk 28) in manifest. Base64 pipeline tested locally (19,288 chars decode+syntax OK). User never sees GitHub.

- 2026-10-11: Global Apple/iOS redesign. New theme tokens `ui/theme/AppleTheme.kt` (LightApple/DarkApple, `Apple.colors`, `FeaturesticTheme`), `Type.kt`, `Shape.kt` (deleted old Color/Theme/OllamaAppTheme). Reusable `ui/components/AppleComponents.kt` (AppleScreen, AppleTopBar, AppleButton, AppleCard, AppleGroup, AppleRow, AppleSectionHeader, AppleTextField, AppleBadge, AppleSwitchRow, AppleSegmentedControl, AppleAlertDialog...). Rewrote ChatScreen (iMessage style), SettingsScreen, TermuxSetupScreen, DashboardScreen, FileScreen, FileExplorerScreen, ProcessManagerScreen. `MainActivity` uses FeaturesticTheme.
- 2026-10-11: Fixes — SettingsScreen provider switch could not reassign `val providerId` → now saves via `prefs.saveProviderId`; Dashboard refresh button now calls suspend `fetchStats` inside `scope.launch`. CI-verified: run 38083945565 SUCCESS.
- APK rebuilt + copied to `/storage/emulated/0/Download/Featurestic-AI-Terminal-debug.apk` (20.3 MB).
- 2026-10-11: Bumped versionCode 1→2, versionName 1.0→1.1. CI run 38084421178 SUCCESS. APK copied to `/storage/emulated/0/Download/Featurestic-AI-Terminal-v1.1-debug.apk`.

## Next Steps
- Optional: test each provider's live free-model fetch on-device.
- Optional: bump versionCode/versionName for release; add signed release build.

## Open Questions
- Whether to rename package `com.txdost` / class `TxDostService` (currently kept intentionally).
