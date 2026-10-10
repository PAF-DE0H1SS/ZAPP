package xyz.azraellab.zapp.core.perApp

import java.io.File

/**
 * Десктоп: вместо списка пакетов -- дерево живых процессов из `/proc`.
 *
 * У понятия «приложение» на ПК нет аналога Android: единица --
 * процесс. Пикер показывает дерево (родитель и его потомки с отступом),
 * выбор одного узла тянет всю ветку ([InstalledApp.familyId] -- pid
 * корня ветки), а поиск по строке показывает семью целиком: вбил имя
 * бинаря -- рядом все его worker'ы. UID берётся настоящий (владелец
 * процесса), поэтому список пригоден и для NFQUEUE `--uid-owner`.
 *
 * Пустой результат -- честное состояние: `/proc` не смонтирован (не
 * Linux) или каталоги недоступны. Падать нельзя: это обычный каталог.
 */
actual fun installedApps(): List<InstalledApp> {
    val dirs = runCatching { File("/proc").listFiles() }.getOrNull() ?: return emptyList()

    // Один проход по /proc: stat даёт ppid, status -- uid, cmdline/comm --
    // имя бинаря. Файлы читаются с runCatching: процесс мог уйти между
    // listFiles() и чтением, и этот pid просто выпадает из каталога.
    val nodes = HashMap<Int, ProcNode>(dirs.size)
    for (dir in dirs) {
        val pid = dir.name.toIntOrNull() ?: continue
        val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: continue
        val close = stat.lastIndexOf(')')
        if (close < 0) continue
        // После comm идёт state, затем ppid: rest[0]=state, rest[1]=ppid.
        val rest = stat.substring(close + 1).trim().split(Regex("\\s+"))
        val ppid = rest.getOrNull(1)?.toIntOrNull() ?: 0
        val status = runCatching { File(dir, "status").readText() }.getOrNull()
        val uid = status?.lineSequence()
            ?.firstOrNull { it.startsWith("Uid:") }
            ?.substringAfter("Uid:")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.toIntOrNull() ?: 0
        // cmdline разделён нулями; пуст у ядерных потоков -- тогда comm.
        val cmd = runCatching { File(dir, "cmdline").readText() }
            .getOrNull()
            ?.split(0.toChar())
            ?.firstOrNull { it.isNotBlank() }
            ?: ""
        val comm = runCatching { File(dir, "comm").readText().trim() }.getOrNull() ?: dir.name
        nodes[pid] = ProcNode(
            pid = pid,
            ppid = ppid,
            exe = cmd.substringAfterLast('/').ifBlank { comm },
            uid = uid
        )
    }
    if (nodes.isEmpty()) return emptyList()

    val children = HashMap<Int, MutableList<Int>>()
    for (node in nodes.values) {
        children.getOrPut(node.ppid) { mutableListOf() } += node.pid
    }

    val result = ArrayList<InstalledApp>(nodes.size)
    val visited = HashSet<Int>(nodes.size)

    // Обход в глубину: родитель сразу за своим поддеревом -- пикеру
    // нужен именно такой порядок, чтобы ветка читалась строками.
    fun walk(pid: Int, depth: Int, rootPid: Int) {
        if (!visited.add(pid)) return
        val node = nodes[pid] ?: return
        result += InstalledApp(
            packageName = "pid:$pid",
            label = node.exe,
            uid = node.uid,
            isSystem = node.uid == 0,
            depth = depth,
            familyId = rootPid
        )
        for (child in children[pid].orEmpty().sortedBy { nodes[it]?.exe?.lowercase() }) {
            walk(child, depth + 1, rootPid)
        }
    }

    // Корни -- процессы, чей родитель вне набора (init, чужой pid-namespace):
    // они и образуют «службы», ветки которых -- их потомки.
    val roots = nodes.values
        .filter { it.ppid == 0 || it.ppid !in nodes }
        .sortedBy { it.exe.lowercase() }
    for (root in roots) walk(root.pid, 0, root.pid)

    // Страховка от циклов пере-родительства: недостижимые из корней
    // узлы становятся своими корнями, а не пропадают из выбора.
    for (node in nodes.values.sortedBy { it.exe.lowercase() }) {
        if (node.pid !in visited) walk(node.pid, 0, node.pid)
    }
    return result
}

/**
 * UID пакета на десктопе -- pid процесса: пережить перезапуск он не
 * может (pids переназначаются), и это осознанно -- выбор процесса
 * живёт столько же, сколько сам процесс.
 */
actual fun packageUid(packageName: String): Int? = packageName
    .removePrefix("pid:")
    .toIntOrNull()

/** У приложения на десктопе нет своего пакета -- скрывать нечего. */
actual fun ownPackageName(): String = ""

/** Один узел дерева процессов до построения порядка вывода. */
private data class ProcNode(
    val pid: Int,
    val ppid: Int,
    val exe: String,
    val uid: Int
)
