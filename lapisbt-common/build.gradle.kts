import org.jetbrains.kotlin.gradle.dsl.*

plugins {
	alias(libs.plugins.androidLibrary)
	alias(libs.plugins.kotlinAndroid)
}

android {
	namespace = "com.elianfabian.lapisbt.common"
	compileSdk = 37

	defaultConfig {
		minSdk = 21

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		consumerProguardFiles("consumer-rules.pro")
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

			languageVersion.set(KotlinVersion.KOTLIN_2_0)
			apiVersion.set(KotlinVersion.KOTLIN_2_0)
		}
	}
}

dependencies {
	api(libs.kotlinxCoroutinesAndroid)
	implementation(libs.androidx.coreKtx)
}
