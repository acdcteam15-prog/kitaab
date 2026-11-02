import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Kotlin is built into AGP 9. KSP is on the build classpath (see the root build file), so no version here.
    id("com.google.devtools.ksp")
    alias(libs.plugins.google.gms.google.services)
}

// ---- version: name from version.properties, code = offset + GitHub run number -------------------
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("VERSION_NAME", "1.0.0").trim()
val versionCodeOffset: Int = versionProps.getProperty("VERSION_CODE_OFFSET", "1000").trim().toInt()
val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.trim()?.toIntOrNull() ?: 0

// ---- release signing: environment first (GitHub Actions), then keystore.properties (local) -----
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val keystorePath = signingValue("KEYSTORE_FILE", "storeFile")
val keystorePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val signingKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
val signingKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = keystorePath != null && rootProject.file(keystorePath).exists() &&
    keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null

// "owner/repo" of the GitHub repository that hosts the releases (set by GitHub Actions).
val updateRepo: String = System.getenv("GITHUB_REPOSITORY")?.trim()
    ?: versionProps.getProperty("UPDATE_REPO", "").trim()

android {
    namespace = "com.pdfreader.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pdfreader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOffset + runNumber
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        // The signed release build must not fail because of a new lint opinion; lint still runs on demand.
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        // Lets JVM unit tests touch Android types (e.g. data classes) without crashing
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // Firebase — Realtime Database for instant cross-device sync notifications
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.google.api.client.android) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.api.services.drive) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.http.client.gson) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.oauth.client)
    implementation(libs.google.api.client)
    // JSON for the in-app updater (its parser is pure Kotlin and unit-tested on the JVM)
    implementation(libs.gson)

    implementation(libs.android.pdf.viewer)

    // Page editing before upload (delete / reorder pages, rewrite the PDF)
    implementation(libs.pdfbox.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.glide)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)

    // Installs the baseline profile (src/main/baseline-prof.txt) → faster cold start & scrolling
    implementation(libs.androidx.profileinstaller)

    // Unit tests: ./gradlew testDebugUnitTest
    testImplementation(libs.junit)
}
