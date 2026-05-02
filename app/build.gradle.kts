import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.androidApplication)
	alias(libs.plugins.kotlinAndroid)
	alias(libs.plugins.kotlinCompose)
	alias(libs.plugins.kotlinParcelize)
}

android {
	namespace = "com.elianfabian.lapisbt.app"
	compileSdk = 36

	defaultConfig {
		applicationId = "com.elianfabian.lapisbt.app"
		minSdk = 23
		targetSdk = 36
		versionCode = 1
		versionName = "1.0"

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
		compilerOptions {
			jvmTarget = JvmTarget.JVM_11
		}
	}
	buildFeatures {
		compose = true
		buildConfig = true
	}
	lint {
		disable += "ConstPropertyName"
	}
}

dependencies {

	implementation(project(":lapisbt"))
	implementation(project(":lapisbt-rpc"))

	implementation(libs.kotlinxCoroutinesAndroid)
	implementation(libs.simpleStack)
	implementation(libs.simpleStackExtensions)
	implementation(libs.simpleStackCompose)
	implementation(libs.flowCombineTuple)

	implementation(libs.androidx.coreKtx)
	implementation(libs.androidx.lifecycleRuntimeKtx)
	implementation(libs.androidx.lifecycleProcess)
	implementation(libs.androidx.lifecycleRuntimeCompose)
	implementation(libs.androidx.activityCompose)
	implementation(platform(libs.androidx.composeBom))
	implementation(libs.androidx.ui)
	implementation(libs.androidx.ui.graphics)
	implementation(libs.androidx.ui.toolingPreview)
	implementation(libs.androidx.material3)
	implementation(libs.androidx.composeMaterialIconsExtended)
	implementation(libs.googlePlayServicesLocation)

//	testImplementation(libs.junit)
//	androidTestImplementation(libs.androidx.junit)
//	androidTestImplementation(libs.androidx.espressoCore)
	androidTestImplementation(platform(libs.androidx.composeBom))
	androidTestImplementation(libs.androidx.ui.testJunit4)
	debugImplementation(libs.androidx.ui.tooling)
	debugImplementation(libs.androidx.ui.testManifest)
}
