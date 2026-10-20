import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from an untracked keystore.properties (storeFile, storePassword, keyAlias,
// keyPassword). Without it the release APK is left unsigned, which is what F-Droid builds expect.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "app.centauri.remote"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.centauri.remote"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "0.4.0"
    }

    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // ./gradlew createDebugUnitTestCoverageReport → app/build/reports/coverage/test/debug/
        debug { enableUnitTestCoverage = true }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Lists the translations for Android 13+'s per-app language setting.
    androidResources { generateLocaleConfig = true }
    // No Google-encrypted dependency metadata in the APK: F-Droid's scanner rejects it and it
    // breaks reproducible builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric reads R.string
        // Robolectric's native runtime reaches into FileDescriptor, closed by default on newer JDKs.
        unitTests.all {
            // Count code run inside Robolectric's sandbox classloader too.
            it.extensions.configure<JacocoTaskExtension> {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
