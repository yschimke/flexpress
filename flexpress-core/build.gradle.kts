plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-test-fixtures`
  alias(libs.plugins.metalava)
  alias(libs.plugins.maven.publish)
}

description = "A variable-font reader and precomputed text outlines whose axes are expressions."

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

metalava { filename.set("api/current.api") }

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.kotlinx.serialization.json)
}
