package xyz.azraellab.zapp.core.native

import xyz.azraellab.zapp.core.root.RootShell
import java.io.File

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
 * и запускается оттуда. Копирование разовое на вызов: размер файла не
 * меняется, а лишние мегабайты в `/data/local/tmp` переживают только
 * сессию перезапуска.
 */
object BinaryResolver {

    private const val SHARED_DIR = "/data/local/tmp/zapp/bin"

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
     * null -- нечего копировать или копирование не понадобилось (нет root,
     * каталог недоступен). В обоих случаях вызывающий просто берёт
     * исходный путь: это не ошибка, а разные способы запуска.
     *
     * Повторный вызов копирует заново только если файла ещё нет или он
     * другого размера: раньше 76 МБ бинарей переписывались на каждое
     * открытие страницы компонентов и на каждый старт демона, а размер
     * внутри одной сборки APK не меняется.
     */
    private fun shareForRoot(local: String, name: String): String? {
        if (!RootShell.available()) return null
        if (!RootShell.run("mkdir -p $SHARED_DIR", 2000).ok) return null
        val dst = "$SHARED_DIR/$name"
        val size = runCatching { File(local).length() }.getOrDefault(0L)
        val unchanged = RootShell.run(
            "[ -f ${RootShell.quote(dst)} ] && " +
                "[ \"$(wc -c < ${RootShell.quote(dst)})\" = \"$size\" ]",
            2000
        )
        if (unchanged.ok) return dst
        val copy = RootShell.run(
            "cp -f ${RootShell.quote(local)} ${RootShell.quote(dst)} && chmod 755 ${RootShell.quote(dst)}",
            15000
        )
        return if (copy.ok) dst else null
    }
}
