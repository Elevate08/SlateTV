# Building External Plugins for Slate TV

Slate TV discovers external plugin APKs automatically via Android's `PackageManager` and communicates over two versioned AIDL contracts in `:plugin-api` (`API_VERSION = 1`):

1. **Background Provider (`com.stasisstate.slatetv.plugin.BACKGROUND_PROVIDER`)** — Supplies full-bleed photos/art via `ISlateBackgroundPlugin` (reference implementations: `:plugin-immich` and `:plugin-sample-external`).
2. **Home Shelf Provider (`com.stasisstate.slatetv.plugin.SHELF_PROVIDER`)** — Supplies optional, minimal home-screen shelves such as Live TV & Guides via `ISlateShelfPlugin` (reference implementation: `:plugin-jellyfin`).

Both contracts support **arbitrary HTTP request headers** (`Bundle`), so self-hosted services never need to leak tokens in URL query parameters.

---

## 1. Background Provider (`ISlateBackgroundPlugin`)

Declare an exported `Service` with intent filter `com.stasisstate.slatetv.plugin.BACKGROUND_PROVIDER`:

```xml
<service
    android:name=".MyBackgroundPluginService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.stasisstate.slatetv.plugin.BACKGROUND_PROVIDER" />
    </intent-filter>
    <meta-data android:name="com.stasisstate.slatetv.plugin.ID" android:value="com.example.myplugin" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.NAME" android:value="My Photo Feed" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.DESCRIPTION" android:value="Custom photo backdrops" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.SETTINGS_ACTIVITY" android:value="com.example.myplugin.SettingsActivity" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.API_VERSION" android:value="1" />
</service>
```

Supported `PluginMediaItem.imageUri` schemes:
- `https://` or `http://` (fetched with `headers`, origin-bound redirects)
- `content://` (read via `ContentResolver` with `FLAG_GRANT_READ_URI_PERMISSION`, ideal for proxying self-signed LAN HTTPS servers as `:plugin-immich` does)
- `ambient://#hex1,#hex2,#hex3` (zero-network procedural gradient)

---

## 2. Home Shelf / Live TV Provider (`ISlateShelfPlugin`)

Declare an exported `Service` with intent filter `com.stasisstate.slatetv.plugin.SHELF_PROVIDER`:

```xml
<service
    android:name=".MyShelfPluginService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.stasisstate.slatetv.plugin.SHELF_PROVIDER" />
    </intent-filter>
    <meta-data android:name="com.stasisstate.slatetv.plugin.ID" android:value="com.example.myshelf" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.NAME" android:value="My Live TV" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.DESCRIPTION" android:value="Live TV channels on the home screen" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.SETTINGS_ACTIVITY" android:value="com.example.myshelf.SettingsActivity" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.GUIDE_ACTIVITY" android:value="com.example.myshelf.GuideActivity" />
    <meta-data android:name="com.stasisstate.slatetv.plugin.API_VERSION" android:value="1" />
</service>
```

Return `ShelfPayload(available = false, ...)` whenever your service is unconfigured or the feature (e.g. Live TV tuner) is unavailable—Slate TV automatically hides the shelf so the home screen remains minimal.

---

## 3. Security & Lifecycle Rules

- Verify `Binder.getCallingUid()` belongs to `com.stasisstate.slatetv` before returning auth headers or private media (as implemented in `:plugin-immich` and `:plugin-jellyfin`).
- Slate TV binds on-demand, enforces strict **4-second** bind + RPC timeouts on bounded daemon threads, unbinds immediately afterward, and applies a 30-second cooldown after consecutive timeouts.
- Slate TV strictly ignores `file://` image URIs and untrusted TLS overrides from external plugins, strips auth headers on cross-origin HTTP redirects, and blocks any launch intent targeting Slate TV's own package.
