// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// Важный момент: здесь перечислены ВСЕ плагины с `apply false`. Помимо обычной
// декларации это ещё и влияет на версию Kotlin Gradle Plugin в classpath: AGP
// тянет свою транзитивную зависимость (kotlin-gradle-plugin 2.2.x), и если
// kotlin.multiplatform в classpath нет, Gradle останется на версии из AGP.
// Со списком ниже конфликт разрешается в сторону нашей (2.4.20).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Сборка всех платформ одним кодом и дизайном:
//   ./gradlew autoBuild
// Android  : :app (APK debug + release) и android-таргет :composeApp
// Desktop  : дистрибутив текущей ОС (msi/deb/appimage) + uber-JAR,
//            который запускается на Windows/macOS/Linux без установки.
tasks.register("autoBuild") {
    group = "build"
    description = "Собирает Android (debug+release) и Desktop (дистрибутив текущей ОС + uber-JAR)"
    dependsOn(
        ":app:assembleDebug",
        ":app:assembleRelease",
        ":desktopApp:createDistributable",
        ":desktopApp:packageUberJarForCurrentOS"
    )
}

// Только релизные артефакты - для CI, где debug не нужен.
tasks.register("autoBuildRelease") {
    group = "build"
    description = "Собирает Android release + desktop uber-JAR"
    dependsOn(
        ":app:assembleRelease",
        ":desktopApp:packageReleaseUberJarForCurrentOS"
    )
}