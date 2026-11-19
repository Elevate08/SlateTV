# Slate TV — Ultra-Minimal Android TV Launcher (`com.stasisstate.slatetv`)

**Slate TV** is a fast, open-source, completely unrestricted Android TV launcher built by Stasis State to eliminate NVIDIA Shield wake-up black screens and sluggish UI—with an open **AIDL Plugin Architecture** supporting installable **Background Plugins** (such as **Immich**) and **Home Shelf / Live TV Guide Plugins** (such as **Jellyfin Live TV**).

Slate TV has zero ads, zero paywalls, and zero locked features. Optional consumable tips (`$1`, `$3`, `$5`, `$10`, `$20`) are available in Settings when installed from Google Play and unlock nothing.

---

## Architecture & Installable Modules

| Module | Application ID | Purpose | Release APK |
| :--- | :--- | :--- | :---: |
| **`:app`** | `com.stasisstate.slatetv` | Ultra-minimal Android TV launcher host (Ambient Slate + Local Photos built-in; discovers external Background & Shelf plugin APKs) | ~1.3 MB |
| **`:plugin-api`** | `com.stasisstate.slatetv.plugin.api` | Versioned AIDL contracts (`ISlateBackgroundPlugin`, `ISlateShelfPlugin`) | Library |
| **`:plugin-immich`** | `com.stasisstate.slatetv.plugin.immich` | Standalone installable **Immich Photos** background plugin APK (header `x-api-key` auth, QR phone pairing, albums, favorites, random, recent) | ~288 KB |
| **`:plugin-jellyfin`** | `com.stasisstate.slatetv.plugin.jellyfin` | Standalone installable **Jellyfin Live TV & Guide** shelf plugin APK (displays On Now Live TV on the main screen when available + 10-foot EPG guide) | ~841 KB |
| **`:plugin-sample-external`** | `com.stasisstate.slatetv.plugin.sample` | Minimal reference external background plugin APK | Sample |

---

## Why Slate TV Eliminates NVIDIA Shield Black Screens & Lag

1. **No Accessibility Service Polling:** Registers cleanly as a standard Android TV `HOME` activity (`singleTask`, `stateNotNeeded`) without background accessibility hooks racing the stock launcher.
2. **HDMI-CEC & HDR Wake Hardening:** Declares `configChanges` including `colorMode`, `screenSize`, `smallestScreenSize`, `screenLayout`, `orientation`, `uiMode`, and `density` so HDMI-CEC wakes and SDR/HDR/Dolby Vision handshakes do **not** recreate the Activity.
3. **Zero `SurfaceView` / Video Wallpapers:** Uses hardware-accelerated double-buffered `ImageView` crossfades over an explicit opaque `#0A0D12` window background, with outgoing drawables released cleanly after transitions.
4. **Instant Cache Hydration & Wake Backoff:** Stores the last displayed photo per plugin in an atomic bounded LRU disk cache (`<= 48 MB`, streaming 16 MB cap, panorama pixel ceiling). On wake, Slate TV renders immediately from flash and retries via `onLinkPropertiesChanged` + bounded backoff (`3s → 8s → 20s → 60s`).
5. **Isolated Plugin Boundaries:** Both background and shelf AIDL calls run on bounded dedicated RPC threads with strict 4-second timeouts, circuit-breaker cooldowns, caller UID checks inside plugins, origin-bound redirect header stripping, and strict payload sanitization.

---

## Remote Control Shortcuts

| Key | Action |
| :--- | :--- |
| **D-Pad Left / Right** | Navigate pinned dock apps, Home Shelf cards, and action pills (`Apps`, `Next`, `Settings`) |
| **OK / Center** | Launch app, open Live TV channel/guide, or trigger focused action |
| **Hold OK** or **Menu** | Context menu (Move Left/Right, Pin/Unpin, Hide, App Info) |
| **D-Pad Up** (from Dock) | Focuses the Home Shelf (e.g., Jellyfin Live TV) if visible; pressing **Up** again (or with no shelf) enters distraction-free Gallery Mode |
| **D-Pad Down** (from Shelf) | Returns focus to the bottom app dock |
| **Any Key** (in Gallery Mode) | Restores dock & HUD immediately |
| **Media Next** / **Channel Up** | Skips immediately to the next background photo |

---

## Building from Source

Requires JDK 17 or 21 and Android SDK (API 36). Optional release signing reads untracked `keystore.properties` (matching Stasis State conventions); debug builds need no credentials.

```bash
# Run all unit tests across all modules (36 tests)
./gradlew testDebugUnitTest

# Build all Debug APKs (Launcher + Immich Plugin + Jellyfin Plugin)
./gradlew :app:assembleDebug :plugin-immich:assembleDebug :plugin-jellyfin:assembleDebug

# Build minified Release APKs
./gradlew :app:assembleRelease :plugin-immich:assembleRelease :plugin-jellyfin:assembleRelease
```

---

## Documentation

- [NVIDIA Shield TV Setup, Plugins & Migrating from Projectivy](docs/SHIELD_SETUP.md)
- [Building External Background & Shelf Plugins for Slate TV](docs/PLUGIN_DEVELOPMENT.md)
