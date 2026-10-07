plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metalava)
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.compose.preview)
}

description =
  "Variable-font axis animation for Remote Compose, without loading a font on the player."

android {
  namespace = "ee.schimke.flexpress.remote"
  compileSdk = 36

  // remote-creation-compose and remote-player-compose require 29.
  defaultConfig { minSdk = 29 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures { compose = true }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// The test fonts, shared with the other modules, as debug resources for the previews.
androidComponents {
  onVariants(selector().withBuildType("debug")) { variant ->
    variant.sources.res?.addStaticSourceDirectory(rootProject.file("fonts/res").path)
  }
}

metalava {
  excludedSourceSets.setFrom("src/debug/kotlin")
  filename.set("api/current.api")
}

dependencies {
  api(projects.flexpressCore)
  implementation(platform(libs.compose.bom))
  api(libs.compose.remote.creation)
  api(libs.compose.remote.creation.compose)
  implementation(libs.compose.runtime)
  implementation(libs.compose.ui)

  debugImplementation(libs.compose.animation.core)
  debugImplementation(libs.compose.foundation)
  debugImplementation(libs.compose.foundation.layout)
  debugImplementation(libs.compose.remote.player.core)
  debugImplementation(libs.compose.remote.player.compose)
  debugImplementation(libs.compose.remote.player.view)
  debugImplementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.preview.annotations)

  testImplementation(testFixtures(projects.flexpressCore))
  testImplementation(projects.flexpressCodegen)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.ext.ktx)
  testImplementation(libs.compose.ui.test.junit4)
  testImplementation(libs.compose.ui.test.manifest)
  testImplementation(libs.androidx.activity.compose)
}
