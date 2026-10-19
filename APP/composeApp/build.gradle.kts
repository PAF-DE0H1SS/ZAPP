import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// --- Натив: Rust-статика + десктопный JNI-мост -------------------------
//
// Три языка в одном бинаре: rust-ядро (сверка правил firewall) линкуется
// в C++-мост (zapp_jni.cpp, тот же, что и на Android), его .so кладётся
// в ресурсы desktopMain -- ZappNative достаёт его с classpath и грузит
// System.load. Android собирает те же исходники своей CMake-задачей; здесь --
// ванильный g++, потому что KMP-расширение native-сборки не умеет.
// Обёртка nix-shell нужна только на NixOS: голый g++ из профиля не видит
// glibc. На обычном Linux (CI ubuntu) компилируется напрямую, на других
// ОС натив пропускается целиком -- ZappNative уходит в Kotlin-фолбэк.
val nativeLinux = System.getProperty("os.name").orEmpty().startsWith("Linux")
val nativeRustOut = layout.buildDirectory.dir("native/rust-x86_64")
val nativeDesktopOut = layout.buildDirectory.dir("native/desktop")
val rustManifest = file("../native/rust/zapp-core/Cargo.toml")
val cppDir = file("../app/src/main/cpp")

val cargoBuildDesktop = tasks.register("cargoBuildDesktop") {
    inputs.dir(file("../native/rust/zapp-core/src"))
    inputs.files(
        fileTree(file("../native/rust/zapp-core")) {
            include("**/*.toml", "Cargo.lock")
        }
    )
    outputs.dir(nativeRustOut)
    doLast {
        if (!nativeLinux) return@doLast
        val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
            .takeIf { it.exists() }
            ?: error("rustup не установлен: нет ${'$'}HOME/.cargo/bin/cargo")
        val proc = ProcessBuilder(
            cargo.absolutePath, "build", "--release",
            "--target", "x86_64-unknown-linux-gnu",
            "--manifest-path", rustManifest.absolutePath
        )
            .directory(rustManifest.parentFile)
            .redirectErrorStream(true)
            .start()
        val log = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "cargo (десктоп) провалился:\n$log" }
        val archive = File(rustManifest.parentFile, "target/x86_64-unknown-linux-gnu/release/libzapp_core.a")
        check(archive.exists()) { "нет ${'$'}{archive.absolutePath} после сборки" }
        val dst = nativeRustOut.get().asFile.apply { mkdirs() }
        archive.copyTo(File(dst, "libzapp_core.a"), overwrite = true)
    }
}

val buildDesktopNative = tasks.register("buildDesktopNative") {
    dependsOn(cargoBuildDesktop)
    inputs.files(
        File(cppDir, "zapp_jni.cpp"),
        File(cppDir, "iptables_parse.cpp"),
        File(cppDir, "iptables_parse.h"),
        File(cppDir, "arg_tokenizer.cpp"),
        File(cppDir, "arg_tokenizer.h"),
        File(cppDir, "ipmath.c"),
        File(cppDir, "ipmath.h")
    )
    outputs.dir(nativeDesktopOut)
    doLast {
        if (!nativeLinux) return@doLast
        val jdk = System.getenv("JAVA_HOME")?.let(::File)
            ?: File(System.getProperty("java.home"))
        val jniInclude = File(jdk, "include").takeIf { it.isDirectory }
            ?: error("в $jdk нет include/jni.h -- нужен полный JDK")
        val out = nativeDesktopOut.get().asFile.apply { mkdirs() }
        // ipmath.c -- чистый C: компилируется gcc отдельно, C++-мосты
        // линкуют его объектником (g++ трактовал бы .c как C++).
        val cObj = File(out, "ipmath.o").absolutePath
        val cmd = "gcc -std=c11 -O2 -fPIC -c " +
            "${File(cppDir, "ipmath.c").absolutePath} -o $cObj && " +
            "g++ -std=c++17 -shared -fPIC -O2 -s -Wl,--gc-sections " +
            "-I${jniInclude.absolutePath} -I${File(jniInclude, "linux").absolutePath} " +
            "${File(cppDir, "zapp_jni.cpp").absolutePath} " +
            "${File(cppDir, "iptables_parse.cpp").absolutePath} " +
            "${File(cppDir, "arg_tokenizer.cpp").absolutePath} " +
            "$cObj " +
            "${File(nativeRustOut.get().asFile, "libzapp_core.a").absolutePath} " +
            "-o ${File(out, "libzappjni.so").absolutePath}"
        // NixOS: без nix-shell у g++ нет glibc. Обычный Linux: напрямую.
        val hasNix = runCatching {
            val probe = ProcessBuilder("nix-shell", "--version")
                .redirectErrorStream(true).start()
            probe.inputStream.readBytes()
            probe.waitFor() == 0
        }.getOrDefault(false)
        val pb = if (hasNix) {
            ProcessBuilder("nix-shell", "-p", "gcc", "binutils", "--run", cmd)
        } else {
            ProcessBuilder("bash", "-c", cmd)
        }
        val proc = pb.redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "сборка десктопного JNI-моста провалилась:\n$log" }
    }
}

kotlin {
    androidLibrary {
        // Namespace - это НЕ пакет Kotlin-кода, а идентификатор Android-библиотеки
        // (в него попадают сгенерированные R/BuildConfig). Поэтому он отличается от
        // пакета исходников и от namespace :app: merger запрещает дубли, а разные
        // Kotlin-пакеты у модулей наоборот нормально - код всё равно видится по classpath.
        namespace = "xyz.azraellab.zapp.shared"
        compileSdk = 37
        minSdk = 33

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    // Версия в коде генерируется из того же gradle-свойства, что и
    // versionName APK. Руками константа больше не правится: застыла на
    // 2.7.3 и заставляла приложение считать себя старым — проверка
    // обновлений предлагала установить уже установленную версию.
    val appVersionDir = layout.buildDirectory.dir("generated/appVersion/kotlin")
    val generateAppVersion = tasks.register("generateAppVersion") {
        val version = providers.provider {
            (project.findProperty("zappVersion") as String?) ?: "0.0.0"
        }
        val outDir = appVersionDir
        inputs.property("appVersion", version)
        outputs.dir(outDir)
        doLast {
            val pkg = outDir.get().asFile.resolve("xyz/azraellab/zapp/core/update")
            pkg.mkdirs()
            pkg.resolve("AppVersion.kt").writeText(
                """
                package xyz.azraellab.zapp.core.update

                /** Сгенерировано сборкой из gradle.properties (zappVersion). Не править руками. */
                const val APP_VERSION: String = "${version.get()}"
                """.trimIndent() + "\n"
            )
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(files(appVersionDir).builtBy(generateAppVersion))
        }
        // Общий JVM-слой: Android и десктоп -- обе JVM, поэтому HTTP-клиент,
        // геолокация и сокеты пишутся один раз здесь, а не копируются в
        // androidMain и desktopMain. Промежуточный source set -- штатный
        // механизм KMP: androidMain и desktopMain зависят от него наравне
        // с commonMain, и expect из common получает ровно один actual.
        val jvmShared by creating {
            dependsOn(commonMain.get())
        }
        getByName("androidMain").dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation("com.google.zxing:core:3.5.3")
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("com.google.zxing:core:3.5.3")
        }
        // .so лежит в ресурсах: ZappNative достаёт его с classpath во
        // время выполнения -- и в тестах, и в дистрибутиве один механизм.
        // builtBy сам вешает processResources на сборку моста.
        desktopMain.resources.srcDir(files(nativeDesktopOut).builtBy(buildDesktopNative))
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            // UI-тесты критичных путей: композиция страниц на десктопе,
            // headless под Xvfb. Тот же junit4, что и в kotlin("test").
            implementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
            implementation(compose.desktop.currentOs)
        }
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
            // Причина падения обязана быть в логе целиком: сборка идёт на
            // чужом раннере, и без текста исключения флак читается только
            // как «где-то упало».
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }
}