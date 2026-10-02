// Нативный счётчик трафика для Android.
//
// Приложение читает системные счётчики часто и мелким шагом, поэтому разбор
// вынесен в C++: в JNI нет боксинга Long на каждом вызове, а сам файл
// кешируется и перечитывается только когда изменилось время модификации.
//
// Формат вызова из Kotlin:
//   NativeTraffic.kt -> nativeReadCsv() -> здесь
//
// Наружу отдаётся CSV-строка, по строке на интерфейс:
//   имя,rx,tx,dropped
//
// Почему строка, а не массив чисел: вариант "плоский long[] с указателями на
// jstring" выглядит экономнее, но требует держать локальные ссылки JNI живыми
// между вызовами. Это висячие указатели и падение при следующем чтении, что
// проще не заводить вовсе. CSV не боксится и разбирается в Kotlin за один
// split, чего для сотни интерфейсов более чем достаточно.

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <sstream>
#include <string>
#include <vector>
#include <fstream>
#include <sys/stat.h>
#include <time.h>

#define LOG_TAG "ZappTraffic"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kProcNetDev = "/proc/net/dev";

struct Iface {
    std::string name;
    int64_t rx = 0;
    int64_t tx = 0;
    int64_t dropped = 0;
    bool up = false;
};

// Кеш интерфейсов. Перечитывается, когда изменилось время модификации файла.
std::vector<Iface> g_cache;
time_t g_mtime = 0;

bool IsTunnelled(const std::string& name) {
    // Служебные интерфейсы не показываем в разбивке: они дублируют трафик,
    // который уже учтён на обычном интерфейсе.
    static const char* prefixes[] = {"lo", "tun", "wg", "ip6tnl", "ppp"};
    for (const char* prefix : prefixes) {
        if (name.rfind(prefix, 0) == 0) return true;
    }
    return false;
}

std::string Trim(const std::string& s) {
    const size_t begin = s.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) return "";
    const size_t end = s.find_last_not_of(" \t\r\n");
    return s.substr(begin, end - begin + 1);
}

// Разбирает строку вида " eth0: 123 456 ... 789 ..."
bool ParseLine(const std::string& line, Iface* out) {
    const size_t colon = line.find(':');
    if (colon == std::string::npos) return false;

    out->name = Trim(line.substr(0, colon));
    if (out->name.empty()) return false;

    // Колонки приёма: 0 bytes, 1 packets, 2 errs, 3 drop, 4 fifo, 5 frame,
    // 6 compressed, 7 multicast. Затем те же восемь колонок передачи.
    std::istringstream stream(line.substr(colon + 1));
    int64_t rx = 0, rxPackets = 0, rxErrs = 0, rxDrop = 0;
    int64_t fifo = 0, frame = 0, compressed = 0, multicast = 0;
    int64_t tx = 0;
    if (!(stream >> rx >> rxPackets >> rxErrs >> rxDrop >> fifo >> frame >>
          compressed >> multicast >> tx)) {
        return false;
    }

    out->rx = rx;
    out->tx = tx;
    out->dropped = rxDrop;
    out->up = (rx > 0 || tx > 0);
    return true;
}

void Refresh(bool force) {
    struct stat st;
    if (stat(kProcNetDev, &st) != 0) {
        g_cache.clear();
        g_mtime = 0;
        return;
    }
    if (!force && st.st_mtime == g_mtime && !g_cache.empty()) return;

    std::ifstream stream(kProcNetDev);
    if (!stream.is_open()) {
        g_cache.clear();
        g_mtime = 0;
        return;
    }

    std::vector<Iface> parsed;
    std::string line;
    int seen = 0;
    while (std::getline(stream, line)) {
        // Первые две строки -- заголовки таблицы.
        if (++seen <= 2) continue;
        Iface iface;
        if (ParseLine(line, &iface)) parsed.push_back(iface);
    }

    g_cache = std::move(parsed);
    g_mtime = st.st_mtime;
}

std::string BuildCsv() {
    std::string out;
    out.reserve(g_cache.size() * 48);
    char buffer[64];
    for (const Iface& iface : g_cache) {
        out += iface.name;
        out += ',';
        snprintf(buffer, sizeof(buffer), "%lld,%lld,%lld\n",
                 static_cast<long long>(iface.rx),
                 static_cast<long long>(iface.tx),
                 static_cast<long long>(iface.dropped));
        out += buffer;
    }
    return out;
}

}  // namespace

extern "C" {

/**
 * Возвращает счётчики всех интерфейсов одной CSV-строкой.
 *
 * Функция не бросает исключений: мониторинг трафика не должен ронять
 * приложение, поэтому любая ошибка превращается в пустую строку.
 */
JNIEXPORT jstring JNICALL
Java_xyz_azraellab_zapp_core_NativeTraffic_nativeReadCsv(JNIEnv* env, jclass) {
    Refresh(false);
    return env->NewStringUTF(BuildCsv().c_str());
}

/** Монотонное время в миллисекундах с момента загрузки библиотеки. */
JNIEXPORT jlong JNICALL
Java_xyz_azraellab_zapp_core_NativeTraffic_nativeNowMs(JNIEnv*, jclass) {
    static int64_t start = 0;
    if (start == 0) {
        timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        start = static_cast<int64_t>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
    }
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    const int64_t now = static_cast<int64_t>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
    return now - start;
}

/** Сброс кеша; нужен после выхода из suspend. */
JNIEXPORT void JNICALL
Java_xyz_azraellab_zapp_core_NativeTraffic_nativeInvalidate(JNIEnv*, jclass) {
    Refresh(true);
}

}  // extern "C"
