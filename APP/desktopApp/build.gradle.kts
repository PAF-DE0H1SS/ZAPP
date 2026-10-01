import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val zappVersion: String by project

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":composeApp"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "xyz.azraellab.zapp.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "zapp"
            packageVersion = zappVersion
            description = "ZAPP - network toolkit"
            vendor = "ZAPP"
            copyright = "Copyright (C) 2026 ZAPP"
            linux {
                appCategory = "Network"
                menuGroup = "Network"
            }
        }
    }
}