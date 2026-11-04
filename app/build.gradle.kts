plugins {
    id("com.android.application")
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
        versionCode = 4
        versionName = "0.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")   // PathParser for KanjiVG stroke paths
    // MaterialShapes (M3 Expressive) + RoundedPolygon.toPath(). Bump to the newest 1.5.x if this alpha is gone.
    implementation("androidx.compose.material3:material3:1.5.0-alpha26")
    // Real M3 Expressive wavy progress indicators (Views), drawn offscreen onto widget bitmaps.
    // Dynamic on purpose: pin the newest 1.14.x/1.15 you see on Maven once it builds.
    implementation("com.google.android.material:material:1.14.+")
    // ML Kit subject segmentation (via Google Play services) for the depth-effect music widget
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
}
