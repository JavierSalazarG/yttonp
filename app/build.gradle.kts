import java.util.Properties

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
        versionCode = 9
        versionName = "2.5"
    }

    // Clave privada, fuera del repositorio. En el PC: ~/.config/yttonp/keystore.properties
    // (storeFile, storePassword, keyAlias, keyPassword). En GitHub Actions: los Secrets YTTONP_KEYSTORE_B64
    // y YTTONP_KEYSTORE_PASSWORD (el flujo los vuelca a un fichero y rellena las variables YTTONP_*).
    // Sin clave, la versión release sale sin firmar: compila, pero no se instala.
    val firma = Properties()
    val ficheroFirma = file(System.getenv("YTTONP_KEYSTORE_PROPERTIES") ?: "${System.getProperty("user.home")}/.config/yttonp/keystore.properties")
    if (ficheroFirma.isFile) ficheroFirma.inputStream().use { entrada -> firma.load(entrada) }
    System.getenv("YTTONP_KEYSTORE")?.let { ruta -> firma.setProperty("storeFile", ruta) }
    System.getenv("YTTONP_KEYSTORE_PASSWORD")?.let { clave -> firma.setProperty("storePassword", clave); firma.setProperty("keyPassword", clave) }
    System.getenv("YTTONP_KEY_ALIAS")?.let { alias -> firma.setProperty("keyAlias", alias) }
    val rutaClave: String? = firma.getProperty("storeFile")
    val conFirma = rutaClave != null && file(rutaClave).isFile
    signingConfigs {
        if (conFirma) create("personal") {
            storeFile = file(rutaClave!!)
            storePassword = firma.getProperty("storePassword")
            keyAlias = firma.getProperty("keyAlias") ?: "yttonp"
            keyPassword = firma.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (conFirma) signingConfig = signingConfigs.getByName("personal")
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
