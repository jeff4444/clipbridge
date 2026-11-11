import java.util.Properties

plugins {
    id("com.android.application")
}

// One version for both apps: the VERSION file at the top of the project ("1.2.3" -> code 10203).
val appVersion: String = rootProject.file("../VERSION").readText().trim()
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }.let { it[0] * 10000 + it[1] * 100 + it[2] }

// Release signing key. It lives outside the project so it can never be committed by accident:
// ~/.clipbridge-signing/keystore.properties (written by scripts/new-android-key.sh), or wherever
// CLIPBRIDGE_KEYSTORE_PROPERTIES points. Without it, release builds fall back to the Android debug
// key: fine on your own phone, not for handing to other people.
val keystorePropsFile = file(
    System.getenv("CLIPBRIDGE_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.clipbridge-signing/keystore.properties"
)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).isFile } == true

android {
    namespace = "dev.clipbridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.clipbridge"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: keystoreProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    // Third-party notices and licence texts, shown by NoticesActivity.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("../legal"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false  // store .so uncompressed and 16 KB page-aligned
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/*.version")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // On-device ADB: pairs with this phone's own adbd over Wireless Debugging (SPAKE2/TLS) and
    // opens a shell, so the app can launch the background helper with no computer attached.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // X.509 self-signed cert generation (sun.security.* shims) for the adb key pair.
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    // Bundled Conscrypt: TLS 1.3 for adb pairing without reaching into hidden platform classes,
    // so it behaves the same across OEM builds.
    implementation("org.conscrypt:conscrypt-android:2.9.0")
    // Fallback if the library still reaches for the platform Conscrypt on some build.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.annotation:annotation:1.9.1")
    // NotificationCompat + RemoteInput for the pairing-code reply notification.
    implementation("androidx.core:core:1.13.1")
    // QR scanning for the Mac pairing code (self-contained scanner activity).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
