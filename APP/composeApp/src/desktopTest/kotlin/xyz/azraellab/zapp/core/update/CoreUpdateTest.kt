package xyz.azraellab.zapp.core.update

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Обновление ядра sing-box: minor-гейт фида, выбор архивов платформ,
 * разбор tar.gz/zip. Сеть и диск тут ни при чём -- они в actual-слое.
 */
class CoreUpdateTest {

    private fun releaseJson(tag: String, vararg assets: String): String = buildString {
        append("""{"tag_name": "$tag", "assets": [""")
        append(assets.joinToString(",") { """{"browser_download_url": "$it"}""" })
        append("]}")
    }

    private val linuxRelease = releaseJson(
        "v1.12.3",
        "https://dl/sing-box-1.12.3-darwin-arm64.tar.gz",
        "https://dl/sing-box-1.12.3-linux-amd64.tar.gz",
        "https://dl/sing-box-1.12.3-windows-amd64.zip"
    )

    @Test
    fun offersPatchInsideOwnLine() {
        val found = parseCoreRelease(linuxRelease, "1.12.1", "linux", "amd64")
        assertEquals(CoreUpdateInfo("1.12.3", "https://dl/sing-box-1.12.3-linux-amd64.tar.gz", false), found)
    }

    @Test
    fun minorJumpIsGated() {
        // Конфиги приложения проверены на своей minor-ветке: прыжок ядра
        // на новую схему не предлагается молча.
        assertNull(parseCoreRelease(releaseJson("v1.13.0", "https://dl/sing-box-1.13.0-linux-amd64.tar.gz"), "1.12.1", "linux", "amd64"))
    }

    @Test
    fun olderOrEqualIsNotOffered() {
        assertNull(parseCoreRelease(releaseJson("v1.12.3"), "1.12.9", "linux", "amd64"))
        assertNull(parseCoreRelease(releaseJson("v1.12.3"), "1.12.3", "linux", "amd64"))
    }

    @Test
    fun missingAssetIsNoOffer() {
        assertNull(parseCoreRelease(releaseJson("v1.12.2", "https://dl/sing-box-1.12.2-darwin-arm64.tar.gz"), "1.12.1", "linux", "amd64"))
    }

    @Test
    fun windowsAssetIsZip() {
        val found = parseCoreRelease(linuxRelease, "1.12.1", "windows", "amd64")
        assertEquals("https://dl/sing-box-1.12.3-windows-amd64.zip", found?.url)
        assertEquals(true, found?.zip)
    }

    @Test
    fun coreGateRules() {
        assertTrue(coreGateOpen("1.12.1", "1.12.3"))
        assertTrue(coreGateOpen("v1.12.1", "v1.12.3"))
        // Короткий формат той же ветки -- тоже своя линия.
        assertTrue(coreGateOpen("1.12", "1.12.1"))
        assertFalse(coreGateOpen("1.12.1", "1.13.0"))
        assertFalse(coreGateOpen("2026-10-03", "1.12.1"))
    }

    @Test
    fun osArchMapping() {
        assertEquals("linux" to "amd64", coreOsArch("Linux", "amd64"))
        assertEquals("linux" to "arm64", coreOsArch("Linux", "aarch64"))
        assertEquals("windows" to "amd64", coreOsArch("Windows 11", "amd64"))
        assertEquals("darwin" to "arm64", coreOsArch("Mac OS X", "aarch64"))
        assertNull(coreOsArch("SunOS", "sparc"))
        assertNull(coreOsArch("Linux", "riscv64"))
    }

    // --- Архивы ---

    private fun tarOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in files) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray()
            nameBytes.copyInto(header, destinationOffset = 0, endIndex = nameBytes.size.coerceAtMost(100))
            data.size.toString(8).padStart(11, '0').encodeToByteArray()
                .copyInto(header, destinationOffset = 124, endIndex = 11)
            header[156] = '0'.code.toByte()
            out.write(header)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun tarEntriesReadFilesAndSkipDirs() {
        val payload = ByteArray(600) { it.toByte() }
        val entries = tarEntries(tarOf("sing-box-1.12.3/sing-box" to payload))
        assertEquals(setOf("sing-box-1.12.3/sing-box"), entries.keys)
        assertContentEquals(payload, entries["sing-box-1.12.3/sing-box"])
    }

    @Test
    fun tarGzEntriesUnpacks() {
        val data = "содержимое бинаря".toByteArray()
        val entries = tarGzEntries(gzip(tarOf("sing-box-1.12.3-linux-amd64/sing-box" to data)))
        assertContentEquals(data, entries["sing-box-1.12.3-linux-amd64/sing-box"])
        // Битый gzip -- пустые записи, а не падение.
        assertEquals(emptyMap(), tarGzEntries(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun zipEntriesReadFiles() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("sing-box-1.12.3-windows-amd64/sing-box.exe"))
            zip.write(byteArrayOf(9, 9, 9))
            zip.closeEntry()
        }
        val entries = zipEntries(out.toByteArray())
        assertContentEquals(byteArrayOf(9, 9, 9), entries["sing-box-1.12.3-windows-amd64/sing-box.exe"])
    }

    @Test
    fun findCoreBinaryPicksPlatformFile() {
        val linux = mapOf("sing-box-1.12.3/sing-box" to ByteArray(4))
        assertContentEquals(ByteArray(4), findCoreBinary(linux, "linux"))
        assertNull(findCoreBinary(linux, "windows"))
        val windows = mapOf("sing-box/sing-box.exe" to byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), findCoreBinary(windows, "windows"))
    }
}
