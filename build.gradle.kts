plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Version is injected by CI (-PversionName=1.0.3 -PversionCode=42); sane defaults for local builds.
val appVersionName: String = (project.findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Release signing comes ONLY from environment variables (GitHub Secrets in CI). Nothing is committed.
val signingStoreFile: String? = System.getenv("PAC_KEYSTORE_FILE")
val signingStorePassword: String? = System.getenv("PAC_KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("PAC_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("PAC_KEY_PASSWORD")
val hasReleaseSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword)
    .all { !it.isNullOrBlank() } && file(signingStoreFile ?: "missing").exists()

android {
    namespace = "com.personal.aicomputer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.personal.aicomputer"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("boolean", "RELEASE_SIGNED", hasReleaseSigning.toString())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 is intentionally off for v1: the app has not been device-tested with shrinking yet.
            // proguard-rules.pro is ready for when it is turned on.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Needed so that an optional libproot.so placed under src/main/jniLibs is extracted to
            // nativeLibraryDir, the only location from which Android 10+ lets an app execute binaries.
            useLegacyPackaging = true
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        // Report-only for v1; CI uploads the lint report. Turn on abortOnError once the baseline is clean.
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
