# Android TV Setup Guide

This guide walks through installing and configuring **Slate TV** (`com.stasisstate.slatetv`) on an Android TV device.

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

- **Background Sources:** Open **Slate TV Settings → BACKGROUND SOURCE** to choose **Ambient Slate**, **Local Photos**, **Immich Photos** (tap **Configure Immich Photos** for LAN auto-discovery, direct sign-in, or QR phone pairing with TOFU TLS certificate pinning), or any installed JSON photo feed extension.
- **Live TV & Guide Shelf:** Open **Slate TV Settings → HOME SHELVES** and tap **Configure Live TV & Guide**. Uses Android TV's built-in **System Live Channels** EPG only (no login; just grant `READ_TV_LISTINGS`). Jellyfin content (Next Up, Latest Media, Watch Next) appears through **App Channels** when the Jellyfin app is installed, with no Jellyfin sign-in in Slate TV. Auto-hides whenever unconfigured or unavailable.
- **Zero-Code JSON Extensions:** Open **Slate TV Settings → EXTENSIONS → Manage JSON Extensions** to add any HTTP/JSON photo feed or home shelf manifest via **Phone QR Code** or direct URL (with explicit on-TV confirmation).

---

## 3. Setting Slate TV as Default Home Launcher

1. Open **Slate TV Settings → APPS & SYSTEM → Default Home Launcher → Set Slate TV as Default Home**. Select **Slate TV** in the Android picker and confirm **Set as default**. Press Home to verify which launcher opens.
2. **If you previously used an Accessibility-based launcher:** Disable its Accessibility Service under **Android TV Settings > Device Preferences > Accessibility**.
3. If the picker is unavailable or Home still opens the stock launcher, connect over ADB and check which app handles Home after setting Slate TV as the preferred launcher.
   ```bash
   adb shell cmd package set-home-activity --user current com.stasisstate.slatetv/.ui.HomeActivity
   adb shell cmd package resolve-activity --brief --user current --query-flags 65536 -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
4. On NVIDIA SHIELD, Android TV Home can keep handling the Home button after Android selects Slate TV. If the check still reports `com.google.android.tvlauncher/.MainActivity`, disable Android TV Home for the current user, then press Home again.
   ```bash
   adb shell pm disable-user --user current com.google.android.tvlauncher
   ```

### Restore Android TV Home on NVIDIA SHIELD

```bash
adb shell pm enable --user current com.google.android.tvlauncher
adb shell cmd package set-home-activity --user current com.google.android.tvlauncher/.MainActivity
```
