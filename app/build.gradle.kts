plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lookup.browser"
    // LP3 ships with Android 14 (API 34). minSdk 26 is conservative;
    // if you only ever install on the LP3 you could raise this to 34.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lookup.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // "release" signing config — fill in your keystore details before building a signed APK.
        // Android Studio: Build > Generate Signed Bundle / APK will prompt for these.
        // create("release") {
        //     storeFile     = file("path/to/your.keystore")
        //     storePassword = "yourStorePassword"
        //     keyAlias      = "yourKeyAlias"
        //     keyPassword   = "yourKeyPassword"
        // }
    }

    buildTypes {
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // AppCompat brings in the Activity library (OnBackPressedCallback, LifecycleOwner)
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Barcode scan: the camera through CameraX, the code read by ZXing (pure Java, no Google services needed).
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
