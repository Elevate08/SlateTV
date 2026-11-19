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

Companion plugins support **on-demand LAN QR Code pairing** (single-use, 6-digit PIN rate-limited) so you can enter your server details from a phone browser instead of typing with a TV remote:

- **Background plugins:** Open **Slate TV Settings → Background Plugin**, select an installed plugin, then choose **Configure…** (or launch the plugin directly from **All Apps**).
- **Home shelf plugins:** Open **Slate TV Settings → Home Shelves**, toggle the shelf on/off, or choose **Configure…**. If a shelf source is unconfigured or unavailable, Slate TV automatically keeps the shelf hidden so your home screen stays uncluttered.

---

## 3. Setting Slate TV as Default Home Launcher

1. If you previously used another custom launcher that relied on an Accessibility Service, disable that Accessibility Service first under **Android TV Settings > Device Preferences > Accessibility**.
2. Verify Slate TV opens cleanly before replacing your default Home launcher:
   ```bash
   adb shell monkey -p com.stasisstate.slatetv -c android.intent.category.LEANBACK_LAUNCHER 1
   ```
3. Set Slate TV as your default Home launcher (no Accessibility Service needed):
   ```bash
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   adb shell cmd package set-home-activity com.stasisstate.slatetv/.ui.HomeActivity
   ```

### Instant Rollback (Re-enable Stock Android TV Launcher)

```bash
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```
