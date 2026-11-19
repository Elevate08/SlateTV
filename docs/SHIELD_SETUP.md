# NVIDIA Shield TV Setup & Migration Guide

This guide walks through installing **Slate TV** on an NVIDIA Shield TV (2015 / 2017 / 2019 / Pro), safely migrating away from Projectivy Launcher, and verifying zero black-screen wake-ups.

---

## 1. Install Slate TV via ADB

Enable **Network Debugging** on your Shield (`Settings > Device Preferences > About > Build` x7 to unlock Developer Options, then `Developer Options > Network Debugging`).

```bash
# Connect to your Shield's local IP
adb connect <SHIELD_IP>:5555

# Install Slate TV
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

*(Optional)* Install the reference external plugin APK to test external AIDL plugin discovery:
```bash
adb install -r plugin-sample-external/build/outputs/apk/debug/plugin-sample-external-debug.apk
```

---

## 2. Safely Migrating from Projectivy Launcher (Avoid the Black-Screen Trap!)

If you previously used Projectivy or Launcher Manager on your Shield:
1. Open **Shield Settings > Device Preferences > Accessibility** and turn **OFF** Projectivy Launcher's Accessibility Service.
2. If you previously disabled the stock Google TV launcher via ADB, **verify Slate TV launches cleanly first** before uninstalling Projectivy:
   ```bash
   adb shell monkey -p dev.slatetv.launcher -c android.intent.category.LEANBACK_LAUNCHER 1
   ```
3. Configure Immich inside Slate TV (**Settings gear > Immich Photos > Configure Immich Plugin… > Pair with Phone (QR Code)**).

---

## 3. Set Slate TV as Default Home Launcher (No Accessibility Service Needed)

Android TV restricts the normal "Default Home App" chooser in UI settings unless the stock launcher is disabled for user 0.

### Option A: Clean ADB Default Home Switch (Recommended)
```bash
# 1. Disable stock Android TV Home for current user (non-destructive, 100% reversible)
adb shell pm disable-user --user 0 com.google.android.tvlauncher

# 2. Explicitly bind Slate TV as the Home activity
adb shell cmd package set-home-activity dev.slatetv.launcher/.ui.HomeActivity
```
Press the **Home** button on your Shield remote—Slate TV opens instantaneously with zero stock-launcher flash and zero Accessibility Service overhead.

### Option B: Instant Rollback (Re-enable Stock Launcher)
If you ever want to restore the stock Shield launcher:
```bash
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

---

## 4. Generating an Immich API Key

In your Immich web UI:
1. Go to **Account Settings > API Keys > New API Key**.
2. Grant minimal read-only permissions (for Immich v1.135+ / v2.x scoped keys):
   - `album.read`
   - `asset.read`
   - `asset.view`
   - *(Optional)* `asset.download` (only if using Full Size mode)
3. On your TV, open **Slate TV Settings > Configure Immich Plugin…**, scan the QR code with your phone, paste the URL (e.g. `http://192.168.1.50:2283`) and API key, and tap **Send to TV**.

---

## 5. Recommended NVIDIA Shield Display Tips

- **HDMI-CEC Wake:** Slate TV handles HDMI-CEC display mode changes in-place without recreating the Activity. If your TV takes a few seconds to complete HDCP/HDR handshake, Slate TV keeps its cached backdrop painted and automatically re-syncs Immich once Ethernet/Wi-Fi reconnects.
- **Image Quality:** Keep Immich Quality set to **Preview (1440p)**—it looks sharp on 4K TVs while keeping memory usage around ~35–55 MB on the Shield's 3 GB RAM.
