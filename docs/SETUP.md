# Android TV Setup & Companion Plugin Guide

This guide walks through installing **Slate TV** (`com.stasisstate.slatetv`) and optional companion plugin APKs on an Android TV device.

---

## 1. Install Slate TV and Optional Plugins via ADB

Enable **Network Debugging** on your Android TV (`Settings > Device Preferences > About > Build` x7 to unlock Developer Options, then `Developer Options > Network Debugging`).

```bash
# Connect to your Android TV's local IP
adb connect <TV_IP>:5555

# 1. Install Slate TV Launcher
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. (Optional) Install companion background or home-shelf plugin APKs
adb install -r plugin-immich/build/outputs/apk/debug/plugin-immich-debug.apk
adb install -r plugin-jellyfin/build/outputs/apk/debug/plugin-jellyfin-debug.apk
```

> **Signing note:** Companion plugins verify Slate TV's signing certificate before sharing credentials or private media. When installing local builds, build both `:app` and plugin APKs with the same keystore (for example, all debug builds or all signed with the same upload key), or pass `-PtrustedSlateCertSha256=<64-hex-sha256>` when building a plugin for a differently signed host.

---

## 2. Configure Optional Companion Plugins

Companion plugins run as background/shelf extensions and stay hidden from your Android TV app drawer so your launcher stays uncluttered. Configure them directly from inside Slate TV:

- **Background plugins:** Open **Slate TV Settings → Background Plugin**, select an installed plugin, then choose **Configure…** (supports LAN auto-discovery, direct sign-in, or on-demand LAN QR code pairing).
- **Home shelf plugins:** Open **Slate TV Settings → Home Shelves**, toggle the shelf on/off, or choose **Configure…**. Live TV channels can be read directly from Android TV's system Live Channels EPG (no login required, after granting `READ_TV_LISTINGS`), or optionally paired with a media server via LAN discovery and Quick Connect. If a shelf source is unconfigured or unavailable, Slate TV automatically keeps the shelf hidden.

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
