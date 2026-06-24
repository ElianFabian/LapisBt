import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
	alias(libs.plugins.androidLibrary)
}

android {
	namespace = "com.elianfabian.lapisbt.logger"
	compileSdk = libs.versions.lapisCompileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.lapisMinSdk.get().toInt()

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	kotlin {
		explicitApi()
		compilerOptions {
			jvmTarget = JvmTarget.JVM_11
		}
	}
}

dependencies {

}
