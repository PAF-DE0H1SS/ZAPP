cmake_minimum_required(VERSION 3.22.1)

project(zapp-traffic LANGUAGES CXX)

# Имя библиотеки совпадает с тем, что ищет System.loadLibrary("zapp-traffic")
# в NativeTraffic.kt. Переименование здесь сломает мост тихо, поэтому при
# правке имени нужно править и Kotlin.
add_library(zapp-traffic SHARED traffic.cpp)

target_compile_features(zapp-traffic PRIVATE cxx_std_17)

# Минимальный набор: <cstdio> для snprintf, <string> для std::string,
# <fstream> для чтения /proc, <sys/stat.h> для stat. Никаких зависимостей
# с внешними библиотеками, потому что всё системное.
find_library(log-lib log)

target_link_libraries(zapp-traffic
    ${log-lib}
)
