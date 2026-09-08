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
