# Developing Zero-Code JSON Extensions for Slate TV

Slate TV supports community **photo feeds** and **home shelves** through a zero-code **Open HTTP/JSON Extension Protocol (v1)**. Extensions require **no Android APK, no sideloading, no Play Store account, and no code running on the TV**—just host a `manifest.json` file (static GitHub Pages, Cloudflare Worker, or a local LAN server) and add its URL from **Slate TV Settings → Extensions → Manage JSON Extensions** via Phone QR Code or direct URL.

---

## 1. Extension Manifest (`manifest.json`)

Serve a JSON document over **HTTPS** (or **HTTP** on a private RFC 1918 / `.local` LAN host):

```json
{
  "schemaVersion": 1,
  "id": "com.example.alpine-gallery",
  "name": "Alpine Gallery",
  "description": "Curated daily mountain photography",
  "version": "1.0.0",
  "author": "Your Name",
  "type": "background",
  "backgroundEndpoint": "/backgrounds.json",
  "refreshIntervalSeconds": 900
}
```

| Field | Required | Notes |
| :--- | :---: | :--- |
| `schemaVersion` | Yes | Must be `1` |
| `id` | Yes | Stable reverse-DNS identifier (e.g., `com.example.feed`) |
| `name` | Yes | Display name shown in Slate TV (≤ 120 chars) |
| `description` | No | Short subtitle (≤ 160 chars) |
| `version` | No | Version string (e.g., `1.0.0`) |
| `author` | No | Publisher name displayed on the TV install confirmation screen |
| `type` | Yes | `"background"`, `"shelf"`, or `"both"` |
| `backgroundEndpoint` | When `background`/`both` | Relative path or same-origin URL returning a background feed |
| `shelfEndpoint` | When `shelf`/`both` | Relative path or same-origin URL returning a home shelf |
| `refreshIntervalSeconds` | No | Minimum cache interval for background feed refreshes (default `900`) |
| `authHeaderName` | No | Optional HTTP header name for user-supplied API key (e.g., `X-API-Key` or `Authorization`) |

> **Simple Photo Lists:** You can also point Slate TV directly at a plain JSON photo array (`[{"imageUrl": "https://...", "title": "...", "subtitle": "..."}]`) or `{"items": [...]}` without a separate manifest—Slate TV automatically wraps it as a background feed extension.

---

## 2. Background Feed Endpoint (`backgrounds.json`)

```json
{
  "items": [
    {
      "id": "dolomites-01",
      "imageUrl": "https://example.com/photos/dolomites.jpg",
      "title": "Evening Light",
      "subtitle": "Dolomites · October 12",
      "width": 3840,
      "height": 2160
    }
  ]
}
```

- `"url"` is also accepted as an alias for `"imageUrl"`.
- Only `https://` and `http://` image URLs are accepted; responses are capped at **1 MiB** and **200 items**.
- When an API key is configured, it is encrypted on-device with Android Keystore and sent **only** to the manifest's exact origin (stripped automatically on cross-origin redirects).

---

## 3. Home Shelf Endpoint (`shelf.json`)

```json
{
  "available": true,
  "shelfTitle": "Featured Streams",
  "items": [
    {
      "id": "item-1",
      "title": "Alpine Trails",
      "subtitle": "Live Nature Documentary",
      "channelNumber": "CH 12",
      "badge": "LIVE",
      "imageUrl": "https://example.com/logos/nature.png",
      "progressPercent": 46,
      "launchUri": "https://example.com/watch/alpine"
    }
  ]
}
```

- Set `"available": false` (or return an empty `items` array) to keep the shelf cleanly hidden on the home screen.
- Responses are capped at **1 MiB** and **24 cards**.
- Allowed card actions:
  - `launchPackageName`: launches an installed Android TV app by package name (never Slate TV itself).
  - `launchUri`: strictly restricted to `https://`, `http://`, and `market://` schemes.

---

## 4. Installing on Android TV

1. Open **Slate TV Settings → Extensions → Manage JSON Extensions**.
2. Choose **Add Extension via Phone QR Code** (recommended—paste the manifest URL and optional API key on your phone browser using a one-time PIN) or **Add Extension by URL**.
3. Review the on-TV confirmation screen showing extension name, author, origin host, and type, then select **Install**.
