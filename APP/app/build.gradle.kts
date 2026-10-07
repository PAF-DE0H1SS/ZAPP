import java.util.Properties

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
        versionCode = 3
        versionName = zappVersion

        // STL не отключаем. `ANDROID_STL=none` убирает libc++ из sysroot, и
        // тогда даже `<cstdint>` не находится: счётчик читает /proc через
        // std::string и std::vector, то есть без STL он не компилируется.
        // По умолчанию NDK берёт c++_static, что и нужно.
    }

    // Два билда с одинаковым applicationId и разными механизмами:
    //   root   -- текущее поведение (su, iptables, NFQUEUE, ядро WireGuard);
    //   noroot -- ни одной root-команды (RootShell принудительно Mode.NONE,
    //            демоны под uid приложения, tun/box вместо ядра и iptables).
    // Флаг ZAPP_ROOT из BuildConfig читает MainActivity и кладёт в
    // composeApp.BuildMode ДО первого кадра.
    flavorDimensions += "mode"
    productFlavors {
        create("root") {
            dimension = "mode"
            buildConfigField("boolean", "ZAPP_ROOT", "true")
        }
        create("noroot") {
            dimension = "mode"
            buildConfigField("boolean", "ZAPP_ROOT", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Подпись release-сборки. keystore и пароли живут в signing/ (в .gitignore,
    // в репозиторий не попадают): локально их создал владелец, в CI их
    // раскладывает шаг Prepare release keystore из GitHub Secrets. Без файла
    // signingConfigs остаётся пустым и сборка выходит неподписанной.
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("signing/keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile?.exists() == true }
        }
    }

    lint {
        // Приложение само выступает провайдером имитации местоположения
        // (GPS-сценарии). Чтобы оно появилось в списке «Приложение для
        // имитации местоположения» в настройках разработчика,
        // ACCESS_MOCK_LOCATION обязан быть объявлен и в release-манифесте,
        // а не только в debug -- правило MockLocation здесь не применимо.
        disable += "MockLocation"
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

// Имена APK по условиям задачи:
//   ZAPP-ROOT-<version>.apk / ZAPP-<version>.apk (release),
//   с суффиксом -debug для debug-сборок.
// Одинаковый applicationId -- установка одного поверх другого сохраняет данные.
androidComponents {
    onVariants(selector().withFlavor("mode" to "root")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "ZAPP-ROOT-$zappVersion${if (variant.name.endsWith("Debug")) "-debug" else ""}.apk"
            )
        }
    }
    onVariants(selector().withFlavor("mode" to "noroot")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "ZAPP-$zappVersion${if (variant.name.endsWith("Debug")) "-debug" else ""}.apk"
            )
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}