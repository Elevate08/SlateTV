# Building External Background Plugins for Slate TV

Slate TV discovers external background plugin APKs automatically via Android's `PackageManager` and communicates over a clean, versioned AIDL contract (`:plugin-api`).

Unlike older TV launcher plugin APIs, Slate TV's `PluginMediaItem` supports **arbitrary HTTP request headers** (`Bundle`), so self-hosted backends (Immich, Jellyfin, Plex, Home Assistant, Nextcloud) never have to leak secrets into URL query parameters.

---

## 1. Add `:plugin-api` Dependency

Include the `:plugin-api` module (or its AAR) in your plugin Android application module:

```kotlin
dependencies {
    implementation(project(":plugin-api"))
}
```

---

## 2. Implement `ISlateBackgroundPlugin.Stub`

Create an exported Android `Service` returning an `ISlateBackgroundPlugin.Stub()` binder:

```kotlin
class MyBackgroundPluginService : Service() {

    private val binder = object : ISlateBackgroundPlugin.Stub() {
        override fun getApiVersion(): Int = PluginContract.API_VERSION

        override fun getPluginInfo(): PluginInfo = PluginInfo(
            id = "com.example.myplugin",
            name = "My Photo Feed",
            description = "Custom backdrops from my server",
            version = "1.0.0",
            supportsNext = true,
            settingsActivityClassName = MySettingsActivity::class.java.name,
        )

        override fun getNextMedia(options: Bundle?): PluginMediaItem {
            val headers = Bundle().apply {
                putString("Authorization", "Bearer my-token")
            }
            return PluginMediaItem(
                id = "item-123",
                imageUri = "https://photos.example.local/api/items/123/preview",
                headers = headers,
                subtitle = "Oct 2024 · Iceland",
                width = 1920,
                height = 1080,
                allowUntrustedTls = false,
            )
        }

        override fun getMediaBatch(count: Int, options: Bundle?): MutableList<PluginMediaItem> {
            return mutableListOf(getNextMedia(options))
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
```

### Supported `imageUri` Schemes
- `https://` or `http://` — Downloaded via OkHttp using the supplied `headers` Bundle and cached automatically in Slate TV's LRU disk cache.
- `content://` — Read via `ContentResolver` (e.g., from a `FileProvider`).
- `ambient://#hex1,#hex2,#hex3` — Zero-network procedural gradient rendered directly by Slate TV.

---

## 3. Declare the Service in `AndroidManifest.xml`

```xml
<service
    android:name=".MyBackgroundPluginService"
    android:exported="true">
    <intent-filter>
        <action android:name="dev.slatetv.launcher.plugin.BACKGROUND_PROVIDER" />
    </intent-filter>
    <meta-data
        android:name="dev.slatetv.plugin.ID"
        android:value="com.example.myplugin" />
    <meta-data
        android:name="dev.slatetv.plugin.NAME"
        android:value="My Photo Feed" />
    <meta-data
        android:name="dev.slatetv.plugin.DESCRIPTION"
        android:value="Custom backdrops from my server" />
    <meta-data
        android:name="dev.slatetv.plugin.SETTINGS_ACTIVITY"
        android:value="com.example.myplugin.MySettingsActivity" />
    <meta-data
        android:name="dev.slatetv.plugin.API_VERSION"
        android:value="1" />
</service>
```

---

## 4. Lifecycle & Reliability Contract

- Slate TV binds your service **only when fetching** new metadata and immediately unbinds afterward, keeping idle RAM usage near zero.
- IPC calls have a strict **5-second timeout**. If your plugin times out or throws an exception, Slate TV isolates the failure and keeps the last cached photo visible on screen.
- See `:plugin-sample-external` in this repository for a complete working APK example.
