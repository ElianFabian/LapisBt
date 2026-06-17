import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.androidLibrary)
	alias(libs.plugins.kotlinAndroid)
}

android {
	namespace = "com.elianfabian.lapisbt_rpc_benchmarks"
	compileSdk = 37

	defaultConfig {
		minSdk = 21
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}

	kotlin {
		compilerOptions {
			jvmTarget = JvmTarget.JVM_11
		}
	}

	testOptions {
		unitTests.isReturnDefaultValues = true
	}
}

dependencies {
	implementation(project(":lapisbt-rpc"))
	implementation(libs.kotlinxCoroutinesAndroid)
	
	testImplementation(libs.junit)
	testImplementation(libs.truth)
	testImplementation(libs.kotlinxCoroutinesTest)
}
