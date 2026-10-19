// Автономная проверка ipmath.c: чистый C, без JNI и Android.
//
// Прогон: gcc -std=c11 -Wall -Wextra -O2 ipmath.c ipmath_test.c
//
// Ожидания взяты из IpMath.kt и Kotlin-тестов (RouteDetectorTest,
// dev-сюиты): краи подсетей, пресеты Local, списки PRIVATE/MULTICAST
// детектора.
#include "ipmath.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            exit(1);                                                    \
        }                                                               \
    } while (0)

// ---- хелперы ----

/* Успешный разбор и точное совпадение значения. */
static bool v4_is(const char *text, uint32_t expect)
{
    uint32_t v = 0;
    return ip_parse_ipv4(text, &v) && v == expect;
}

/* Отказ, и буфер результата не тронут. */
static bool v4_reject(const char *text)
{
    uint32_t v = 0x5A5A5A5Au;
    return !ip_parse_ipv4(text, &v) && v == 0x5A5A5A5Au;
}

static bool v6_is(const char *text, const uint16_t expect[8])
{
    uint16_t g[8];
    if (!ip_parse_ipv6(text, g)) return false;
    for (int i = 0; i < 8; i++)
        if (g[i] != expect[i]) return false;
    return true;
}

static bool v6_reject(const char *text)
{
    uint16_t g[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    if (ip_parse_ipv6(text, g)) return false;
    for (int i = 0; i < 8; i++)
        if (g[i] != (uint16_t)(i + 1)) return false;
    return true;
}

/* Две строки разбираются в одинаковые адреса: полная форма == сжатие. */
static bool v6_same(const char *a, const char *b)
{
    uint16_t ga[8];
    uint16_t gb[8];
    return ip_parse_ipv6(a, ga) && ip_parse_ipv6(b, gb) &&
           memcmp(ga, gb, sizeof ga) == 0;
}

/* Первое совпавшее правило из списка, как firstOrNull у детектора. */
static const char *first_match(const char *const *list, size_t n, const char *addr)
{
    for (size_t i = 0; i < n; i++)
        if (ip_cidr_contains(list[i], addr)) return list[i];
    return NULL;
}

/* Точно те списки, что пишет RouteDetector. */
static const char *const PRIVATE[6] = {
    "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12",
    "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10"
};
static const char *const MULTICAST[2] = {"224.0.0.0/4", "255.255.255.255/32"};

int main(void)
{
    // --- IPv4: разбор ---

    CHECK(v4_is("10.1.2.3", 0x0A010203u));  // из RouteDetectorTest
    CHECK(v4_is("1.2.3.4", 0x01020304u));
    CHECK(v4_is("0.0.0.0", 0u));
    CHECK(v4_is("255.255.255.255", 0xFFFFFFFFu));
    CHECK(v4_is(" \t10.1.2.3 \r\n", 0x0A010203u));  // внешние пробелы
    CHECK(v4_is("001.002.003.004", 0x01020304u));   // ведущие нули -- не ошибка
    CHECK(v4_is("01.2.3.04", 0x01020304u));

    // --- IPv4: отказы ---

    CHECK(v4_reject("256.1.1.1"));  // из RouteDetectorTest
    CHECK(v4_reject("1.2.3"));
    CHECK(v4_reject("1.2.3.4.5"));
    CHECK(v4_reject("a.b.c.d"));
    CHECK(v4_reject("1..2.3"));
    CHECK(v4_reject(""));
    CHECK(v4_reject("   "));
    CHECK(v4_reject("1.2.3.256"));
    CHECK(v4_reject("1234.1.1.1"));  // 4 цифры -- отказ, а не 123
    CHECK(v4_reject("1.2.3.4x"));
    CHECK(v4_reject("1.2.3.-1"));
    CHECK(v4_reject("1.2.3.+1"));
    CHECK(v4_reject("1.2.3.4:80"));  // порт -- не адрес
    CHECK(v4_reject("::1"));         // IPv6 -- не IPv4

    // --- IPv4: пробелы по краям, как в Kotlin ---

    CHECK(v4_is("\xC2\xA0" "10.1.2.3", 0x0A010203u));  // NBSP режется
    CHECK(v4_is("10.1.2.3\xC2\xA0", 0x0A010203u));
    CHECK(v4_reject("\xC2\x85" "10.1.2.3"));  // NEL -- не пробел
    CHECK(v4_reject("10.1.2.3\xC2\x85"));
    CHECK(v4_reject("\xE2\x80\x8B" "10.1.2.3"));  // zero-width space -- не пробел
    CHECK(v4_is("\x1C" "1.2.3.4" "\x1F", 0x01020304u));  // 0x1C..0x1F режутся

    // --- IPv6: разбор ---

    CHECK(v6_is("::1", (const uint16_t[8]){0, 0, 0, 0, 0, 0, 0, 1}));  // из RouteDetectorTest
    CHECK(v6_is("::", (const uint16_t[8]){0, 0, 0, 0, 0, 0, 0, 0}));
    CHECK(v6_is("2001:db8::1", (const uint16_t[8]){0x2001, 0x0DB8, 0, 0, 0, 0, 0, 1}));
    CHECK(v6_same("2001:db8:0:0:0:0:0:1", "2001:db8::1"));  // сжатие == полной форме
    CHECK(v6_is("::ffff:1.2.3.4", (const uint16_t[8]){0, 0, 0, 0, 0, 0xFFFF, 0x0102, 0x0304}));
    CHECK(v6_is("::1.2.3.4", (const uint16_t[8]){0, 0, 0, 0, 0, 0, 0x0102, 0x0304}));
    CHECK(v6_is(":: 1.2.3.4", (const uint16_t[8]){0, 0, 0, 0, 0, 0, 0x0102, 0x0304}));  // пробел перед хвостом
    CHECK(v6_is("1:2:3:4:5:6:1.2.3.4", (const uint16_t[8]){1, 2, 3, 4, 5, 6, 0x0102, 0x0304}));  // хвост без "::"
    CHECK(v6_is("FF01::ABCD", (const uint16_t[8]){0xFF01, 0, 0, 0, 0, 0, 0, 0xABCD}));  // верхний регистр
    CHECK(v6_is("  ::1  ", (const uint16_t[8]){0, 0, 0, 0, 0, 0, 0, 1}));
    CHECK(v6_is("fe80::", (const uint16_t[8]){0xFE80, 0, 0, 0, 0, 0, 0, 0}));
    CHECK(v6_is("1:2:3:4:5:6:7::", (const uint16_t[8]){1, 2, 3, 4, 5, 6, 7, 0}));  // "::" съедает хвостовые нули
    CHECK(v6_is("::1:2:3:4:5:6:7", (const uint16_t[8]){0, 1, 2, 3, 4, 5, 6, 7}));

    // --- IPv6: отказы ---

    CHECK(v6_reject("2001::db8::1"));  // из RouteDetectorTest
    CHECK(v6_reject("12345::"));
    CHECK(v6_reject("gg::1"));
    CHECK(v6_reject(""));
    CHECK(v6_reject("1:2:3:4:5:6:7"));
    CHECK(v6_reject("1:2:3:4:5:6:7:8:9"));
    CHECK(v6_reject("1:2:3:4:5:6:7:8:"));
    CHECK(v6_reject(":"));
    CHECK(v6_reject("::1:2:3:4:5:6:7:8"));  // "::" не может занять ноль групп
    CHECK(v6_reject("1:2:3:4:5:6:7::8"));
    CHECK(v6_reject("1::2::3"));
    CHECK(v6_reject("1:::2"));  // хвост с "::" начинается с пустой группы
    CHECK(v6_reject("abcd::12345"));
    CHECK(v6_reject("fe80::1%eth0"));  // zone id не поддерживается
    CHECK(v6_reject("1.2.3.4"));       // чистый IPv4 -- не IPv6
    CHECK(v6_reject("::ffff:1.2.3.999"));
    CHECK(v6_reject("::ffff:1.2.3"));  // хвост -- не IPv4

    // --- Литерал vs домен (вопрос детектора «это IP?») ---

    CHECK(ip_is_ip_literal("10.1.2.3"));
    CHECK(ip_is_ip_literal("::1"));
    CHECK(ip_is_ip_literal(" 8.8.8.8 "));
    CHECK(ip_is_ip_literal("::ffff:1.2.3.4"));
    CHECK(ip_is_ip_literal("2001:db8::1"));
    CHECK(!ip_is_ip_literal("example.com"));
    CHECK(!ip_is_ip_literal("router.lan"));
    CHECK(!ip_is_ip_literal(""));
    CHECK(!ip_is_ip_literal("1.2.3.4.5"));
    CHECK(!ip_is_ip_literal("2001::db8::1"));
    CHECK(!ip_is_ip_literal("1.2.3.4:80"));

    // --- CIDR v4: кейсы из RouteDetectorTest ---

    CHECK(ip_cidr_contains("10.0.0.0/8", "10.1.2.3"));
    CHECK(!ip_cidr_contains("10.0.0.0/8", "11.0.0.1"));
    CHECK(ip_cidr_contains("203.0.113.5/32", "203.0.113.5"));
    CHECK(!ip_cidr_contains("203.0.113.5/32", "203.0.113.6"));
    CHECK(ip_cidr_contains("0.0.0.0/0", "8.8.8.8"));
    CHECK(!ip_cidr_contains("10.0.0.0/8", "2001:db8::1"));  // смешанные семейства
    CHECK(!ip_cidr_contains("10.0.0.0", "10.0.0.1"));  // без слэша
    CHECK(!ip_cidr_contains("10.0.0.0/99", "10.0.0.1"));

    // --- CIDR v4: границы подсетей ---

    CHECK(ip_cidr_contains("192.168.1.0/24", "192.168.1.0"));  // первый адрес
    CHECK(ip_cidr_contains("192.168.1.0/24", "192.168.1.255"));  // широковещательный
    CHECK(!ip_cidr_contains("192.168.1.0/24", "192.168.2.1"));
    CHECK(!ip_cidr_contains("192.168.1.0/24", "192.168.0.255"));
    CHECK(ip_cidr_contains("10.1.2.3/0", "8.8.8.8"));  // /0 ловит всё, база не обязана быть сетевой
    CHECK(ip_cidr_contains("100.64.0.0/10", "100.64.0.1"));  // CGNAT: нижняя граница /10
    CHECK(ip_cidr_contains("100.64.0.0/10", "100.127.255.255"));  // верхняя
    CHECK(!ip_cidr_contains("100.64.0.0/10", "100.128.0.0"));
    CHECK(!ip_cidr_contains("100.64.0.0/10", "100.63.255.255"));
    CHECK(ip_cidr_contains("172.16.0.0/12", "172.16.0.1"));
    CHECK(ip_cidr_contains("172.16.0.0/12", "172.31.255.255"));
    CHECK(!ip_cidr_contains("172.16.0.0/12", "172.32.0.0"));
    CHECK(!ip_cidr_contains("172.16.0.0/12", "172.15.255.255"));
    CHECK(ip_cidr_contains("127.0.0.0/8", "127.255.255.255"));
    CHECK(!ip_cidr_contains("127.0.0.0/8", "128.0.0.1"));
    CHECK(ip_cidr_contains("192.168.0.0/16", "192.168.255.255"));  // пресет Local
    CHECK(ip_cidr_contains("169.254.0.0/16", "169.254.1.1"));
    CHECK(ip_cidr_contains("224.0.0.0/4", "239.1.2.3"));  // мультикаст
    CHECK(!ip_cidr_contains("224.0.0.0/4", "240.0.0.1"));
    CHECK(!ip_cidr_contains("224.0.0.0/4", "223.255.255.255"));
    CHECK(ip_cidr_contains("255.255.255.255/32", "255.255.255.255"));
    CHECK(!ip_cidr_contains("255.255.255.255/32", "255.255.255.254"));
    CHECK(ip_cidr_contains("192.168.1.5/24", "192.168.1.7"));  // непустая база маскируется тоже
    CHECK(ip_cidr_contains(" 10.0.0.0 /8", " 10.1.2.3 "));  // пробелы срезаются обеими сторонами

    // --- CIDR v4: вложенные подсети ---

    CHECK(ip_cidr_contains("10.0.0.0/8", "10.1.2.3"));
    CHECK(ip_cidr_contains("10.1.0.0/16", "10.1.2.3"));
    CHECK(ip_cidr_contains("10.1.2.0/24", "10.1.2.3"));
    CHECK(ip_cidr_contains("10.1.2.3/32", "10.1.2.3"));
    CHECK(!ip_cidr_contains("10.1.2.0/24", "10.1.3.1"));  // соседний /24 вне
    CHECK(!ip_cidr_contains("10.1.0.0/16", "10.2.0.1"));  // соседний /16 вне

    // --- CIDR: длина префикса читается как Int Kotlin ---

    CHECK(ip_cidr_contains("10.1.2.3/+032", "10.1.2.3"));  // знак и ведущие нули
    CHECK(ip_cidr_contains("10.0.0.0/+8", "10.1.2.3"));
    CHECK(ip_cidr_contains("10.1.2.3/-0", "8.8.8.8"));  // "-0" -- это 0, то есть /0
    CHECK(!ip_cidr_contains("10.0.0.0/ 8", "10.1.2.3"));  // пробел в числе -- отказ
    CHECK(!ip_cidr_contains("10.0.0.0/8 ", "10.1.2.3"));
    CHECK(!ip_cidr_contains("10.0.0.0/-1", "10.1.2.3"));
    CHECK(!ip_cidr_contains("10.0.0.0/", "10.1.2.3"));  // пустая длина
    CHECK(!ip_cidr_contains("/8", "10.1.2.3"));  // пустая база
    CHECK(!ip_cidr_contains("10.0.0.0//8", "10.1.2.3"));
    CHECK(!ip_cidr_contains("10.0.0.0/8/9", "10.1.2.3"));  // хвост после длины -- мусор
    CHECK(!ip_cidr_contains("10.0.0.0/99999999999999999999", "10.1.2.3"));  // переполнение Int
    CHECK(!ip_cidr_contains("banana", "10.1.2.3"));
    CHECK(!ip_cidr_contains("10.0.0.0/8", "banana"));
    CHECK(!ip_cidr_contains("10.0.0.0/8", ""));

    // --- CIDR v6: кейсы из RouteDetectorTest и границы ---

    CHECK(ip_cidr_contains("2001:db8::/32", "2001:db8:1234::1"));
    CHECK(!ip_cidr_contains("2001:db8::/32", "2001:db9::1"));
    CHECK(ip_cidr_contains("ff00::/8", "ff02::1"));
    CHECK(ip_cidr_contains("::/0", "2001:db8::1"));  // всё v6-пространство
    CHECK(ip_cidr_contains("::/0", "::ffff:1.2.3.4"));
    CHECK(ip_cidr_contains("fc00::/7", "fd00::1"));  // ULA из пресета Local
    CHECK(ip_cidr_contains("fc00::/7", "fcff:ffff::1"));  // последний адрес
    CHECK(!ip_cidr_contains("fc00::/7", "fe00::1"));
    CHECK(!ip_cidr_contains("fc00::/7", "fbff:ffff::1"));
    CHECK(ip_cidr_contains("2001:db8::/32", "2001:db8::1"));  // первый адрес
    CHECK(ip_cidr_contains("2001:db8::/32", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"));  // последний
    CHECK(!ip_cidr_contains("2001:db8::/32", "2001:d800::1"));
    CHECK(ip_cidr_contains("2001:db8:8000::/33", "2001:db8:ffff::1"));  // маска внутри группы
    CHECK(!ip_cidr_contains("2001:db8:8000::/33", "2001:db8:7fff::1"));
    CHECK(ip_cidr_contains("fe80::/10", "fe80::1"));
    CHECK(!ip_cidr_contains("fe80::/10", "fec0::1"));
    CHECK(ip_cidr_contains("2001:db8::/127", "2001:db8::1"));
    CHECK(!ip_cidr_contains("2001:db8::/127", "2001:db8::2"));  // младший бит решает
    CHECK(ip_cidr_contains("2001:db8::/128", "2001:db8::"));  // /128 -- только точное совпадение
    CHECK(!ip_cidr_contains("2001:db8::/128", "2001:db8::1"));
    CHECK(!ip_cidr_contains("2001:db8::/129", "2001:db8::1"));
    CHECK(!ip_cidr_contains("2001:db8::", "2001:db8::1"));  // без слэша
    CHECK(!ip_cidr_contains("2001:db8::/abc", "2001:db8::1"));
    CHECK(!ip_cidr_contains("2001:db8::/32", "10.1.2.3"));  // v6-подсеть против v4-адреса
    CHECK(!ip_cidr_contains("2001:db8::/32", "banana"));

    // --- Списки детектора: PRIVATE и MULTICAST отдают те же совпадения ---

    CHECK(strcmp(first_match(PRIVATE, 6, "192.168.1.1"), "192.168.0.0/16") == 0);
    CHECK(strcmp(first_match(PRIVATE, 6, "100.64.0.1"), "100.64.0.0/10") == 0);
    CHECK(first_match(PRIVATE, 6, "8.8.8.8") == NULL);
    CHECK(first_match(PRIVATE, 6, "2001:db8::1") == NULL);  // смешанные семейства
    CHECK(strcmp(first_match(MULTICAST, 2, "239.1.2.3"), "224.0.0.0/4") == 0);
    CHECK(strcmp(first_match(MULTICAST, 2, "255.255.255.255"), "255.255.255.255/32") == 0);
    CHECK(first_match(MULTICAST, 2, "223.255.255.255") == NULL);

    // --- NULL и защитные краи API ---

    uint32_t v = 0;
    CHECK(!ip_parse_ipv4(NULL, &v));
    CHECK(v == 0);  // буфер не тронут
    uint16_t g[8] = {0};
    CHECK(!ip_parse_ipv6(NULL, g));
    CHECK(!ip_is_ip_literal(NULL));
    CHECK(!ip_cidr_contains(NULL, "1.2.3.4"));
    CHECK(!ip_cidr_contains("10.0.0.0/8", NULL));

    // Режим «только проверка»: out == NULL.
    CHECK(ip_parse_ipv4("10.1.2.3", NULL));
    CHECK(ip_parse_ipv6("::1", NULL));
    CHECK(!ip_parse_ipv4("junk", NULL));
    CHECK(!ip_parse_ipv6("junk", NULL));

    puts("OK");
    return 0;
}
