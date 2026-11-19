# Slate TV — Ultra-Minimal Android TV Launcher with Immich & Plugin Support

**Slate TV** is a fast, open-source, zero-bloat Android TV launcher engineered specifically to eliminate the black-screen-on-wake and sluggishness issues common on **NVIDIA Shield TV** devices while providing first-class **Immich** photo backdrop integration and an open **AIDL Plugin Architecture**.

---

## Why Slate TV?

| Launcher / App | Minimal & Fast | Open Source | Plugin System | Native Immich (Header Auth) | Zero Accessibility Service |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Slate TV** | Yes | Yes | Yes (Open AIDL) | Yes (Built-in + QR Phone Setup) | Yes |
| Projectivy Launcher | Moderate | No (Premium required for plugins) | Yes (URL query-param workaround) | Community plugin only | No (often relies on Accessibility) |
| Luncher / Just-a-Launcher / BareLauncher | Yes | Yes | No | No | Yes |
| FLauncher | Yes (Flutter) | Yes (unmaintained) | No | No | Yes |
| ImmichFrame / Immich TV | N/A (Screensaver/App) | Yes | N/A | Yes | N/A |

### How Slate TV Eliminates NVIDIA Shield Black Screens & Lag
1. **No Accessibility Service Polling:** Registers cleanly as a standard Android TV `HOME` activity (`singleTask`, `stateNotNeeded`) without background accessibility hooks racing the stock launcher.
2. **HDMI-CEC Wake Hardening:** Declares `configChanges` for `screenSize|smallestScreenSize|screenLayout|orientation|uiMode|density` so HDMI-CEC wake-ups and SDR/HDR/resolution handshakes do **not** tear down and recreate the launcher Activity.
3. **Never Uses `SurfaceView` / Video Wallpapers:** Renders purely with hardware-accelerated double-buffered `ImageView` crossfades over an explicit opaque `#0A0D12` window background—so the window buffer is never blank or black.
4. **Instant < 20ms Cache Hydration:** Stores the last displayed Immich photo in a bounded LRU disk cache (`<= 48 MB`, downsampled to display resolution). On cold boot or Shield wake-up, Slate TV paints immediately from flash and refreshes from Immich asynchronously once Ethernet/Wi-Fi reconnects.
5. **Guaranteed Remote Focus Recovery (`FocusGuard`):** Restores D-pad focus on `onResume`, `onWindowFocusChanged`, and `DisplayManager.DisplayListener` events so your Shield remote responds on the very first click.

---

## Features

- **Gallery-First Minimal Home Screen:** Full-bleed Immich photos, quiet top-right clock, optional subtle date/location caption (`Oct 2024 · Seattle, WA`), and a single clean bottom dock of your pinned TV apps.
- **Idle Gallery Mode:** Automatically fades out the app dock after configurable inactivity (or press **Up** / **Back** on the home screen) to turn your TV into a distraction-free photo frame. Any remote press brings the dock back instantly.
- **First-Class Immich Plugin (`:plugin-immich`):**
  - Authenticates securely with the `x-api-key` HTTP header (never leaks API keys in URL query parameters).
  - **Phone QR Pairing Setup:** Opens an on-demand, PIN-protected local LAN web form with a scannable QR code on your TV—paste your Immich URL & API key from your phone in seconds without typing on a TV remote.
  - **Sources:** Random Library, Selected Albums (interactive multi-select picker), Favorites, or Recent Photos.
  - **Portrait Photo Intelligence:** Either filter strictly to landscape photos (accounting for EXIF rotation tags) or display portrait photos cleanly with **Smart Portrait Blur Pillarbox**.
  - **Homelab Friendly:** Supports local LAN `http://` servers and optional self-signed LAN HTTPS certificates.
- **Open Plugin Architecture (`:plugin-api`):**
  - Built-in plugins: **Immich Photos**, **Ambient Slate** (offline dark architectural gradients), and **Local Photos**.
  - External APK plugin discovery via Android AIDL (`com.stasisstate.slatetv.plugin.BACKGROUND_PROVIDER`) with HTTP header support, strict 5-second IPC timeouts, immediate unbinding, and full crash isolation.
  - Includes `:plugin-sample-external`, a working standalone external plugin APK.

---

## Remote Control Shortcuts

| Key | Action |
| :--- | :--- |
| **D-Pad Left / Right** | Navigate pinned dock apps and quick action pills (`Apps`, `Next`, `Settings`) |
| **OK / Center** | Launch app or trigger focused action |
| **Hold OK** or **Menu** | Open context menu (Move Left/Right, Pin/Unpin, Hide, App Info) |
| **D-Pad Up** or **Back** (on Home) | Enter distraction-free Gallery Mode (hides dock & HUD) |
| **Any Key** (in Gallery Mode) | Restore dock & HUD immediately |
| **Media Next** / **Channel Up** | Skip immediately to the next Immich / plugin photo |

---

## Project Modules

- `:app` — Slate TV Launcher APK (`com.stasisstate.slatetv`)
- `:plugin-api` — Open plugin interfaces & AIDL IPC contract (`ISlateBackgroundPlugin`)
- `:plugin-immich` — First-class Immich REST client, plugin implementation, QR generator, and on-demand LAN pairing server
- `:plugin-sample-external` — Reference standalone external plugin APK (`com.stasisstate.slatetv.plugin.sample`)

---

## Building from Source

Requires JDK 17 or 21 and Android SDK (API 36).

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Build Debug APKs (Launcher + Sample External Plugin)
./gradlew :app:assembleDebug :plugin-sample-external:assembleDebug

# Build minified Release APK
./gradlew :app:assembleRelease
```

APKs are output to:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`
- `plugin-sample-external/build/outputs/apk/debug/plugin-sample-external-debug.apk`

---

## Documentation

- [NVIDIA Shield TV Setup & Migrating from Projectivy](docs/SHIELD_SETUP.md)
- [Building External Background Plugins for Slate TV](docs/PLUGIN_DEVELOPMENT.md)
