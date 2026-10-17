package xyz.azraellab.zapp.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private const val QUIET_ZONE = 4
private const val TARGET_SIZE = 256

actual fun encodeQr(text: String): QrMatrix? {
    if (text.isEmpty()) return null
    return runCatching {
        val matrix: BitMatrix = QRCodeWriter().encode(
            text,
            com.google.zxing.BarcodeFormat.QR_CODE,
            TARGET_SIZE,
            TARGET_SIZE,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE
            )
        )
        val cells = BooleanArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                cells[y * matrix.width + x] = matrix.get(x, y)
            }
        }
        QrMatrix(matrix.width, matrix.height, cells)
    }.getOrNull()
}

actual fun decodeQr(matrix: QrMatrix): String? {
    if (matrix.width == 0 || matrix.height == 0) return null
    return runCatching {
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] =
                    if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader()
            .decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
            .text
    }.getOrNull()
}
