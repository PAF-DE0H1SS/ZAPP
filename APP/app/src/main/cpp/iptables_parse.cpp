#include "iptables_parse.h"

#include <sstream>

namespace {

constexpr const char kPrefix[] = "-A ZAPP_DPI";

std::string Trim(const std::string& s) {
    const size_t begin = s.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) return "";
    const size_t end = s.find_last_not_of(" \t\r\n");
    return s.substr(begin, end - begin + 1);
}

}  // namespace

std::vector<std::string> ParseChainRules(const std::string& output) {
    std::vector<std::string> rules;
    std::istringstream stream(output);
    std::string line;
    const size_t prefixLen = sizeof(kPrefix) - 1;  // без завершающего NUL
    while (std::getline(stream, line)) {
        line = Trim(line);
        // Тот же startsWith, что у Kotlin-фолбэка: префикс обязан
        // совпасть целиком, тело -- всё, что за ним (обрезанное).
        if (line.compare(0, prefixLen, kPrefix) != 0) continue;
        std::string body = Trim(line.substr(prefixLen));
        if (body.empty()) continue;
        rules.push_back(body);
    }
    return rules;
}
