# Reusing the installed Jellyfin app instead of a second sign-in

Investigated 2026-11-19 against Jellyfin for Android TV **0.20.0** (`org.jellyfin.androidtv`, latest release) on an
NVIDIA SHIELD (Android 11), plus source review of jellyfin-androidtv `master`/`v0.20.0`, jellyfin-android, and AOSP
TvProvider (Android 8–14). Probes ran as Slate TV's own uid (instrumented test), not as `adb shell`.

## Bottom line

| Data | Without signing in to Slate TV? | How |
|---|---|---|
| Jellyfin home rows: Recently added, My media, Next up, Movies, New episodes | **Yes**, verified on device | TvProvider preview programs, `READ_TV_LISTINGS` (existing App Channels shelves) |
| Jellyfin "continue watching" | **Yes**, verified | TvProvider Watch Next, same permission |
| Artwork for those rows | **Yes**, verified | `content://org.jellyfin.androidtv.integration.provider.ImageProvider?src=…` (exported, unguarded) |
| Open an item in Jellyfin | **Yes**, verified | Explicit intent to `StartupActivity` with `ItemId` / `ItemIsUserView` extras (already in each row's `intent_uri`) |
| Server address | **Yes**, verified | Parsed from the `src` of those ImageProvider URIs, which have no token. Not used: Slate TV no longer connects to a Jellyfin server |
| Library search | Yes, but fragile | Exported `org.jellyfin.androidtv.content` search-suggestion provider (see below) |
| **Live TV channel list + now/next guide** | **No** | Needs a server token; see "Not possible" |

So the zero-login Jellyfin experience is the **App Channels** shelves, and that is the only Jellyfin integration Slate
TV ships. Jellyfin Live TV would need a server token, so Slate TV does not offer it; Live TV comes only from System
Live Channels.

## Findings

### TvProvider (works)
For a caller holding `READ_TV_LISTINGS`, AOSP `TvProvider.createSqlParams()` filters rows with
`package_name = caller OR searchable = 1`, the same way for channels, programs, preview_program and
watch_next_program on Android 8–14. `searchable` defaults to 1, and Jellyfin doesn't change it. Constraints: no
`selection` is allowed (it throws `SecurityException`), so filter on the client. Writes are limited to the owner.
`package_name` is set by TvProvider, so a Jellyfin-owned row can't be spoofed by another app.

On device, Jellyfin publishes 5 preview channels (hourly refresh by `LeanbackChannelWorker`) plus Watch Next. Poster
URIs go through its ImageProvider and contain the server URL, no `api_key`.

### Search provider (works with caveats; not used yet)
`content://org.jellyfin.androidtv.content/suggestions/search_suggest_query/<term>?limit=N` is exported with no
permission. It runs `GET /Items?searchTerm=` **with the Jellyfin app's own session** and returns name, item UUID,
year, duration and artwork. Caveats:
- It returns 0 rows on a cold Jellyfin process (session restore is async), so retry.
- It is search-only, with nothing to list everything.
- Live TV channels appear (e.g. "CBS"). `suggest_is_live` comes from the item's `isLive`, which was 0 for every
  channel tested, so channels can't reliably be told apart from studios or genres with the same name.
- It isn't a published API. It also needs `<queries><provider android:authorities="org.jellyfin.androidtv.content"/>`.

Candidate for a future "search Jellyfin from Slate TV" feature; not suitable as a channel source.

### Not possible (no non-privileged path)
- **Jellyfin's access token**: it lives in the private file `filesDir/authentication_store.json`. The old
  AccountManager authenticator was never exported and was removed entirely in 0.20.0-beta.1. There is no
  `sharedUserId` and the signing key is different.
- **Jellyfin Live TV in the system guide**: Jellyfin has no TvInputService and writes no TIF channels/programs, so
  System Live Channels can't show it.
- **Guide data from Jellyfin**: no exported surface returns programs.
- **The TV app approving Quick Connect**: it only *initiates* codes. jellyfin-web (and the mobile app, which wraps it)
  can approve, via the route `/web/#/quickconnect?code=…`.
- `MediaBrowser`/Media3 `AndroidMediaService` only exposes the current playback session. A legacy `MediaBrowser`
  connect timed out in testing.
- Deep-linking a Live TV channel `ItemId` opens Jellyfin's details screen, not playback.

## Decision: no Jellyfin sign-in
By decision, the Jellyfin server Live TV source was removed. Slate TV never asks the user to sign in to Jellyfin.
- **Live TV & Guide** reads only Android TV System Live Channels (`READ_TV_LISTINGS`).
- **Jellyfin content** comes from the installed Jellyfin app's TvProvider rows (preview channels and Watch Next),
  shown as **App Channels** shelves. Opening an item uses the row's own `intent_uri`, re-checked against TvProvider
  at click time.
- Settings saved by older builds (server URL, encrypted API key, user id, pinned certificate, source mode, "favorites
  only") are deleted on the next load, along with the old AndroidKeyStore key.

### Considered, not shipped
These were built and then removed with the server source:
- A detector that read the server address from Jellyfin's ImageProvider poster URIs ("Use server from Jellyfin app").
- Quick Connect sign-in, including a QR code for jellyfin-web's `/web/#/quickconnect?code=…` approval form.
- LAN server discovery, phone pairing, manual API key entry, and TOFU certificate pinning for self-signed servers.

## Possible follow-ups
- Make the Jellyfin App Channels shelves the default suggestion when Jellyfin is installed and Live TV isn't configured.
- Jellyfin search shelf/dialog via the search provider, with a retry for cold start.
- Watch for Google's Watch Next deprecation (Engage SDK, 2H 2027) and whether Jellyfin keeps publishing preview channels on Google TV.

## Artwork
Jellyfin's ImageProvider serves the full-size poster (~2.9 MB, ~3 s per image on the SHIELD). That exceeds App
Channels' 2 MB artwork cap and 1.5 s shelf budget, so cards had no images. `downsizedJellyfinArtworkUri()` asks the
same provider for the same server image with `maxWidth=400&quality=80` (~120 KB, 0.1–0.5 s). The shelf also keeps the
artwork that resolves within the budget instead of discarding all of it on timeout. Verified on device: 80/80 Jellyfin
cards across 5 rows plus Watch Next had artwork, with no Jellyfin sign-in.
