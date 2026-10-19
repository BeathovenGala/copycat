import java.util.Properties

plugins {
    id("com.android.application")
}

val appVersion = rootProject.file("../VERSION").readText().trim()
val (major, minor, patch) = appVersion.split(".").map { it.toInt() }

// Kept outside the repo; losing this key means existing installs can't update.
val signingProps = Properties().apply {
    val path = System.getenv("COPYCAT_SIGNING") ?: "${System.getProperty("user.home")}/.config/copycat/signing.properties"
    file(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.copycat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.copycat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = major * 10000 + minor * 100 + patch
        versionName = appVersion
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
