// Порт IpMath.kt (parseIpv4 / parseIpv6 / isIpLiteral / cidrContains)
// на чистый C: тот же разбор, те же отказы, включая странные на первый
// взгляд краи (ведущие нули, "+" в длине префикса, пробел перед
// хвостовым IPv4). Каждое расхождение с Kotlin здесь было бы багом
// детектора маршрутизации, поэтому поведение повторено точь-в-точь.
#include "ipmath.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ---- внутренние помощники ----

/* Разбор одного кодпоинта UTF-8 из [p, end); структурно битые байты --
   отказ. Проверка на сверхдлинные коды не нужна: для задачи «это
   пробел?» соврать нельзя. */
static bool utf8_cp(const char *p, const char *end, uint32_t *cp, size_t *width)
{
    const unsigned char c = (unsigned char)*p;
    uint32_t v;
    size_t w;
    if (c < 0x80) {
        v = c;
        w = 1;
    } else if ((c & 0xE0u) == 0xC0u) {
        v = c & 0x1Fu;
        w = 2;
    } else if ((c & 0xF0u) == 0xE0u) {
        v = c & 0x0Fu;
        w = 3;
    } else if ((c & 0xF8u) == 0xF0u) {
        v = c & 0x07u;
        w = 4;
    } else {
        return false;
    }
    if (p + w > end) return false;
    for (size_t i = 1; i < w; i++) {
        const unsigned char t = (unsigned char)p[i];
        if ((t & 0xC0u) != 0x80u) return false;
        v = (v << 6) | (t & 0x3Fu);
    }
    *cp = v;
    *width = w;
    return true;
}

/* Пробел по правилу Kotlin (Char.isWhitespace() || Char.isSpaceChar(),
   сверено на JVM): ASCII-пробелы, 0x1C..0x1F, NBSP и юникод-разделители.
   NEL (U+0085) и zero-width space (U+200B) -- НЕ пробелы. */
static bool ws_cp(uint32_t cp)
{
    if (cp >= 0x09 && cp <= 0x0D) return true;
    if (cp >= 0x1C && cp <= 0x1F) return true;
    if (cp == 0x20 || cp == 0xA0 || cp == 0x1680) return true;
    if (cp >= 0x2000 && cp <= 0x200A) return true;
    if (cp == 0x2028 || cp == 0x2029 || cp == 0x202F) return true;
    if (cp == 0x205F || cp == 0x3000) return true;
    return false;
}

/* Срез внешних пробелов тем же составом, что trim() в Kotlin. */
static void trim_ws(const char *b, const char *e, const char **out_b, const char **out_e)
{
    while (b < e) {
        uint32_t cp;
        size_t w;
        if (!utf8_cp(b, e, &cp, &w) || !ws_cp(cp)) break;
        b += w;
    }
    while (e > b) {
        const char *p = e - 1;
        while (p > b && ((unsigned char)*p & 0xC0u) == 0x80u) p--;
        uint32_t cp;
        size_t w;
        if (!utf8_cp(p, e, &cp, &w) || p + w != e || !ws_cp(cp)) break;
        e = p;
    }
    *out_b = b;
    *out_e = e;
}

/* Ровно четыре части по точке: пустая, длиннее трёх цифр или >255 --
   отказ. Ведущие нули разрешены ("01" -- это 1), как в Kotlin. */
static bool ipv4_range(const char *b, const char *e, uint32_t *out)
{
    uint32_t value = 0;
    const char *p = b;
    for (int i = 0; i < 4; i++) {
        const char *q = p;
        while (q < e && *q != '.') q++;
        const size_t len = (size_t)(q - p);
        if (len == 0 || len > 3) return false;
        unsigned oct = 0;
        for (size_t k = 0; k < len; k++) {
            const char c = p[k];
            if (c < '0' || c > '9') return false;
            oct = oct * 10u + (unsigned)(c - '0');
        }
        if (oct > 255) return false;
        value = (value << 8) | oct;
        if (i == 3) {
            if (q != e) return false;  // пятая часть
        } else {
            if (q == e || *q != '.') return false;
            p = q + 1;
        }
    }
    if (out) *out = value;
    return true;
}

/* То же с внешним срезом пробелов -- как parseIpv4() в Kotlin. */
static bool ipv4_str(const char *b, const char *e, uint32_t *out)
{
    const char *tb;
    const char *te;
    trim_ws(b, e, &tb, &te);
    return ipv4_range(tb, te, out);
}

/* Группы одного куска без "::": непустые токены по ':', каждый -- 1..4
   шестнадцатеричные цифры. cap -- предел числа групп: больше -- отказ,
   вызывающему так или иначе не хватило бы лимита. n -- фактическое
   число групп (только при успехе). */
static bool groups_range(const char *b, const char *e, uint16_t *out, int cap, int *n)
{
    int count = 0;
    if (b == e) {
        *n = 0;
        return true;
    }
    const char *p = b;
    for (;;) {
        const char *q = p;
        while (q < e && *q != ':') q++;
        const size_t len = (size_t)(q - p);
        if (len == 0 || len > 4) return false;
        unsigned g = 0;
        for (size_t k = 0; k < len; k++) {
            const char c = p[k];
            unsigned d;
            if (c >= '0' && c <= '9') d = (unsigned)(c - '0');
            else if (c >= 'a' && c <= 'f') d = (unsigned)(c - 'a') + 10u;
            else if (c >= 'A' && c <= 'F') d = (unsigned)(c - 'A') + 10u;
            else return false;
            g = (g << 4) | d;
        }
        if (count < cap) out[count] = (uint16_t)g;
        if (++count > cap) return false;
        if (q == e) break;
        p = q + 1;
    }
    *n = count;
    return true;
}

/* Разбор IPv6 по уже обрезанным границам: ASCII-строчное копирование,
   замена хвостового IPv4 на две группы (точно как подстановка
   "$hi:$lo" в Kotlin), затем ветвление по "::". */
static bool ipv6_range(const char *b, const char *e, uint16_t *out)
{
    if (b == e) return false;
    const size_t len = (size_t)(e - b);
    char *s = malloc(len + 16);  // с запасом под замену хвоста (+2)
    if (!s) return false;
    for (size_t i = 0; i < len; i++) {
        const char c = b[i];
        s[i] = (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c;
    }
    s[len] = '\0';

    if (strchr(s, '.')) {
        char *cut = strrchr(s, ':');
        if (!cut) { free(s); return false; }  // IPv4 без двоеточий -- не IPv6
        uint32_t v4;
        // parseIpv4() в Kotlin тоже срезает пробелы -- ":: 1.2.3.4" валидно.
        if (!ipv4_str(cut + 1, cut + 1 + strlen(cut + 1), &v4)) { free(s); return false; }
        snprintf(cut + 1, (size_t)(s + len + 16 - (cut + 1)), "%x:%x",
                 (unsigned)((v4 >> 16) & 0xFFFFu), (unsigned)(v4 & 0xFFFFu));
    }

    uint16_t groups[8];
    const char *run = strstr(s, "::");
    if (!run) {
        int n;
        if (!groups_range(s, s + strlen(s), groups, 8, &n) || n != 8) { free(s); return false; }
    } else {
        uint16_t head[8];
        uint16_t tail[8];
        int hn;
        int tn;
        if (!groups_range(s, run, head, 8, &hn)) { free(s); return false; }
        if (!groups_range(run + 2, s + strlen(s), tail, 7, &tn)) { free(s); return false; }
        if (hn + tn > 7) { free(s); return false; }  // "::" обязан занять группу
        int i = 0;
        for (int k = 0; k < hn; k++) groups[i++] = head[k];
        for (int k = hn + tn; k < 8; k++) groups[i++] = 0;
        for (int k = 0; k < tn; k++) groups[i++] = tail[k];
    }
    if (out) memcpy(out, groups, sizeof groups);
    free(s);
    return true;
}

/* То же с внешним срезом пробелов -- как parseIpv6() в Kotlin. */
static bool ipv6_str(const char *b, const char *e, uint16_t *out)
{
    const char *tb;
    const char *te;
    trim_ws(b, e, &tb, &te);
    return ipv6_range(tb, te, out);
}

/* Длина префикса как toIntOrNull() из Kotlin: один необязательный знак,
   только цифры, максимум Int.MAX_VALUE (переполнение -- отказ). */
static bool int_range(const char *b, const char *e, int *out)
{
    const size_t len = (size_t)(e - b);
    if (len == 0) return false;
    const bool neg = (b[0] == '-');
    size_t i = (b[0] == '+' || neg) ? 1 : 0;
    if (i == len) return false;
    long long v = 0;
    for (; i < len; i++) {
        const char c = b[i];
        if (c < '0' || c > '9') return false;
        v = v * 10 + (c - '0');
        if (v > 2147483647LL) return false;
    }
    *out = neg ? -(int)v : (int)v;
    return true;
}

// ---- публичный интерфейс ----

bool ip_parse_ipv4(const char *text, uint32_t *out)
{
    if (!text) return false;
    return ipv4_str(text, text + strlen(text), out);
}

bool ip_parse_ipv6(const char *text, uint16_t out[8])
{
    if (!text) return false;
    return ipv6_str(text, text + strlen(text), out);
}

bool ip_is_ip_literal(const char *text)
{
    return ip_parse_ipv4(text, NULL) || ip_parse_ipv6(text, NULL);
}

bool ip_cidr_contains(const char *cidr, const char *address)
{
    if (!cidr || !address) return false;
    const char *slash = strchr(cidr, '/');
    if (!slash || slash == cidr) return false;
    int bits;
    // Всё после первого '/' целиком: "8/9" -- мусор, а не префикс 8.
    if (!int_range(slash + 1, cidr + strlen(cidr), &bits)) return false;

    uint32_t ip4;
    uint32_t base4;
    if (ipv4_str(address, address + strlen(address), &ip4) &&
        ipv4_str(cidr, slash, &base4)) {
        if (bits < 0 || bits > 32) return false;
        if (bits == 0) return true;  // маска не считается: /0 ловит всё
        const uint32_t mask = 0xFFFFFFFFu << (32 - bits);
        return (ip4 & mask) == (base4 & mask);
    }

    uint16_t ip6[8];
    uint16_t base6[8];
    if (ipv6_str(address, address + strlen(address), ip6) &&
        ipv6_str(cidr, slash, base6)) {
        if (bits < 0 || bits > 128) return false;
        int left = bits;
        for (int i = 0; i < 8 && left > 0; i++) {
            const int take = left < 16 ? left : 16;
            const uint16_t mask = (uint16_t)(0xFFFFu >> (16 - take) << (16 - take));
            if ((ip6[i] & mask) != (base6[i] & mask)) return false;
            left -= take;
        }
        return true;
    }
    return false;
}
