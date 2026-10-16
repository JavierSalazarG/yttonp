plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.javi.yt2newpipe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.javi.yt2newpipe"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.1"
    }

    // Clave fija (uso personal) para que cada versión nueva se instale encima de la anterior.
    signingConfigs {
        create("personal") {
            storeFile = file("yt2np.jks")
            storePassword = "yt2newpipe"
            keyAlias = "yt2np"
            keyPassword = "yt2newpipe"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Sin dependencias externas: solo el SDK de Android.
