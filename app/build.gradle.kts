plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wdgts"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "com.wdgts"
        minSdk = 33          // monochrome app icons, system dynamic colours
        targetSdk = 36
        // CI builds count upwards by themselves, so a newer APK always installs over an older one
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 100
        versionName = "0.4"
    }
    // One fixed key for every build, so each new APK installs over the previous one instead of needing an uninstall.
    // The keystore is committed on purpose (see README-HUD.md, "Signing"); override the password with the
    // WDGTS_STORE_PASSWORD gradle property or environment variable if you ever swap in a private key.
    signingConfigs {
        create("wdgts") {
            val pw = providers.gradleProperty("WDGTS_STORE_PASSWORD")
                .orElse(providers.environmentVariable("WDGTS_STORE_PASSWORD"))
                .orElse("wdgtsdebug").get()
            storeFile = file("signing/wdgts-debug.p12")
            storeType = "pkcs12"
            storePassword = pw
            keyAlias = "wdgts"
            keyPassword = pw
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("wdgts") }
        getByName("release") { signingConfig = signingConfigs.getByName("wdgts") }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    // Fallback only: if the root build still compiles with Kotlin 2.2 and you can't raise it, uncomment this so the
    // compiler accepts the Smartspacer SDK's Kotlin 2.4 metadata. Not needed once Kotlin is 2.4.0.
    // compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")   // PathParser for KanjiVG stroke paths
    // In-app UI (com.wdgts.ui): the same Compose versions as microtag and B-72.
    val compose = "1.13.0-beta02"
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.ui:ui-graphics:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.animation:animation:$compose")
    // MaterialShapes (M3 Expressive), motion schemes and dynamic colour. Also gives RoundedPolygon.toPath().
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    // Icons for the home tiles (same artifact and version as B-72). R8 drops the ones that are not used.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // Real M3 Expressive wavy progress indicators (Views), drawn offscreen onto widget bitmaps.
    // Dynamic on purpose: pin the newest 1.14.x/1.15 you see on Maven once it builds.
    implementation("com.google.android.material:material:1.14.+")
    // HUD: Smartspacer targets. The SDK is built with Kotlin 2.4, so the build's Kotlin must be 2.4.0 too.
    implementation("com.kieronquinn.smartspacer:sdk-client:1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // HUD steps from Health Connect (the optional source next to the phone's own step sensor)
    implementation("androidx.health.connect:connect-client:1.1.0")
    testImplementation("junit:junit:4.13.2") // only for the HUD logic test
    // ML Kit subject segmentation (via Google Play services) for the depth-effect music widget
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // reads the lettering printed on the cover, so the Depth widget can leave it alone (Play services delivers the model, like segmentation)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
