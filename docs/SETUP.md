# Android TV Setup & Companion Plugin Guide

This guide walks through installing **Slate TV** (`com.stasisstate.slatetv`) and optional companion plugin APKs on an Android TV device.

---

## 1. Install Slate TV via Google Play or ADB

Slate TV ships as a **single self-contained APK / App Bundle**—no companion plugin APKs or sideloading are required. Built-in photo server support, System Live Channels / EPG Guide, and zero-code HTTP/JSON extensions all live inside Slate TV.

For local testing over ADB, enable **Network Debugging** on your Android TV (`Settings > Device Preferences > About > Build` x7 to unlock Developer Options, then `Developer Options > Network Debugging`):

```bash
# Connect to your Android TV's local IP
adb connect <TV_IP>:5555

# Install Slate TV (single APK includes built-in connectors + JSON extension manager)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# (Optional) Grant System Live Channels EPG access if your Android TV build suppresses the runtime prompt
adb shell pm grant com.stasisstate.slatetv android.permission.READ_TV_LISTINGS
```

---

## 2. Configure Built-In Connectors & Zero-Code JSON Extensions

Configure everything directly inside **Slate TV Settings** (zero extra app icons cluttering your launcher):

- **Background Sources:** Open **Slate TV Settings → BACKGROUND PLUGIN** to choose **Ambient Slate**, **Local Photos**, **Immich Photos** (tap **Configure Immich Photos** for LAN auto-discovery, direct sign-in, or QR phone pairing with TOFU TLS certificate pinning), or any installed JSON photo feed extension.
- **Live TV & Guide Shelf:** Open **Slate TV Settings → HOME SHELVES** and tap **Configure Live TV & Guide**. Uses Android TV's built-in **System Live Channels** EPG by default (no login required after granting `READ_TV_LISTINGS`), or optionally pairs with a media server via LAN discovery and Quick Connect. Auto-hides whenever unconfigured or unavailable.
- **Zero-Code JSON Extensions:** Open **Slate TV Settings → EXTENSIONS → Manage JSON Extensions** to add any HTTP/JSON photo feed or home shelf manifest via **Phone QR Code** or direct URL (with explicit on-TV confirmation).

---

## 3. Setting Slate TV as Default Home Launcher

1. **In-App (Recommended):** Open **Slate TV Settings → APPS & SYSTEM → Default Home Launcher** and choose **Set Slate TV as Default Home** (uses Android's system Home role picker / Default Home Apps settings).
2. **If you previously used an Accessibility-based launcher:** Disable its Accessibility Service under **Android TV Settings > Device Preferences > Accessibility**.
3. **Optional ADB Fallback** (for Android TV builds that hide the system Home picker or lock the stock launcher):
   ```bash
   # Verify Slate TV opens cleanly first
   adb shell monkey -p com.stasisstate.slatetv -c android.intent.category.LEANBACK_LAUNCHER 1

   # Set Slate TV as default Home activity (and optionally disable the stock launcher for user 0)
   adb shell cmd package set-home-activity com.stasisstate.slatetv/.ui.HomeActivity
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   ```

### Instant Rollback (Re-enable Stock Android TV Launcher)

```bash
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```
