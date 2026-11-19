plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.stasisstate.slatetv.plugin.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stasisstate.slatetv.plugin.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.androidx.core.ktx)
}
