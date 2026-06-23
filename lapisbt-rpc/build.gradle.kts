import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
	alias(libs.plugins.androidLibrary)
	alias(libs.plugins.kotlinAndroid)
}

android {
	namespace = "com.elianfabian.lapisbt_rpc"
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
		debug {
			enableUnitTestCoverage = true
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
	testOptions {
		unitTests {
			isReturnDefaultValues = true
		}
	}
}

dependencies {
	api(project(":lapisbt"))

	implementation(libs.kotlinxCoroutinesAndroid)
	testImplementation(libs.junit)
	testImplementation(libs.truth)
	testImplementation(libs.kotlinxCoroutinesTest)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.espressoCore)
}
