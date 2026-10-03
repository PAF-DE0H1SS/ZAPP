package xyz.azraellab.zapp.core.native

/**
 * На десктопе свои бинари: они не входят в дистрибутив, а ищутся в PATH.
 * Методы существуют, чтобы общий код не знал про платформу, но делают
 * ровно ничего: готовность наступает сразу, путей нет.
 */
actual object NativeBinaries {

    actual val tools: List<String> = listOf("nfqws", "goodbyedpi", "wg", "box", "openvpn", "tor", "lyrebird")

    actual suspend fun awaitReady() = Unit

    actual fun path(tool: String): String? = null

    actual fun installed(): List<String> = emptyList()

    actual suspend fun reinstall() = Unit
}
