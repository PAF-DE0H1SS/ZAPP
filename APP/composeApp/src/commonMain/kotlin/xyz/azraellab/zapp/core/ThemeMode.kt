package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable

/** Как выбирается тема приложения (UX4). */
@Serializable
enum class ThemeMode {
    /** За системной темой устройства. */
    SYSTEM,

    /** Всегда тёмная. */
    DARK,

    /** Всегда светлая. */
    LIGHT
}
