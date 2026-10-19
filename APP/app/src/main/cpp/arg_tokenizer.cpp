#include "arg_tokenizer.h"

namespace {

// Kotlin-шный Char.isWhitespace(); <cctype> не тянем, чтобы держать
// набор заголовков ровно <string> + <vector>.
bool IsSpace(char ch) {
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' ||
           ch == '\f' || ch == '\v';
}

}  // namespace

std::vector<std::string> TokenizeArgs(const std::string& input) {
    std::vector<std::string> out;
    std::string cur;
    char quote = ' ';  // ' ' -- вне кавычки

    for (char ch : input) {
        if (quote != ' ') {
            // Внутри кавычки: свой символ её закрывает, любой другой
            // (включая пробелы и второй тип кавычки) копируется.
            if (ch == quote) {
                quote = ' ';
            } else {
                cur.push_back(ch);
            }
        } else if (ch == '"' || ch == '\'') {
            // Открытие кавычки возможно и посреди токена: a"b c" -> "ab c".
            quote = ch;
        } else if (IsSpace(ch)) {
            if (!cur.empty()) {
                out.push_back(cur);
                cur.clear();
            }
        } else {
            cur.push_back(ch);
        }
    }

    // Незакрытая кавычка не ошибка, просто хвост становится токеном;
    // пустой хвост не добавляем.
    if (!cur.empty()) out.push_back(cur);
    return out;
}

std::vector<std::string> ExpandHosts(const std::vector<std::string>& tokens,
                                     const std::vector<std::string>& hosts) {
    static const std::string kPlaceholder = "$HOSTS";

    std::string joined;
    for (size_t i = 0; i < hosts.size(); ++i) {
        if (i != 0) joined.push_back(' ');
        joined += hosts[i];
    }

    std::vector<std::string> out;
    for (const std::string& token : tokens) {
        if (token == kPlaceholder) {
            // Разворачивается в отдельные аргументы; пустой список
            // гасит токен, а не оставляет пустышку.
            for (const std::string& host : hosts) out.push_back(host);
        } else if (token.find(kPlaceholder) != std::string::npos) {
            if (hosts.empty()) {
                continue;  // вплетённый при пустом списке -- исчезает
            }
            // Замена всех вхождений одной строкой: `-H:$HOSTS` остаётся
            // одним аргументом с пробелами внутри.
            std::string expanded;
            size_t pos = 0;
            while (true) {
                size_t hit = token.find(kPlaceholder, pos);
                if (hit == std::string::npos) {
                    expanded += token.substr(pos);
                    break;
                }
                expanded.append(token, pos, hit - pos);
                expanded += joined;
                pos = hit + kPlaceholder.size();
            }
            out.push_back(expanded);
        } else {
            out.push_back(token);
        }
    }
    return out;
}
