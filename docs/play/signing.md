# Slate TV upload key

Slate TV (`com.stasisstate.slatetv`) uses a dedicated Stasis State LLC upload key. Enroll in Google Play App Signing and use the upload key to sign the bundles you submit. Play manages the app signing key used for installs. [Android app signing](https://developer.android.com/studio/publish/app-signing)

Create the upload keystore once in a private directory with mode `700`. Use the existing Slate TV upload key for subsequent releases. Run `keytool` from a JDK and enter the password at its prompt.

```sh
umask 077
keytool -genkeypair -alias upload -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12 -keystore /absolute/private/path/slatetv-upload.p12
```

Keep an encrypted backup on separate durable storage and store the password in the Stasis State password manager. Create `keystore.properties` in the repository root with mode `600`, or place it in a private directory and pass `-PkeystorePropertiesFile=/absolute/path/keystore.properties`. Keep the keystore, properties and passwords out of version control.

```properties
storeFile=/absolute/private/path/slatetv-upload.p12
storePassword=<keystore password>
keyAlias=upload
keyPassword=<same password for PKCS12>
```

Debug builds use the default Android debug signing configuration. For release, supply all four fields and confirm the keystore exists before running `./gradlew :app:bundleRelease`. Compare the SHA-256 upload certificate fingerprint from `keytool -list -v -keystore /absolute/private/path/slatetv-upload.p12 -alias upload` to **Test and release → App integrity** before subsequent uploads. If you lose the upload key, request a reset through Play Console.
