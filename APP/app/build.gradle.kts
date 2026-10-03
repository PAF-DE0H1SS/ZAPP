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

        // STL не отключаем. `ANDROID_STL=none` убирает libc++ из sysroot, и
        // тогда даже `<cstdint>` не находится: счётчик читает /proc через
        // std::string и std::vector, то есть без STL он не компилируется.
        // По умолчанию NDK берёт c++_static, что и нужно.
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

    packaging {
        jniLibs {
            // Нативные бинари (jniLibs) должны лежать на диске обычными
            // файлами: SELinux разрешает исполнять только их, а не файлы
            // из app data. При false система оставила бы их внутри APK и
            // путь для запуска был бы недоступен.
            useLegacyPackaging = true
        }
    }

    // Нативный счётчик трафика на C++.
    //
    // Собирается именно здесь, а не в :composeApp: у KMP-расширения
    // `android { }` из AGP 9.2.1 вообще нет поддержки native-сборки, метода
    // `androidNative` не существует. В :app обычный com.android.application,
    // поэтому externalNativeBuild доступен.
    //
    // Kotlin-объявления external-функций лежат в :composeApp androidMain; это
    // нормально: символ JNI привязывается к имени класса в JVM, а не к
    // Gradle-модулю, поэтому .so из :app находится и для кода из :composeApp.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}