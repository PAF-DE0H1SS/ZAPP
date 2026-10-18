package xyz.azraellab.zapp.core.update

/**
 * Ядро Android заменить нельзя: бинари стоят в jniLibs
 * (nativeLibraryDir) и исполняются только оттуда -- SELinux отклоняет
 * exec из каталога данных, и права на файл это не лечат. sing-box
 * обновляется здесь вместе с приложением.
 */
actual fun coreBoxPath(): String? = null

actual fun writeUpdatedCore(binary: ByteArray, version: String): Boolean = false
