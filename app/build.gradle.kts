plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ksaye.mygps"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ksaye.mygps"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

    }

    signingConfigs {
        create("release") {
            val ks = project.findProperty("mygpsKeystore") as String?
            if (ks != null) {
                storeFile = file(ks)
                storePassword = project.findProperty("mygpsKeystorePassword") as String?
                keyAlias = project.findProperty("mygpsKeyAlias") as String? ?: "mygps"
                keyPassword = project.findProperty("mygpsKeyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (project.hasProperty("mygpsKeystore")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // world.map is read with openFd(), which needs it stored uncompressed.
        noCompress += "map"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // OpenStreetMap tiles; no Google Maps API key needed.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Offline vector maps (.map files from download.mapsforge.org), rendered on the phone.
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")
    // Android Auto (navigation category: the only one allowed to draw a map).
    implementation("androidx.car.app:app:1.7.0")
}
