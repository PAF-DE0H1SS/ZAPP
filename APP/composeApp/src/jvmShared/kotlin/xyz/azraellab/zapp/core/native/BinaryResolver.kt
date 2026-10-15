package xyz.azraellab.zapp.core.native

import xyz.azraellab.zapp.core.root.RootShell
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Поиск исполняемого файла для запуска: свои бинари, потом PATH.
 *
 * Порядок важен. Сначала -- распакованные из assets: они всегда той же
 * версии, что и приложение, и не зависят от того, что установили в
 * образ. Дальше -- PATH: `/system/bin`, `/data/local/tmp` и прочее, куда
 * бинари кладут вручную. Всё остальное -- null, и вызывающий уже решает,
 * как честно сообщить об отсутствии.
 *
 * Есть ещё один шаг, свой Android с root: каталог приложения -- это
 * `app_data_file`, и исполнять его su-дочернему процессу мешает SELinux
 * на части прошивок. Поэтому при работающем `su` бинарь копируется в
 * `/data/local/tmp/zapp/bin` -- каталог, который shell читает всегда, --
 * и запускается оттуда.
 *
 * Этот каталог переживает uninstall и доступен другим приложениям, поэтому
 * копия не принимается на веру: перед запуском её sha256 сверяется с
 * sha256 нашего файла. Старый бинарь после переустановки, подменённый
 * файл и частично записанная копия отсекаются здесь, и вызывающий получает
 * либо проверенный путь, либо отказ.
 */
object BinaryResolver {

    private const val SHARED_DIR = "/data/local/tmp/zapp/bin"

    /**
     * Проверенные хэши этой сессии: путь -- sha256.
     *
     * Бинарь -- десятки мегабайт, и считать его хэш на каждый старт
     * демона -- лишняя работа. Раз в сессию на файл достаточно: ни версия
     * APK, ни содержимое каталога за время жизни процесса сами по себе не
     * меняются.
     */
    private val sessionSha = ConcurrentHashMap<String, String>()

    /**
     * Возвращает путь к бинарю или null, если нигде не найден.
     *
     * [name] -- обычное имя (`box`) или путь (`/vendor/bin/wg`): путям
     * верификация не нужна, они проходят как есть.
     */
    fun resolve(name: String): String? {
        if (name.contains('/')) {
            return name.takeIf { runCatching { File(it).exists() }.getOrDefault(false) }
        }

        val local = NativeBinaries.path(name)
        if (local != null) {
            return shareForRoot(local, name) ?: local
        }
        return RootShell.which(name)
    }

    /**
     * Кладёт файл в каталог для root-запуска и возвращает новый путь.
     *
     * null -- нечего копировать, копирование не понадобилось (нет root,
     * каталог недоступен) или копия не сошлась с нашим файлом. В первых
     * двух случаях вызывающий просто берёт исходный путь: это не ошибка,
     * а разные способы запуска. В последнем исходный путь отдавать нельзя:
     * от SELinux он всё равно не запустится, и лучше честный отказ, чем
     * чужой бинарь под видом нашего.
     */
    private fun shareForRoot(local: String, name: String): String? {
        if (!RootShell.available()) return null
        if (!RootShell.run("mkdir -p $SHARED_DIR", 2000).ok) return null
        val dst = "$SHARED_DIR/$name"
        val size = runCatching { File(local).length() }.getOrDefault(0L)
        val sha = localSha(File(local)) ?: return null
        if (isCurrent(dst, size, sha)) return dst
        val copy = RootShell.run(
            "cp -f ${RootShell.quote(local)} ${RootShell.quote(dst)} && chmod 755 ${RootShell.quote(dst)}",
            15000
        )
        if (!copy.ok) return null
        // Свежая копия тоже сверяется: частично записанный файл (место
        // кончилось) не должен запускаться от root, а на один `exit 0` от
        // cp полагаться слабо.
        if (!isCurrent(dst, size, sha)) {
            RootShell.run("rm -f ${RootShell.quote(dst)}", 2000)
            return null
        }
        return dst
    }

    /**
     * Копия в каталоге совпадает с нашим файлом.
     *
     * Хэш считается прямо здесь: `/data/local/tmp` читается любым
     * приложением (иначе su-дети не увидели бы его вовсе), поэтому
     * отдельный шелл для сверки не нужен. Если прочитать копию не вышло --
     * прошивка спрятала каталог от untrusted_app -- остаётся прежняя
     * проверка по размеру: грубее, но лучше, чем ничего.
     */
    private fun isCurrent(dst: String, size: Long, sha: String): Boolean {
        sessionSha[dst]?.let { return it == sha }
        val remote = sha256Of(File(dst)) ?: return sameSize(dst, size)
        if (remote != sha) return false
        sessionSha[dst] = sha
        return true
    }

    /** Наш sha256 файла: раз на сессию, не на каждый вызов. */
    private fun localSha(file: File): String? {
        sessionSha[file.absolutePath]?.let { return it }
        val sha = sha256Of(file) ?: return null
        sessionSha[file.absolutePath] = sha
        return sha
    }

    /** Размер копии совпадает: запасной путь, когда копия не читается. */
    private fun sameSize(dst: String, size: Long): Boolean =
        RootShell.run(
            "[ -f ${RootShell.quote(dst)} ] && " +
                "[ \"$(wc -c < ${RootShell.quote(dst)})\" = \"$size\" ]",
            2000
        ).ok

    /** sha256 файла, null -- не прочитали. */
    private fun sha256Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
