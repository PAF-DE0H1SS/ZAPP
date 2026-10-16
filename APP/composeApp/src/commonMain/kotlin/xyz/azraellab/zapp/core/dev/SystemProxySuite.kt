package xyz.azraellab.zapp.core.dev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.ConfigCodec
import xyz.azraellab.zapp.core.Preset
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.net.FlexibleBase64
import xyz.azraellab.zapp.core.net.SingboxConfig

/**
 * Системный прокси, общая часть: флаг настроек и локальные
 * входы в конфиге ядра.
 *
 * Механика ОС (реестр, gsettings, networksetup) живёт на десктопе и
 * покрыта отдельным тестом в `desktopTest`; здесь -- то, что обязано
 * работать одинаково на любой платформе: кодек, изоляция пресета и
 * сам состав inbounds.
 */
internal fun systemProxyTests(out: MutableList<DevTest>) {

    val ss = "ss://" + FlexibleBase64.encodeNoPadding("aes-128-gcm:secret") +
        "@1.1.1.1:443#n"

    out += DevTest("config/system-proxy-roundtrip", DevCategory.CONFIG, "системный прокси: флаг переживает кодек") {
        val decoded = ConfigCodec.decodeConfig(ConfigCodec.encodeConfig(AppConfig(systemProxy = true)))
        checkTrue(decoded.systemProxy, "записанный флаг должен прочитаться")
        checkFalse(ConfigCodec.decodeConfig("{}").systemProxy, "дефолт -- выключен")
    }

    out += DevTest("config/system-proxy-not-in-preset", DevCategory.CONFIG, "системный прокси: пресет не переносит флаг") {
        val preset = ConfigCodec.decodePreset(
            ConfigCodec.encodePreset(Preset.from(AppConfig(systemProxy = true), "p", 1L))
        )
        checkFalse(preset.applyTo(AppConfig()).systemProxy, "пресет не включает чужое поле")
        checkTrue(preset.applyTo(AppConfig(systemProxy = true)).systemProxy, "и не выключает своё")
    }

    out += DevTest("vpn/local-proxy-inbounds", DevCategory.VPN, "локальные входы: socks и http на loopback") {
        val built = SingboxConfig.buildGroup(VpnConfig(), listOf(ss))
        val inbounds = Json.parseToJsonElement(built.json).jsonObject["inbounds"]!!
            .jsonArray.map { it.jsonObject }
        val socks = inbounds.firstOrNull { it["type"]?.jsonPrimitive?.content == "socks" }
        val http = inbounds.firstOrNull { it["type"]?.jsonPrimitive?.content == "http" }
        checkTrue(socks != null, "socks-вход обязан быть в десктопном конфиге")
        checkTrue(http != null, "http-вход обязан быть в десктопном конфиге")
        checkEq("127.0.0.1", socks!!["listen"]?.jsonPrimitive?.content, "socks -- только loopback")
        checkEq("19060", socks["listen_port"]?.jsonPrimitive?.content, "socks-порт")
        checkEq("127.0.0.1", http!!["listen"]?.jsonPrimitive?.content, "http -- только loopback")
        checkEq("19061", http["listen_port"]?.jsonPrimitive?.content, "http-порт")
    }

    out += DevTest("vpn/local-proxy-inbounds-disabled", DevCategory.VPN, "локальные входы: без поддержки -- пусто") {
        checkTrue(SingboxConfig.localProxyInbounds(false).isEmpty(), "без поддержки платформы входов нет")
        checkEq(2, SingboxConfig.localProxyInbounds(true).size, "с поддержкой -- ровно два")
    }
}
