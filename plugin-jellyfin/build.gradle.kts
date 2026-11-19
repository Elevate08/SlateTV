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
    namespace = "com.stasisstate.slatetv.plugin.jellyfin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stasisstate.slatetv.plugin.jellyfin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        val pinnedHostSha256 = providers.gradleProperty("trustedSlateCertSha256").orNull.orEmpty()
            .replace(":", "")
            .trim()
            .lowercase()
        buildConfigField("String", "TRUSTED_SLATE_CERT_SHA256", "\"$pinnedHostSha256\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
}
