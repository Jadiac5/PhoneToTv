import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = "1.1.3"
val appVersionCode = 5

// Release signing: keystore.properties (storeFile, storePassword, keyAlias, keyPassword) in the repo root.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.phonestream.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonestream.app"
        minSdk = 26
        // Stay on 34: newer targets add the Android 17 local-network permission gate,
        // which this LAN-only app doesn't want to deal with.
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "PhoneStream-$appVersion-$name.apk"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The Android SDK jar only has stubs of org.json; plain JVM unit tests need the real thing.
    testImplementation("org.json:json:20231013")
}
