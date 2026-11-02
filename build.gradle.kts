// AGP 9 has Kotlin built in, so there is no kotlin-android plugin. To use a newer Kotlin / KSP than the
// ones bundled with AGP, both are put on the build classpath here (the documented way). The version
// catalog cannot be used inside this block, so the two versions are written out. Dependabot updates them.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.13")
    }
}

plugins {
    alias(libs.plugins.android.application)        apply false
    alias(libs.plugins.google.gms.google.services) apply false
}
