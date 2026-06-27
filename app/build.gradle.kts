plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linkcast.receiver"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.linkcast.receiver"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            // Ship non-debuggable so ART does NOT force CheckJNI. The reused native
            // lib makes JNI calls that strict CheckJNI flags (CheckMethodID crash);
            // the original app runs release (CheckJNI off).
            isDebuggable = false
        }
    }

}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

