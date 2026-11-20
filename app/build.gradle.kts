import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.play.publisher)
}

// Optional upload-key signing (Stasis State convention): read from untracked keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword), or -PkeystorePropertiesFile=/abs/path.
// Debug builds require no credentials.
val keystorePropertiesFile = providers.gradleProperty("keystorePropertiesFile").orNull?.let { rootProject.file(it) }
    ?: rootProject.file("keystore.properties")
val keystoreProps = keystorePropertiesFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
val signingFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val uploadStoreFile = keystoreProps?.getProperty("storeFile")?.takeIf { it.isNotBlank() }?.let { path ->
    File(path).let { if (it.isAbsolute) it else keystorePropertiesFile.absoluteFile.parentFile.resolve(path) }
}
val releaseSigningReady = keystoreProps != null &&
    signingFields.all { !keystoreProps.getProperty(it).isNullOrBlank() } &&
    uploadStoreFile?.isFile == true

android {
    namespace = "com.stasisstate.slatetv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stasisstate.slatetv"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0-beta.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                val creds = requireNotNull(keystoreProps)
                storeFile = uploadStoreFile
                storePassword = creds.getProperty("storePassword")
                keyAlias = creds.getProperty("keyAlias")
                keyPassword = creds.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by Google Play Billing 9.2+
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":connector-immich"))
    implementation(project(":connector-livetv"))
    implementation(project(":connector-extensions"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Optional, unrestricted Tip Jar (Google Play Billing)
    implementation(libs.android.billing)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Uploads through the Google Play Developer API with the Stasis State service account. The key path lives in
// ~/.gradle/gradle.properties (playServiceAccountKey=...). Track defaults to the dedicated Android TV closed
// testing track ("tv:Closed Testing - Beta"). Override with -PplayTrack=tv:qa (internal) or -PplayTrack=tv:production.
// See docs/play/publishing.md.
play {
    providers.gradleProperty("playServiceAccountKey").orNull?.let { serviceAccountCredentials.set(file(it)) }
    defaultToAppBundles.set(true)
    track.set(providers.gradleProperty("playTrack").orElse("tv:Closed Testing - Beta"))
    releaseStatus.set(
        providers.gradleProperty("playReleaseStatus")
            .map { com.github.triplet.gradle.androidpublisher.ReleaseStatus.valueOf(it.uppercase()) }
            .orElse(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED),
    )
}

// Listing-only publish tasks (publishReleaseListing) don't need upload-key signing.
val signedReleaseTasks = setOf("bundleRelease", "publishReleaseBundle", "publishReleaseApk", "publishReleaseApps")
tasks.matching { it.name in signedReleaseTasks }.configureEach {
    val ready = releaseSigningReady
    doFirst {
        if (!ready) {
            throw GradleException("Release signing requires keystore.properties with an existing upload key. See docs/play/signing.md.")
        }
    }
}
