plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metalava)
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.compose.preview)
}

description = "Variable-font axis animation for Compose UI, without re-instancing a typeface."

android {
  namespace = "ee.schimke.flexpress.compose"
  compileSdk = 36

  defaultConfig { minSdk = 26 }

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
  api(libs.compose.runtime)
  api(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.text)
  implementation(libs.compose.ui.unit)

  debugImplementation(libs.compose.animation.core)
  debugImplementation(libs.compose.foundation)
  debugImplementation(libs.compose.foundation.layout)
  debugImplementation(libs.compose.ui.tooling.preview)
  debugImplementation(platform(libs.compose.preview.daemon.bom))
  debugImplementation(libs.compose.preview.annotations)

  testImplementation(testFixtures(projects.flexpressCore))
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.ext.ktx)
  testImplementation(libs.compose.ui.test.junit4)
  testImplementation(libs.compose.ui.test.manifest)
  testImplementation(libs.androidx.activity.compose)
}
