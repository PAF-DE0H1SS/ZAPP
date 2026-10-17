package xyz.azraellab.zapp.core.qr

/**
 * Матрица QR: true — чёрная клетка (модуль).
 *
 * Своя обёртка поверх BitMatrix из zxing, чтобы обмен текста был виден
 * в commonMain, а звисимость на zxing осталась на JVM-стороне.
 */
class QrMatrix(
    val width: Int,
    val height: Int,
    private val cells: BooleanArray
) {
    init {
        require(cells.size == width * height) { "матрица не совпадает по размеру" }
    }

    operator fun get(x: Int, y: Int): Boolean = cells[y * width + x]
}

/** Строит матрицу из строк, где `#` — чёрная клетка, всё остальное — светлая. */
fun qrMatrixOf(rows: List<String>): QrMatrix {
    val height = rows.size
    val width = rows.firstOrNull()?.length ?: 0
    val cells = BooleanArray(width * height)
    for (y in 0 until height) {
        val row = rows[y]
        for (x in 0 until width) {
            cells[y * width + x] = x < row.length && row[x] == '#'
        }
    }
    return QrMatrix(width, height, cells)
}

/** Кодирует текст в QR; null — пусто или текст не помещается в один код. */
expect fun encodeQr(text: String): QrMatrix?

/** Декодирует QR обратно в текст; null — не читается. */
expect fun decodeQr(matrix: QrMatrix): String?
