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

- **Background Sources:** Open **Slate TV Settings → BACKGROUND SOURCE** to choose **Ambient Slate**, **Local Photos**, **Immich Photos** (tap **Configure Immich Photos** for LAN auto-discovery, email & password sign-in, or QR phone pairing, with TOFU TLS certificate pinning), or any installed JSON photo feed extension.
  - **Sign in with email & password:** type just the address (e.g. `192.168.1.20` or `photos.example.com`) — Slate TV finds the right scheme and Immich's default port `2283` on its own, remembers your email, and mints a read-only "Slate TV" API key. The password is never stored.
  - **Pair with phone (QR):** no password on the TV and nothing ever connects to the TV — only TV → Immich traffic, so it works behind strict firewalls. The TV shows a QR code (opens Immich in your phone's browser) and a code like `slate-k7m4q9xa`. In Immich on your phone, open an album → **Share → Create link**, set **Custom URL** to that code (no password) and create it. The TV finds the link within seconds and shows that album, read-only. Needs Immich v1.137+. Afterwards you can remove the custom URL; the link keeps working. For your whole library, favorites or recent photos, use email & password sign-in.
- **Live TV & Guide Shelf:** Open **Slate TV Settings → HOME SHELVES** and tap **Configure Live TV & Guide**. Uses Android TV's built-in **System Live Channels** EPG only (no login; just grant `READ_TV_LISTINGS`). Jellyfin content (Next Up, Latest Media, Watch Next) appears through **App Channels** when the Jellyfin app is installed, with no Jellyfin sign-in in Slate TV. Auto-hides whenever unconfigured or unavailable.
- **Google Calendar:** Open **Slate TV Settings → DISPLAY & GALLERY → Show Google Calendar Events** and allow Android's read-only Calendar permission. The feature is off by default and requires no additional Google sign-in. Slate TV shows up to three events over the next seven days from visible calendars already synced to a single Google account on the TV. Event titles and times stay on the device and are visible to anyone who can see the home screen. Turning the setting off hides them immediately. Availability depends on the TV's Android Calendar Provider and Google Calendar sync. If the provider has calendars from more than one Google account, the agenda stays hidden because Slate TV cannot determine the active Google TV profile.
- **Home Assistant:** Open **Slate TV Settings → SMART HOME → Home Assistant**. See [section 4](#4-home-assistant-doorbell-alerts--home-controls).
- **Zero-Code JSON Extensions:** Open **Slate TV Settings → PLUGINS → Manage Plugins** to browse the plugin catalog (install, uninstall or reinstall first-party plugins) or add any HTTP/JSON photo feed or home shelf manifest via **Phone QR Code** or direct URL (with explicit on-TV confirmation).

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

---

## 4. Home Assistant: Doorbell Alerts & Home Controls

Slate TV connects directly to your Home Assistant server. It needs no add-on or custom integration.

1. **Pick the server.** Open **Slate TV Settings → SMART HOME → Home Assistant**. Servers found on your network (mDNS) appear as **Found: …** rows — select one. Otherwise select **Server address** and type it (for example `http://homeassistant.local:8123`).
2. **Sign in.** Select **Sign in** and enter your Home Assistant username and password, plus your two-factor code if you use one. Slate TV creates a long-lived token named `Slate TV (<device name>)` (valid 10 years; you can revoke it under **Profile → Security**), stores it encrypted with the Android Keystore, and sends it only to that server. The password is never stored. Password sign-in needs the built-in **Home Assistant Local** login; if your server only offers SSO or trusted networks, use a token instead (below). Slate TV warns before signing in over plain `http://` to an address outside your local network.
   - **Paste a token instead:** under **ADVANCED → Paste an access token**, enter a token from **Profile → Security → Long-lived access tokens → Create token**.
   - **Set up from a computer:** see [Configure over ADB](#configure-over-adb).
3. **Home screen controls (optional).** Under **Choose home controls**, pick up to 12 lights, switches, fans, covers, toggles (input booleans), scenes, or scripts. They appear as a row above the dock: press **Up** from the dock to reach them. Press **OK** to toggle a device or run a scene or script. Hold **OK** on a dimmable light to set its brightness.
4. **Doorbells.** Select **Add doorbell**, pick the entity that fires when someone rings (a binary sensor, `event.*` entity, or button), then optionally pick a camera. When it rings, a card with the live camera feed appears in the corner of the screen. Press **Left/Right** to choose a reply, **OK** to send it, or **Back** to dismiss. The card closes on its own after the auto-dismiss time.
5. **Quick replies.** Each reply always fires a `slatetv_quick_reply` event in Home Assistant. Its data includes `message`, `doorbell`, `camera`, and `source`. A reply can also run a script, scene, or button you pick. Scripts receive the same fields as variables, so a script can play `{{ message }}` on a doorbell speaker. To act on replies without a script, create an automation in Home Assistant with an **Event** trigger of type `slatetv_quick_reply`. Firing the event requires an admin user's token. Replies that run a script work with any user's token.

### Configure over ADB

To skip typing on the TV, create a long-lived token in Home Assistant (**Profile → Security**), save it in `token.txt`, connect adb to the TV (see [section 1](#1-install-slate-tv-via-google-play-or-adb)) and run:

```bash
adb shell am broadcast -a com.stasisstate.slatetv.action.HA_CONFIGURE \
  -n com.stasisstate.slatetv/com.stasisstate.slatetv.connector.homeassistant.HaAdbConfigReceiver \
  --es url https://your-ha:8123 --es token "$(cat token.txt)"
```

Slate TV checks the token against the server, saves it encrypted, and reconnects. It works right after a fresh install, without opening the app. The result line reports what happened:

| `result=` | Meaning |
|---|---|
| `-1` | Saved and connected (`data` names the server and version) |
| `2` | Bad arguments (missing or malformed `url` or `token`) |
| `3` | Home Assistant rejected the token |
| `4` | Server unreachable or timed out |
| `5` | Other error |

Only adb (or the system) can send this broadcast: the receiver requires `android.permission.DUMP`, which ordinary apps can't hold. Slate TV never logs the token, but Android keeps recent broadcasts, including their extras, in its broadcast history for a while; only adb and system apps can read it (`dumpsys activity broadcasts`). Repeated wrong tokens count as failed logins, and Home Assistant may ban the TV's IP if `ip_ban_enabled` is on.

### Show alerts over other apps

To show the doorbell card over whatever is playing, Slate TV needs **Display over other apps** access. Select **Show alerts over other apps** in the Home Assistant settings. Many TVs don't include this settings screen. If yours doesn't, grant access over ADB:

```bash
adb shell appops set com.stasisstate.slatetv SYSTEM_ALERT_WINDOW allow
```

Without this access, doorbell alerts only appear while Slate TV is on screen.

While doorbell alerts are on, Slate TV keeps a small background service running, so Android doesn't suspend the connection while another app is in front. Turning alerts off, or removing all doorbells, stops the service. Some Android system screens, such as Settings, hide all overlays while they are open.
