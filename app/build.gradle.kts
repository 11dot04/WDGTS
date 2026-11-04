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
        versionCode = 1
        versionName = "0.1"
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
}
