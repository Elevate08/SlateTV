# NVIDIA Shield TV Setup, Plugins & Projectivy Migration Guide

This guide walks through installing **Slate TV** (`com.stasisstate.slatetv`) and its optional **Immich** and **Jellyfin Live TV** plugins on an NVIDIA Shield TV, safely migrating away from Projectivy Launcher.

---

## 1. Install Slate TV and Plugins via ADB

Enable **Network Debugging** on your Shield (`Settings > Device Preferences > About > Build` x7 to unlock Developer Options, then `Developer Options > Network Debugging`).

```bash
# Connect to your Shield's local IP
adb connect <SHIELD_IP>:5555

# 1. Install Slate TV Launcher
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. (Optional) Install the Immich Photos background plugin
adb install -r plugin-immich/build/outputs/apk/debug/plugin-immich-debug.apk

# 3. (Optional) Install the Jellyfin Live TV & Guide home-shelf plugin
adb install -r plugin-jellyfin/build/outputs/apk/debug/plugin-jellyfin-debug.apk
```

---

## 2. Configure Immich & Jellyfin Plugins (QR Phone Pairing)

Both plugins support **on-demand LAN QR Code pairing** (single-use, 6-digit PIN rate-limited) so you can paste your server URL and API key from your phone instead of typing on a TV remote:

- **Immich Photos:** Open **Slate TV Settings → Background Plugin → Immich Photos → Configure Immich Photos…** (or launch *Immich Photos* from All Apps). Scan the QR code, paste your Immich URL & API key (scopes: `album.read`, `asset.read`, `asset.view`), test connection, pick albums/source mode, and save.
- **Jellyfin Live TV:** Open **Slate TV Settings → Home Shelves → Configure Jellyfin Live TV…** (or launch *Jellyfin Live TV* from All Apps). Scan the QR code, paste your Jellyfin URL & API key, test connection, and save.
  - If Live TV is enabled on your Jellyfin server, a minimal **Live TV · On Now** shelf appears automatically above your dock (press **Up** from the dock to browse channels or open the full 10-foot EPG guide).
  - If Live TV is **not** available on your Jellyfin instance, the shelf stays completely hidden so your home screen remains uncluttered.

---

## 3. Safely Migrating from Projectivy Launcher

1. Open **Shield Settings > Device Preferences > Accessibility** and turn **OFF** Projectivy Launcher's Accessibility Service.
2. Verify Slate TV opens cleanly before replacing your default Home launcher:
   ```bash
   adb shell monkey -p com.stasisstate.slatetv -c android.intent.category.LEANBACK_LAUNCHER 1
   ```
3. Set Slate TV as default Home launcher (no Accessibility Service needed):
   ```bash
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   adb shell cmd package set-home-activity com.stasisstate.slatetv/.ui.HomeActivity
   ```

### Instant Rollback (Re-enable Stock Shield Launcher)
```bash
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```
