import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
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

    sourceSets {
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
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
        }
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }
}