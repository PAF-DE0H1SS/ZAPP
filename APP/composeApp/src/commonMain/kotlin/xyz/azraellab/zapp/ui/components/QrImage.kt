package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import xyz.azraellab.zapp.core.qr.QrMatrix

/**
 * Рисует QR-матрицу чёрными клетками по светлому фону.
 *
 * Масштаб клетки считается от меньшей стороны, поэтому код остаётся
 * квадратным и читаемым на любой ширине карточки.
 */
@Composable
fun QrImage(
    matrix: QrMatrix,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        if (matrix.width == 0 || matrix.height == 0) return@Canvas
        val cell = minOf(size.width / matrix.width, size.height / matrix.height)
        val offsetX = (size.width - cell * matrix.width) / 2f
        val offsetY = (size.height - cell * matrix.height) / 2f
        drawRect(color = Color.White, size = Size(size.width, size.height))
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offsetX + x * cell, offsetY + y * cell),
                        size = Size(cell, cell)
                    )
                }
            }
        }
    }
}
