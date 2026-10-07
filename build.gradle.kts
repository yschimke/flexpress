import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.metalava) apply false
}

subprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")

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
