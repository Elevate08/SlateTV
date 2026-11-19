# Publish Slate TV on Google Play

## First release

- [ ] In the Stasis State LLC Play Console account, create **Slate TV: Minimal TV Launcher** as a free app, default language English (United States). Confirm the first bundle registers `com.stasisstate.slatetv`.
- [ ] Set the category to **Tools**, support email to `support@stasisstate.com` and website to `https://stasisstate.com`. Confirm the public privacy policy is reachable at `https://stasisstate.com/slatetv-privacy.html`.
- [ ] Fill in the main store listing with the English text, icon, feature graphic, TV banner and TV screenshots from `app/src/main/play/`. The files follow the Gradle Play Publisher layout. The current Gradle build has no publisher plugin configured, so upload the listing and bundles in Play Console.
- [ ] Go to **Test and release → Advanced settings → Form factors** and add Android TV. Upload TV screenshots for all listings, return to **Manage** for Android TV, select the dedicated release track and opt in for TV quality review. [Android TV release tracks](https://support.google.com/googleplay/android-developer/answer/13295490)
- [ ] Configure the upload key using [signing.md](signing.md). Run `./gradlew :app:bundleRelease` with the Android SDK and JDK configured. Upload `app/build/outputs/bundle/release/app-release.aab` to the Android TV testing track, enroll in Play App Signing and paste the release notes from `app/src/main/play/release-notes/en-US/default.txt`. Upload the launcher bundle for this listing. The separately installed plugins have their own package IDs.
- [ ] Add testers and share the opt-in link. Apply any testing requirements shown for the developer account. Set up license testers for billing and verify that all five tips can be purchased again after consumption and that tipping does not change feature access.
- [ ] Complete the App content forms below, check the TV device catalog and verify D-pad navigation on a TV. Use **Publishing overview → Send for review**. After approval and testing, promote the release on the Android TV production track. Increment `versionCode` for each subsequent bundle.

## Optional Tip Jar

Set up the developer payments profile. After uploading the billing-enabled bundle, go to **Monetize with Play → Products → One-time products**. Create and activate a buy option for each ID, set prices and availability in the target countries, and configure each as a repeatable consumable purchase. The app consumes successful tips via Play Billing.

| Product ID | Display name | USD price |
| --- | --- | --- |
| `tip_1` | Small tip | $1.00 |
| `tip_3` | Coffee tip | $3.00 |
| `tip_5` | Generous tip | $5.00 |
| `tip_10` | Big tip | $10.00 |
| `tip_20` | Huge tip | $20.00 |

Use “An optional tip to support Slate TV development. It does not unlock any features.” as the product description.

## App content

- **Privacy policy** — `https://stasisstate.com/slatetv-privacy.html`.
- **App access** — The launcher is usable without a login. Optional companion plugins use the user's own Immich or Jellyfin server. Document that setup in review notes when showing the integrations.
- **Ads** — No. **Target audience** — 18 and over. **Government, financial, health and news app declarations** — No, where prompted.
- **Data safety** — Declare **Financial info → Purchase history** only. Mark it collected, optional, used for app functionality and not shared. Play Billing handles tip purchases over encrypted connections. The launcher does not collect payment card details, analytics, crash reports or the installed-app inventory. Plugin credentials and server traffic belong to user-configured companion apps and are not sent to Stasis State LLC. Verify the privacy policy and actual deletion process before completing the deletion questions. [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469)
- **IARC content rating** — Choose **Utility, Productivity, Communication, or Other**. Answer No for violence, sexual content, strong language, controlled substances, gambling, interaction between users and sharing personal information. Answer Yes for digital purchases because of the Tip Jar and No for randomized purchases. Where asked about access to online content, describe the optional plugins and user-supplied servers, including live programming that can vary. Use the rating the questionnaire assigns. [Content ratings](https://support.google.com/googleplay/android-developer/answer/9859655)

## Broad package visibility

Submit the `QUERY_ALL_PACKAGES` declaration if Play Console flags it for the uploaded bundle. Describe the core launcher function and show the All Apps grid, pinning, and launching apps with a TV remote.

> Slate TV replaces the Android TV home screen. Its core function is to let users find, pin and launch installed apps. The launcher lists TV apps and can include sideloaded apps with standard launcher entries. The installed apps vary by device and user, so a fixed package allowlist would exclude apps that users expect to find. Package visibility is used on-device for app discovery and launch. The app inventory is never transmitted or used for advertising or analytics.

Test whether intent-scoped package visibility covers all supported app discovery flows before submitting a bundle that requests broad visibility. Google Play requires a justification for why narrower visibility fails on supported devices. [Package visibility policy](https://support.google.com/googleplay/android-developer/answer/10158779)
