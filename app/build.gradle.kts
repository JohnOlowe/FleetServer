import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
}

// Signing credentials are never committed. They are read, in order, from:
//   1. keystore.properties in the project root (git-ignored, for local builds)
//   2. Gradle project properties, which includes ORG_GRADLE_PROJECT_* environment
//      variables - that is how GitHub Actions passes its secrets in
//   3. a plain environment variable of the same name
// Anything missing simply leaves the config unsigned, so the build never breaks.
val localKeystoreProps = Properties().apply {
  val file = rootProject.file("keystore.properties")
  if (file.exists()) {
    file.inputStream().use { load(it) }
  }
}

fun signingValue(name: String, fallback: String? = null): String? {
  // An unset GitHub Actions secret arrives as an empty string, not as nothing, and
  // an empty password is a broken signing config rather than a missing one.
  val value = localKeystoreProps.getProperty(name)
    ?: (project.findProperty(name) as? String)
    ?: System.getenv(name)
    ?: fallback
  return value?.takeIf { it.isNotBlank() }
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
  // The keystore itself lives in keystore/; the two passwords do not live in the
  // repo at all (see signingValue above).
  signingConfigs {
    create("stable") {
      val storePath = signingValue("FLEETSERVER_STORE_FILE", "keystore/damjay_debug.keystore")!!
      val keyStore = rootProject.file(storePath)
      if (keyStore.exists()) {
        storeFile = keyStore
        // JKS starts with 0xFEEDFEED; anything else produced by keytool or Android
        // Studio these days is PKCS12.
        val header = ByteArray(4)
        keyStore.inputStream().use { it.read(header) }
        storeType = if (header[0] == 0xFE.toByte() && header[1] == 0xED.toByte()) "jks" else "pkcs12"
        storePassword = signingValue("FLEETSERVER_STORE_PASSWORD")
        keyAlias = signingValue("FLEETSERVER_KEY_ALIAS", "photo-triage")
        keyPassword = signingValue("FLEETSERVER_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    // Debug builds are signed with the stable key. Release stays unsigned - the app is
    // distributed as a debug APK from CI.
    getByName("debug") {
      val stable = signingConfigs.getByName("stable")
      // Half a signing config is worse than none: without both passwords the build
      // would fail at signing time, so fall back to the standard Android debug key.
      if (stable.storeFile != null && !stable.storePassword.isNullOrBlank()
          && !stable.keyPassword.isNullOrBlank()
      ) {
        signingConfig = stable
      } else {
        logger.lifecycle("Stable signing key unavailable (keystore or passwords missing) - using the default debug key")
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
