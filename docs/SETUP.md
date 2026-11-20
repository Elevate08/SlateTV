# Android TV Setup Guide

Slate TV (`com.stasisstate.slatetv`) is a single APK. Every connector and extension is built in, so you don't need any companion apps.

---

## 1. Install

Install from Google Play, or sideload over ADB.

### Enable ADB debugging

1. **Unlock Developer options.** Open **Settings → System → About** (on SHIELD: **Settings → Device Preferences → About**). Select **Build** (labelled **Android TV OS build** on Google TV) 7 times.
2. **Turn on debugging.** In **Settings → System → Developer options**, turn on **USB debugging**, then one of:
   - **Wireless debugging** (Google TV, Android 11+). Open it, then select **Pair device with pairing code**. Note the IP, port and code it shows.
   - **Network debugging** (SHIELD and older Android TV). The TV listens on port `5555`.
3. **Connect.** Accept the **Allow debugging?** prompt on the TV.
   ```bash
   # Wireless debugging: pair once, then connect to the port on the Wireless debugging screen (not the pairing port)
   adb pair <TV_IP>:<PAIRING_PORT> <CODE>
   adb connect <TV_IP>:<PORT>

   # Network debugging
   adb connect <TV_IP>:5555
   ```

On Google TV, Wireless debugging turns off when the TV restarts. Turn it back on to reconnect.

### Install the APK

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Optional: Live Channels guide access, if the TV never shows the permission prompt
adb shell pm grant com.stasisstate.slatetv android.permission.READ_TV_LISTINGS
```

---

## 2. Set Slate TV as the Home launcher

1. Open **Slate TV Settings → APPS & SYSTEM → Default Home Launcher → Set Slate TV as Default Home**. Pick **Slate TV** and select **Set as default**, then press **Home** to check.
2. If you used an Accessibility-based launcher before, turn off its service in **Settings → Device Preferences → Accessibility**.
3. If Home still opens the stock launcher, set Slate TV as Home over ADB, then check which app handles Home:
   ```bash
   adb shell cmd package set-home-activity --user current com.stasisstate.slatetv/.ui.HomeActivity
   adb shell cmd package resolve-activity --brief --user current --query-flags 65536 -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
4. **NVIDIA SHIELD:** if the check still reports `com.google.android.tvlauncher`, turn off Android TV Home:
   ```bash
   adb shell pm disable-user --user current com.google.android.tvlauncher
   # Undo
   adb shell pm enable --user current com.google.android.tvlauncher
   ```

> **Google TV (Chromecast and others):** Google TV ignores the preferred Home setting. Turning off `com.google.android.apps.tv.launcherx` makes Home open Google's setup-recovery screen instead of Slate TV. **Do not turn off `com.google.android.tungsten.setupwraith`.** Doing that took a Chromecast HD offline. Undo with `adb shell pm enable --user current <package>`.

---

## 3. Configure

Everything is in **Slate TV Settings**:

| Feature | Where | Notes |
| --- | --- | --- |
| **Background** | **BACKGROUND SOURCE** | Ambient Slate, Local Photos, Immich Photos, or a JSON photo feed. |
| **Live TV & Guide** | **HOME SHELVES → Configure Live TV & Guide** | Uses the TV's System Live Channels guide and needs `READ_TV_LISTINGS`. Jellyfin rows appear through App Channels when the Jellyfin app is installed. The shelf hides when it has nothing to show. |
| **Google Calendar** | **DISPLAY & GALLERY → Show Google Calendar Events** | Off by default. Shows up to 3 events from the next 7 days, from the one Google account synced on the TV. It stays hidden if the TV has more than one account. Events are visible to anyone who can see the screen. |
| **Home Assistant** | **SMART HOME → Home Assistant** | See [section 4](#4-home-assistant). |
| **Plugins** | **PLUGINS → Manage Plugins** | Install, remove or reinstall catalog plugins, or add a JSON manifest by phone QR code or URL. |

### Immich Photos

Open **BACKGROUND SOURCE → Configure Immich Photos**. Slate TV can find servers on your network and pins each server's TLS certificate the first time it connects.

- **Email & password:** enter the server address, such as `192.168.1.20`. Slate TV finds the scheme and port (default `2283`) and creates a read-only "Slate TV" API key. It doesn't store your password.
- **Phone QR pairing** (Immich v1.137+, one album): the TV shows a QR code and a code like `slate-k7m4q9xa`. In Immich on your phone, open an album, select **Share → Create link**, set **Custom URL** to that code, and create the link. The TV picks it up within seconds. Only the TV makes connections, so this works behind strict firewalls.

---

## 4. Home Assistant

1. **Create a token.** In Home Assistant, open **Profile → Security → Long-lived access tokens → Create token**. Slate TV encrypts the token and sends it only to the server you enter.
2. **Connect.** In **SMART HOME → Home Assistant**, enter the server address (for example `http://homeassistant.local:8123`) and the token, then select **Test connection**.
3. **Home controls (optional).** Under **Choose home controls**, pick up to 12 lights, switches, fans, covers, input booleans, scenes or scripts. They appear in a row above the dock. Press **Up** to reach them and **OK** to toggle or run one. Hold **OK** on a dimmable light to set its brightness.
4. **Doorbells.** Select **Add doorbell**, pick the entity that fires on a ring (binary sensor, `event.*` or button), and optionally a camera. When someone rings, a card with the live camera feed appears. Press **Left/Right** to choose a reply, **OK** to send it, or **Back** to dismiss it.
5. **Quick replies.** Each reply fires a `slatetv_quick_reply` event with `message`, `doorbell`, `camera` and `source`, and can also run a script, scene or button. Scripts get the same fields as variables, for example `{{ message }}`. Firing the event needs an admin token. Running a script works with any token.

**Alerts over other apps:** select **Show alerts over other apps** in the Home Assistant settings. If your TV doesn't have that screen, run:

```bash
adb shell appops set com.stasisstate.slatetv SYSTEM_ALERT_WINDOW allow
```

Without this access, alerts only appear while Slate TV is on screen. While alerts are on, a small background service keeps the connection alive. Some system screens, such as Settings, hide all overlays.
