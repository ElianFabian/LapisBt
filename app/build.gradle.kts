plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "com.elianfabian.lapisbt"
	compileSdk = 36

	defaultConfig {
		applicationId = "com.elianfabian.lapisbt"
		minSdk = 21
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
	kotlinOptions {
		jvmTarget = "11"
	}
	buildFeatures {
		compose = true
	}
}

dependencies {

	implementation(project(":lapisbt"))
	implementation(libs.androidx.coreKtx)
	implementation(libs.androidx.lifecycleRuntimeKtx)
	implementation(libs.androidx.activityCompose)
	implementation(platform(libs.androidx.composeBom))
	implementation(libs.androidx.ui)
	implementation(libs.androidx.ui.graphics)
	implementation(libs.androidx.ui.toolingPreview)
	implementation(libs.androidx.material3)
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.espressoCore)
	androidTestImplementation(platform(libs.androidx.composeBom))
	androidTestImplementation(libs.androidx.ui.testJunit4)
	debugImplementation(libs.androidx.ui.tooling)
	debugImplementation(libs.androidx.ui.testManifest)
}
