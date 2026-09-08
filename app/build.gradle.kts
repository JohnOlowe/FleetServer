plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "damjay.tracker.fleetserver"
  compileSdk = 36
  buildToolsVersion = "36.0.0"

  defaultConfig {
    applicationId = "damjay.tracker.fleetserver"
    minSdk = 26
    targetSdk = 36
    versionCode = 2
    versionName = "1.1"
  }

  // Pin every build to one keystore so the APK signature never changes between
  // builds - an APK from CI then upgrades cleanly over one built in Android Studio.
  // Credentials live in gradle.properties; the keystore itself is in keystore/.
  signingConfigs {
    create("stable") {
      val storePath = (project.findProperty("FLEETSERVER_STORE_FILE") as? String)
        ?: "keystore/damjay_debug.keystore"
      val keyStore = rootProject.file(storePath)
      if (keyStore.exists()) {
        storeFile = keyStore
        // JKS starts with 0xFEEDFEED; anything else produced by keytool or Android
        // Studio these days is PKCS12.
        val header = ByteArray(4)
        keyStore.inputStream().use { it.read(header) }
        storeType = if (header[0] == 0xFE.toByte() && header[1] == 0xED.toByte()) "jks" else "pkcs12"
        storePassword = project.findProperty("FLEETSERVER_STORE_PASSWORD") as? String
        keyAlias = project.findProperty("FLEETSERVER_KEY_ALIAS") as? String
        keyPassword = project.findProperty("FLEETSERVER_KEY_PASSWORD") as? String
      }
    }
  }

  buildTypes {
    // Debug builds are signed with the stable key. Release stays unsigned - the app is
    // distributed as a debug APK from CI.
    getByName("debug") {
      val stable = signingConfigs.getByName("stable")
      if (stable.storeFile != null) {
        signingConfig = stable
      }
    }
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
}

dependencies {
  implementation(libs.androidx.core)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.recyclerview)
  implementation(libs.osmdroid.android)
}
