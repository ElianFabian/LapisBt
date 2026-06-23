import com.android.build.gradle.LibraryExtension

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
	alias(libs.plugins.kotlinAndroid) apply false
	alias(libs.plugins.kotlinParcelize) apply false
	//alias(libs.plugins.kotlinSerialization) apply false
	id("maven-publish")
}

subprojects {
	val libraryModules = listOf("lapisbt", "lapisbt-rpc", "lapisbt-logger")
	if (name in libraryModules) {
		val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
		group = libs.findVersion("lapisbtGroup").get().requiredVersion
		version = libs.findVersion("lapisbtVersion").get().requiredVersion

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
