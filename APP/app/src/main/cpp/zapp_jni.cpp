// C++-мост JNI: Kotlin -> здесь -> Rust (native/rust/zapp-core).
//
// Единственный файл, который знает про jni.h: маршalling (массивы
// строк туда, план обратно) и линковка статики Rust в общую .so.
// Свой логики почти нет -- разбор iptables живёт в iptables_parse.cpp,
// решение о сверке в ржавом ядре; здесь только мост и владение
// буферами на границе.
//
// Контракт с Kotlin (ZappNative.kt):
//   nativeParseChain(output)      -> String[] тел правил; не бросает.
//   nativePlanReconcile(d, a)     -> String[] вида "+rule"/"-rule";
//                                    null -- формат не собрался,
//                                    вызывающий уходит в Kotlin-фолбэк.
//   nativeSha256Hex(bytes)        -> 64 hex-символа; null -- нет ядра.
//   nativeSha256File(path)        -> хеш файла; null -- нет ядра
//                                    или файл не читается (фолбэк).
//   nativeTokenizeArgs(input)     -> аргументы пользовательской строки.
//   nativeExpandHosts(t, h)       -> подстановка $HOSTS.
//   nativeParseIpv4(text)         -> UInt как Long, -1 -- невалидно.
//   nativeParseIpv6(text)         -> 8 групп; null -- невалидно.
//   nativeIsIpLiteral(text)       -> литерал IP, а не домен.
//   nativeCidrContains(c, a)      -> адрес в подсети.
//
// Символы JNI по имени класса (как у traffic.cpp): статический или
// instance-метод -- не важно, обе стороны ABI идентичны, второй
// аргумент (jclass/jobject) не используется.

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "arg_tokenizer.h"
#include "ipmath.h"
#include "iptables_parse.h"

extern "C" {
// native/rust/zapp-core: план сверки, освобождение -- zapp_string_free.
char* zapp_reconcile_plan(const char* const* desired, size_t desired_len,
                          const char* const* actual, size_t actual_len);
void zapp_string_free(char* p);
// native/rust/zapp-core: sha256 байтов и файла (null при ошибке).
char* zapp_sha256_hex(const unsigned char* data, size_t len);
char* zapp_sha256_file(const char* path);
}

namespace {

// jstring -> std::string; null -> пустая строка (Kotlin шлёт только
// непустые списки, пустая строка лучше падения на границе).
std::string ToStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return "";
    const char* raw = env->GetStringUTFChars(s, nullptr);
    if (raw == nullptr) return "";
    std::string out(raw);
    env->ReleaseStringUTFChars(s, raw);
    return out;
}

// Java String[] -> вектор строк; null-массив -- пустой набор.
std::vector<std::string> ToVector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(ToStd(env, item));
        if (item != nullptr) env->DeleteLocalRef(item);
    }
    return out;
}

// Текст -> Java String[] (новые ссылки освобождаются сразу).
jobjectArray ToStringArray(JNIEnv* env, const std::vector<std::string>& items) {
    jclass stringCls = env->FindClass("java/lang/String");
    jobjectArray arr =
        env->NewObjectArray(static_cast<jsize>(items.size()), stringCls, nullptr);
    for (size_t i = 0; i < items.size(); i++) {
        jstring s = env->NewStringUTF(items[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeParseChain(
    JNIEnv* env, jclass, jstring output) {
    const std::string text = ToStd(env, output);
    return ToStringArray(env, ParseChainRules(text));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativePlanReconcile(
    JNIEnv* env, jclass, jobjectArray jDesired, jobjectArray jActual) {
    const auto desired = ToVector(env, jDesired);
    const auto actual = ToVector(env, jActual);

    // Указатели живут в векторах строк до возврата Rust.
    std::vector<const char*> dptr;
    dptr.reserve(desired.size());
    for (const auto& s : desired) dptr.push_back(s.c_str());
    std::vector<const char*> aptr;
    aptr.reserve(actual.size());
    for (const auto& s : actual) aptr.push_back(s.c_str());

    char* joined =
        zapp_reconcile_plan(dptr.data(), desired.size(), aptr.data(), actual.size());
    if (joined == nullptr) return nullptr;  // формат не собрался -- фолбэк в Kotlin

    // План: строки "+rule"/"-rule" через \n -> Java String[].
    std::vector<std::string> mutations;
    const char* p = joined;
    while (*p != '\0') {
        const char* nl = strchr(p, '\n');
        const size_t len = nl == nullptr ? strlen(p) : static_cast<size_t>(nl - p);
        if (len > 0) mutations.emplace_back(p, len);
        if (nl == nullptr) break;
        p = nl + 1;
    }
    zapp_string_free(joined);
    return ToStringArray(env, mutations);
}

extern "C" JNIEXPORT jstring JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeSha256Hex(
    JNIEnv* env, jclass, jbyteArray bytes) {
    if (bytes == nullptr) return nullptr;
    const jsize len = env->GetArrayLength(bytes);
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (data == nullptr) return nullptr;
    char* hex = zapp_sha256_hex(reinterpret_cast<const unsigned char*>(data),
                                static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    if (hex == nullptr) return nullptr;
    jstring out = env->NewStringUTF(hex);
    zapp_string_free(hex);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeSha256File(
    JNIEnv* env, jclass, jstring path) {
    const std::string file = ToStd(env, path);
    if (file.empty()) return nullptr;
    char* hex = zapp_sha256_file(file.c_str());
    if (hex == nullptr) return nullptr;
    jstring out = env->NewStringUTF(hex);
    zapp_string_free(hex);
    return out;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeTokenizeArgs(
    JNIEnv* env, jclass, jstring input) {
    return ToStringArray(env, TokenizeArgs(ToStd(env, input)));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeExpandHosts(
    JNIEnv* env, jclass, jobjectArray jTokens, jobjectArray jHosts) {
    const auto tokens = ToVector(env, jTokens);
    const auto hosts = ToVector(env, jHosts);
    return ToStringArray(env, ExpandHosts(tokens, hosts));
}

extern "C" JNIEXPORT jlong JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeParseIpv4(
    JNIEnv* env, jclass, jstring text) {
    const std::string s = ToStd(env, text);
    uint32_t value = 0;
    if (!ip_parse_ipv4(s.c_str(), &value)) return -1;
    return static_cast<jlong>(value);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeParseIpv6(
    JNIEnv* env, jclass, jstring text) {
    const std::string s = ToStd(env, text);
    uint16_t groups[8];
    if (!ip_parse_ipv6(s.c_str(), groups)) return nullptr;
    jint jgroups[8];
    for (int i = 0; i < 8; i++) jgroups[i] = static_cast<jint>(groups[i]);
    jintArray out = env->NewIntArray(8);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 8, jgroups);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeIsIpLiteral(
    JNIEnv* env, jclass, jstring text) {
    const std::string s = ToStd(env, text);
    return ip_is_ip_literal(s.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_xyz_azraellab_zapp_core_native_ZappNative_nativeCidrContains(
    JNIEnv* env, jclass, jstring jCidr, jstring jAddress) {
    const std::string cidr = ToStd(env, jCidr);
    const std::string address = ToStd(env, jAddress);
    return ip_cidr_contains(cidr.c_str(), address.c_str()) ? JNI_TRUE : JNI_FALSE;
}
