pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "flexpress"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// The font reader, the variation model and the precomputed outline: plain Kotlin.
include(":flexpress-core")

// Remote Compose: RemoteVariableFontText.
include(":flexpress-remote")

// Jetpack Compose UI: VariableFontText.
include(":flexpress-compose")

// Works an outline out at build time and writes it as Kotlin source.
include(":flexpress-codegen")
