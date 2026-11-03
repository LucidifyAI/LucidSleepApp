import java.security.MessageDigest
import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.lucidify.lucidsleep"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lucidify.lucidsleep"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.play.services.code.scanner)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
}

// Puts the debug APK on the Lucidify website for testers. Runs after every assembleDebug
// (including DevBridge's build button), or on its own: gradlew.bat publishApkToSite.
// Then deploy Firebase Hosting as usual; it uploads the APK only when it has changed.
// Copies it to <site>/downloads/lucidsleep.apk, with lucidsleep.json (version, size, SHA-256)
// beside it; the setup card and lucidsleep-app.html read that file to show the install button.
// The site folder defaults to the CloudServices checkout next to this one; override it with
// -PlucidsleepSiteDir=<path to the public folder>. -PlucidsleepPublish=false skips the copy.
// On a computer without the site folder, it skips the copy with a warning.
val lucidsleepSiteDir: String = (findProperty("lucidsleepSiteDir") as String?)
    ?: rootProject.file("../../CloudServices/GCR/extern/extern/public").path
val apkVersionName: String = android.defaultConfig.versionName ?: "0"
val apkVersionCode: Int = android.defaultConfig.versionCode ?: 0
val publishAfterDebugBuild: Boolean = (findProperty("lucidsleepPublish") as String?)?.toBoolean() ?: true

tasks.register("publishApkToSite") {
    group = "distribution"
    description = "Copies the debug APK and its version file into the public website's downloads folder."
    dependsOn("assembleDebug")
    val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val site = File(lucidsleepSiteDir)
    doLast {
        if (!site.isDirectory) {
            logger.warn("publishApkToSite: website folder not found, skipped: $site (set -PlucidsleepSiteDir=...)")
            return@doLast
        }
        val apk = apkFile.get().asFile
        val out = File(site, "downloads").apply { mkdirs() }
        val target = File(out, "lucidsleep.apk")
        apk.copyTo(target, overwrite = true)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(out, "lucidsleep.json").writeText(
            "{\"versionName\":\"$apkVersionName\",\"versionCode\":$apkVersionCode," +
                "\"builtAt\":\"${Instant.now()}\",\"bytes\":${target.length()},\"sha256\":\"$sha256\"}\n"
        )
        println("Published ${target.length() / 1_048_576} MB to $target")
    }
}

// Every debug build also refreshes the website's copy of the APK.
if (publishAfterDebugBuild) {
    tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy("publishApkToSite") }
}