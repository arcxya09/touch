plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}
android {
    namespace = "com.arcxya09.touch.benchmark.tests"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TARGET_PACKAGE", "\"com.arcxya09.touch\"")
    }
    targetProjectPath = ":app"
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += "release"
            buildConfigField("String", "TARGET_PACKAGE", "\"com.arcxya09.touch.benchmark\"")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
baselineProfile { useConnectedDevices = true }
