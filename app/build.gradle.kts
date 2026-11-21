plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.catatkeuangan"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.catatkeuangan"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Kunci tanda tangan tetap: signing/release.keystore.enc dibuka di GitHub Actions memakai
    // Secret CK_SIGNING_PASS. Tanpa Secret, APK ditandatangani kunci debug sementara.
    val signPass: String? = System.getenv("CK_SIGNING_PASS")?.takeIf { it.isNotBlank() }
    val signFile: String? = System.getenv("CK_KEYSTORE_FILE")
    signingConfigs {
        create("fixed") {
            storeFile = file(signFile ?: "missing.keystore")
            storePassword = signPass
            keyAlias = "catatkeuangan"
            keyPassword = signPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signPass != null && signFile != null) signingConfigs.getByName("fixed")
            else signingConfigs.getByName("debug")
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
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
