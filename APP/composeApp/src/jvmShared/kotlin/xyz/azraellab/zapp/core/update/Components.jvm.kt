package xyz.azraellab.zapp.core.update

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.azraellab.zapp.core.native.BinaryResolver

// Общий для Android и Desktop: пути к бинарям и запуск процессов у обеих
// платформ одинаковы (BinaryResolver решает и сюжет jniLibs, и out/x86_64).

actual suspend fun probeComponentVersion(component: ZappComponent): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val path = BinaryResolver.resolve(component.binary) ?: return@runCatching null
            val process = ProcessBuilder(listOf(path) + component.args)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching null
            }
            // Читаем уже после выхода: usage бывает длинным, и чтение до
            // закрытия pipe упёрлось бы в буфер у зависшего процесса.
            val output = process.inputStream.bufferedReader().use { it.readText() }
            parseComponentVersion(component, output)
        }.getOrNull()
    }
