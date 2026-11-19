buildscript {
    dependencies {
        // AGP 9 bundles built-in Kotlin with a KGP 2.2.10 floor; pin the newer compiler explicitly.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.play.publisher) apply false
}
