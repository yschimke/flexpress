plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.maven.publish)
}

description = "Generates a variable-font text outline as Kotlin source at build time."

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies { implementation(projects.flexpressCore) }
