plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val zappVersion: String by project

android {
    namespace = "xyz.azraellab.zapp"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "xyz.azraellab.zapp"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = zappVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}