import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stasisstate.slatetv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

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

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":plugin-immich"))

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
