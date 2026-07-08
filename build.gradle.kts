import com.android.build.api.dsl.LibraryExtension

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
	repositories {
		google()
		mavenCentral()
		maven { setUrl("https://jitpack.io") }
	}
}

plugins {
	alias(libs.plugins.androidApplication) apply false
	alias(libs.plugins.androidLibrary) apply false
	alias(libs.plugins.kotlinParcelize) apply false
	//alias(libs.plugins.kotlinSerialization) apply false
	id("maven-publish")
}

subprojects {
	val libraryModules = listOf("lapisbt", "lapisbt-rpc", "lapisbt-logger")
	if (name in libraryModules) {
		val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
		group = libs.findVersion("lapisbtGroup").get().requiredVersion

		// If JitPack injects a version from the Git tag, use it. Otherwise, fall back to the TOML version for local builds.
		val tomlVersion = libs.findVersion("lapisbtVersion").get().requiredVersion
		version = if (project.version == "unspecified") tomlVersion else project.version

		plugins.withId("com.android.library") {
			plugins.apply("maven-publish")
			configure<LibraryExtension> {
				publishing {
					singleVariant("release") {
						withSourcesJar()
					}
				}
			}
			afterEvaluate {
				extensions.configure<PublishingExtension> {
					publications {
						create<MavenPublication>("release") {
							from(components.findByName("release") ?: components["release"])

							groupId = project.group.toString()
							artifactId = project.name
							version = project.version.toString()
						}
					}
				}
			}
		}
	}
}
