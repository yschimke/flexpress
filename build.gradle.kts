import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.metalava) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.compose.preview) apply false
}

/**
 * The version every module publishes at: the release tag's on a release (`PLUGIN_VERSION`, set by
 * `.github/workflows/release.yml`), otherwise the next patch after the last release, as a snapshot.
 */
val publishedVersion: String =
  providers.environmentVariable("PLUGIN_VERSION").orNull?.takeIf { it.isNotBlank() }?.removePrefix("v")
    ?: run {
      val manifest = file(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

subprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")

  group = "ee.schimke.flexpress"
  version = publishedVersion

  // Every module publishes to Maven Central under its own name; see `configureFlexpressPublishing`.
  pluginManager.withPlugin("com.vanniktech.maven.publish") { configureFlexpressPublishing() }

  configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
    googleStyle()
    trailingCommaManagementStrategy.set(
      com.ncorti.ktfmt.gradle.TrailingCommaManagementStrategy.COMPLETE
    )
  }

  tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
      // The modules share core's internals; see InternalFlexpressApi.
      freeCompilerArgs.add("-opt-in=ee.schimke.flexpress.InternalFlexpressApi")
    }
  }
}

/**
 * The coordinates, signing and POM every published module carries. An Android library publishes its
 * `release` variant with sources and an empty javadoc jar (Central requires one, not a useful one).
 * Core's test fixtures are for this repository's tests and are not published.
 */
@Suppress("DEPRECATION") // AndroidSingleVariantLibrary(javadocJar, sourcesJar, variant)
fun Project.configureFlexpressPublishing() {
  configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    if (!version.toString().endsWith("SNAPSHOT")) signAllPublications()
    coordinates("ee.schimke.flexpress", project.name, version.toString())
    pluginManager.withPlugin("com.android.library") {
      configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
          javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
          sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
          variant = "release",
        )
      )
    }
    pom {
      name.set(project.name)
      description.set(project.description)
      url.set("https://github.com/yschimke/flexpress")
      inceptionYear.set("2026")
      licenses {
        license {
          name.set("The Apache License, Version 2.0")
          url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
          distribution.set("repo")
        }
      }
      developers {
        developer {
          id.set("yschimke")
          name.set("Yuri Schimke")
          url.set("https://github.com/yschimke")
        }
      }
      scm {
        url.set("https://github.com/yschimke/flexpress")
        connection.set("scm:git:https://github.com/yschimke/flexpress.git")
        developerConnection.set("scm:git:ssh://git@github.com/yschimke/flexpress.git")
      }
    }
  }
  pluginManager.withPlugin("java-test-fixtures") {
    val java = components["java"] as AdhocComponentWithVariants
    // After evaluation: the plugin adds the fixtures' sources variant late.
    afterEvaluate {
      listOf("Api", "Runtime", "Sources")
        .mapNotNull { configurations.findByName("testFixtures${it}Elements") }
        .forEach { java.withVariantsFromConfiguration(it) { skip() } }
    }
  }
}
