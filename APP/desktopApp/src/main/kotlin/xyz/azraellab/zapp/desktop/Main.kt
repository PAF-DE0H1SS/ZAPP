package xyz.azraellab.zapp.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import kotlin.system.exitProcess
import xyz.azraellab.zapp.App
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.createConfigStore
import xyz.azraellab.zapp.core.net.ProxyUri
import xyz.azraellab.zapp.core.net.SingboxConfig

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--check-config") {
        exitProcess(checkConfig(args.drop(1)))
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "ZAPP",
            state = rememberWindowState(size = DpSize(900.dp, 640.dp))
        ) {
            App()
        }
    }
}

/**
 * Dev-инструмент: собрать конфиг sing-box из настроек и прогнать
 * `box check` -- валидацию схемы настоящим бинарём из native/out.
 *
 * Аргументы: [profileId | --group] [путь к box]. Без аргументов --
 * активный профиль и путь `native/out/x86_64/box` относительно CWD.
 * Отдельный режим приложения, а не юнит-тест: заменить живой валидатор
 * стабом -- значит проверить не то.
 */
private fun checkConfig(args: List<String>): Int {
    val profileArg = args.getOrNull(0)
    val boxPath = args.getOrNull(1) ?: "native/out/x86_64/box"

    val config = runCatching { createConfigStore().readConfig() }.getOrNull()
    if (config == null) {
        System.err.println("check-config: cannot read app config")
        return 2
    }
    val vpn = config.vpn

    val built = try {
        if (profileArg == "--group" || (profileArg == null && vpn.groupMode)) {
            val links = vpn.visibleProfiles().mapNotNull { profile ->
                val raw = profile.rawConfig
                if (raw.isBlank() || "://" !in raw) return@mapNotNull null
                if (VpnProtocol.of(profile.protocol) in setOf(
                        VpnProtocol.TOR, VpnProtocol.WIREGUARD,
                        VpnProtocol.AMNEZIA_WG, VpnProtocol.OPENVPN,
                        VpnProtocol.SINGBOX, VpnProtocol.XRAY
                    )
                ) return@mapNotNull null
                if (profile.health.alive == false) return@mapNotNull null
                ProxyUri.parse(raw)?.let { raw }
            }
            SingboxConfig.buildGroup(vpn, links)
        } else {
            val profile = vpn.profileById(profileArg.orEmpty()) ?: vpn.activeProfile()
            if (profile == null) {
                System.err.println("check-config: no profile")
                return 2
            }
            when (VpnProtocol.of(profile.protocol)) {
                VpnProtocol.TOR -> SingboxConfig.buildTor(vpn, vpn.torSocksPort)
                VpnProtocol.OPENVPN -> {
                    System.err.println("check-config: openvpn profiles are not sing-box configs")
                    return 2
                }
                else -> SingboxConfig.build(profile, vpn)
            }
        }
    } catch (e: Exception) {
        System.err.println("check-config: build failed: ${e.message}")
        return 2
    }

    val tmp = File(System.getProperty("java.io.tmpdir"), "zapp-box-check.json")
    tmp.writeText(built.json)
    built.notes.forEach { println("note: $it") }
    println("config: ${tmp.absolutePath}")

    val process = try {
        ProcessBuilder(boxPath, "check", "-c", tmp.absolutePath).start()
    } catch (e: Exception) {
        System.err.println("check-config: cannot run $boxPath: ${e.message}")
        return 2
    }
    val output = process.inputStream.bufferedReader().readText() +
        process.errorStream.bufferedReader().readText()
    print(output)
    return process.waitFor()
}
