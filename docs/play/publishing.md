# Publish Slate TV on Google Play (Closed Testing & Production)

Slate TV is published as `com.stasisstate.slatetv` under the Stasis State LLC developer account with
[Gradle Play Publisher](https://github.com/Triple-T/gradle-play-publisher) (configured in `app/build.gradle.kts` and `gradle/libs.versions.toml`).

The store listing (text, icon, feature graphic, TV banner, phone & TV screenshots) and release notes live in
`app/src/main/play/` in GPP's exact layout.

## Prerequisites

- Upload key and `keystore.properties`: see [signing.md](signing.md) (or run `scripts/setup-upload-key.sh`).
- `playServiceAccountKey=/absolute/path/key.json` in `~/.gradle/gradle.properties`. This is the shared Stasis State
  service account; never commit, print, or paste the key.
- In Play Console → **Users and permissions**, grant the service account access to the Slate TV app with:
  *View app information*, *Release apps to testing tracks*, *Manage testing tracks and edit tester lists*, and *Manage store presence*.
- **Privacy policy live first:** publish `slatetv-privacy.html` to `https://stasisstate.com/slatetv-privacy.html` before submitting (Google Play rejects dead URLs).

## One-time Play Console setup (manual: API cannot do initial creation)

1. **Create app**: Name **Slate TV: Minimal TV Launcher**, default language **English (United States)**, **App**, **Free**.
2. **Form factors (Android TV)**: Go to **Test and release → Advanced settings → Form factors**, add **Android TV**, and opt in for TV quality review.
3. **Store settings**: Category **Tools**, contact email `support@stasisstate.com`, website `https://stasisstate.com/slatetv.html`.
4. **First signed bundle by hand**:
   - Build `app/build/outputs/bundle/release/app-release.aab` with `./gradlew :app:bundleRelease`.
   - Go to **Test and release → Testing → Closed testing**, switch the form-factor selector at the top to **Android TV only**, create a release, upload `app-release.aab`, enroll in **Play App Signing**, and save.
   - Push the store listing text & graphics via API once the app exists:
     ```sh
     ./gradlew :app:publishReleaseListing
     ```
5. **Testers**: Add your Google Group or tester email list to the Android TV closed testing track and share the opt-in link. Add your test accounts under **Settings → License testing** so Tip Jar purchases can be tested without charging real money.
6. **Optional Tip Jar products** (after the first bundle is uploaded): Go to **Monetize with Play → Products → One-time products** and create & activate all five consumable products with **exact** IDs:

| Product ID | Display name | USD price |
| --- | --- | --- |
| `tip_1` | Small tip | $1.00 |
| `tip_3` | Coffee tip | $3.00 |
| `tip_5` | Generous tip | $5.00 |
| `tip_10` | Big tip | $10.00 |
| `tip_20` | Huge tip | $20.00 |

Description: `"An optional tip to support Slate TV development. It does not unlock any features."`

7. **Complete App content forms** (answers below), verify D-pad navigation, and click **Publishing overview → Send for review**.

## Subsequent uploads via Gradle Play Publisher

Slate TV is an **Android TV-only** app (`android.software.leanback` required), so all releases target Google Play's dedicated `tv:` form-factor tracks. Bump `versionCode` (and `versionName`, e.g., `1.0.0-beta.3`) in `app/build.gradle.kts` before every upload:

```sh
./gradlew :app:publishReleaseBundle :app:publishReleaseListing   # Android TV closed testing ("tv:alpha") + listing
./gradlew :app:publishReleaseListing                             # Store text & graphics only
./gradlew :app:publishReleaseBundle -PplayTrack=tv:qa            # Android TV internal testing
./gradlew :app:publishReleaseBundle -PplayTrack=tv:production    # Android TV production
./gradlew :app:publishReleaseBundle -PplayReleaseStatus=draft    # Draft upload
```

## App content answers

- **Privacy policy** — `https://stasisstate.com/slatetv-privacy.html`.
- **App access** — All core launcher features are usable without a login or special credentials.
- **Ads** — No.
- **Target audience** — 18 and over (not designed for children; "Appeals to children": No).
- **Government, financial, health, and news apps** — No.
- **Data safety** — Declare **Financial info → Purchase history** only. Mark it **collected**, **not shared**, **optional** (only users who voluntarily tip create an order), purpose **App functionality**, **encrypted in transit** (Google Play Billing uses HTTPS). Nothing else is collected by Stasis State LLC (no analytics, ads, crash SDKs, or installed-app inventory transmission). Optional user-configured connectors (self-hosted photo servers, media servers, JSON feeds) encrypt any user-supplied API keys on-device with Android Keystore and send requests directly to the user's chosen server only—never to Stasis State LLC. On-device TV listings (`READ_TV_LISTINGS`) are read locally and never leave the device. Deletion inquiries: `privacy@stasisstate.com` (order records are managed by Google Play).
- **IARC content rating** — Choose **Utility, Productivity, Communication, or Other**. Answer **No** for violence, sexual content, strong language, controlled substances, gambling, user interaction, and sharing personal information. Answer **Yes** to digital purchases (optional Tip Jar) and **No** to randomized purchases.

## Broad package visibility (`QUERY_ALL_PACKAGES`)

Submit the `QUERY_ALL_PACKAGES` declaration if Play Console prompts for it (≤ 500 chars; 414 chars below):

> Slate TV is an Android TV Home Launcher (CATEGORY_HOME). QUERY_ALL_PACKAGES is required for its core purpose: discovering installed TV and optional sideloaded apps so users can browse all installed apps in the All Apps drawer, pin, unpin, reorder, or hide apps on the Home dock, open App Info, and launch any installed app from the Home screen. Installed app data stays strictly on-device and is never transmitted.
