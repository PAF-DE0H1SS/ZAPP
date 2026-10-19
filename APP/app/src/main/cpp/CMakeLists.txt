cmake_minimum_required(VERSION 3.22.1)

project(zapp-traffic LANGUAGES C CXX)

# Имя библиотеки совпадает с тем, что ищет System.loadLibrary("zapp-traffic")
# в NativeTraffic.kt. Переименование здесь ломает мост тихо, поэтому при
# правке имени нужно править и Kotlin. Тот же .so несёт и JNI-мост к
# Rust-ядру (ZappNative.kt грузит эту библиотеку и на десктопе -- там
# она собирается отдельной задачей из тех же исходников).
# arg_tokenizer.cpp -- токенизатор аргументов (C++), ipmath.c -- IP/CIDR
# математика (чистый C, без jni.h).
add_library(zapp-traffic SHARED
    traffic.cpp zapp_jni.cpp iptables_parse.cpp arg_tokenizer.cpp ipmath.c
)

target_compile_features(zapp-traffic PRIVATE cxx_std_17)

# Минимальный набор: <cstdio> для snprintf, <string> для std::string,
# <fstream> для чтения /proc, <sys/stat.h> для stat. Никаких зависимостей
# с внешними библиотеками, потому что всё системное.
find_library(log-lib log)

# --- Rust-ядро (native/rust/zapp-core) --------------------------------
#
# Статика собирается cargo'й ДО конфигурации: задачу configureCMake*
# Gradle вешает на cargoBuildAndroidNative (см. app/build.gradle.kts).
# Каталог -- build/rust/<трiple>/libzapp_core.a, там же, где его
# кладёт та задача; ABI здесь же, как его знает CMake.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET armv7-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET i686-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET x86_64-linux-android)
else()
    message(FATAL_ERROR "Неизвестный ABI для Rust-ядра: ${ANDROID_ABI}")
endif()

add_library(zapp_core STATIC IMPORTED)
set_target_properties(zapp_core PROPERTIES
    IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../build/rust/${RUST_TARGET}/libzapp_core.a
)

target_link_libraries(zapp-traffic
    ${log-lib}
    zapp_core
)
