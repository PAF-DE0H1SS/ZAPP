// Автономный тест TokenizeArgs/ExpandHosts: без фреймворков, один main.
// Ожидания выписаны трейсом ArgTokenizer.kt, а не интуицией.
#include "arg_tokenizer.h"

#include <cstdio>
#include <string>
#include <vector>

namespace {

int g_failures = 0;

std::string Show(const std::vector<std::string>& v) {
    std::string s = "[";
    for (size_t i = 0; i < v.size(); ++i) {
        if (i != 0) s += ", ";
        s += "\"" + v[i] + "\"";
    }
    return s + "]";
}

#define EXPECT_EQ(actual, expected)                                          \
    do {                                                                     \
        const auto a_ = (actual);                                            \
        const auto e_ = (expected);                                          \
        if (!(a_ == e_)) {                                                   \
            ++g_failures;                                                    \
            std::printf("FAIL %s:%d  %s\n  got      %s\n  expected %s\n",    \
                        __FILE__, __LINE__, #actual, Show(a_).c_str(),       \
                        Show(e_).c_str());                                   \
        }                                                                    \
    } while (0)

void TestTokenizeEmpty() {
    EXPECT_EQ(TokenizeArgs(""), std::vector<std::string>{});
    // Пробелы и табы одни -- пусто, пустые куски прочь.
    EXPECT_EQ(TokenizeArgs(" "), std::vector<std::string>{});
    EXPECT_EQ(TokenizeArgs("  \t \n \t  "), std::vector<std::string>{});
}

void TestTokenizeSpaces() {
    EXPECT_EQ(TokenizeArgs("--port 8080"),
              (std::vector<std::string>{"--port", "8080"}));
    // Пробелы подряд сливаются в один разделитель.
    EXPECT_EQ(TokenizeArgs("  --port \t 8080 \n"),
              (std::vector<std::string>{"--port", "8080"}));
    // Разделитель внутри токена не появляется.
    EXPECT_EQ(TokenizeArgs("a   b"), (std::vector<std::string>{"a", "b"}));
}

void TestTokenizeDoubleQuotes() {
    // Классическая задача: кавычки склеивают пробелы в один аргумент.
    EXPECT_EQ(TokenizeArgs("--file=\"my list.txt\""),
              (std::vector<std::string>{"--file=my list.txt"}));
    EXPECT_EQ(TokenizeArgs("\"a b\" \"c d\""),
              (std::vector<std::string>{"a b", "c d"}));
    // Пустые кавычки не порождают токен.
    EXPECT_EQ(TokenizeArgs("\"\""), std::vector<std::string>{});
    EXPECT_EQ(TokenizeArgs("x\"\"y"), (std::vector<std::string>{"xy"}));
    // Внутри двойных одинарная -- обычный символ.
    EXPECT_EQ(TokenizeArgs("\"it's\""), (std::vector<std::string>{"it's"}));
}

void TestTokenizeSingleQuotes() {
    EXPECT_EQ(TokenizeArgs("'--x y'"),
              (std::vector<std::string>{"--x y"}));
    // Внутри одинарных двойная -- обычный символ.
    EXPECT_EQ(TokenizeArgs("'say \"hi\"'"),
              (std::vector<std::string>{"say \"hi\""}));
    // Смешанные типы: одинарная не закрывает двойную.
    EXPECT_EQ(TokenizeArgs("\"a'b\" 'c\"d'"),
              (std::vector<std::string>{"a'b", "c\"d"}));
}

void TestTokenizeUnclosedQuote() {
    // Незакрытая кавычка не ошибка: накопленное становится токеном.
    EXPECT_EQ(TokenizeArgs("--x \"abc"),
              (std::vector<std::string>{"--x", "abc"}));
    EXPECT_EQ(TokenizeArgs("\"abc def"),
              (std::vector<std::string>{"abc def"}));
    EXPECT_EQ(TokenizeArgs("'abc"),
              (std::vector<std::string>{"abc"}));
    // Незакрытая кавычка держит хвост одним токеном до конца строки.
    EXPECT_EQ(TokenizeArgs("a \"b c d"),
              (std::vector<std::string>{"a", "b c d"}));
}

void TestTokenizeQuoteMidToken() {
    // Кавычка открывается даже посреди токена.
    EXPECT_EQ(TokenizeArgs("a\"b c\""),
              (std::vector<std::string>{"ab c"}));
    EXPECT_EQ(TokenizeArgs("pre\"two words\"post"),
              (std::vector<std::string>{"pretwo wordspost"}));
    // Кавычки без пробелов вокруг ничего не добавляют.
    EXPECT_EQ(TokenizeArgs("\"a\"b'c'"),
              (std::vector<std::string>{"abc"}));
}

void TestExpandHostsExactToken() {
    const std::vector<std::string> hosts{"one.com", "two.com"};
    EXPECT_EQ(ExpandHosts({"$HOSTS"}, hosts),
              (std::vector<std::string>{"one.com", "two.com"}));
    // Пустой список гасит даже точный токен.
    EXPECT_EQ(ExpandHosts({"$HOSTS"}, {}), std::vector<std::string>{});
}

void TestExpandHostsEmbedded() {
    const std::vector<std::string> hosts{"one.com", "two.com"};
    // Вплетённый -- один аргумент через пробел.
    EXPECT_EQ(ExpandHosts({"-H:$HOSTS"}, hosts),
              (std::vector<std::string>{"-H:one.com two.com"}));
    // Пустой список: токен исчезает, а не превращается в пустышку.
    EXPECT_EQ(ExpandHosts({"-H:$HOSTS"}, {}), std::vector<std::string>{});
    // Замена затрагивает все вхождения.
    EXPECT_EQ(ExpandHosts({"$HOSTS:$HOSTS"}, hosts),
              (std::vector<std::string>{"one.com two.com:one.com two.com"}));
}

void TestExpandHostsPlain() {
    // Без плейсхолдера -- копия, список хостов не важен.
    EXPECT_EQ(ExpandHosts({"--verbose", "-x"}, {"one.com"}),
              (std::vector<std::string>{"--verbose", "-x"}));
    EXPECT_EQ(ExpandHosts({"--verbose"}, {}), (std::vector<std::string>{"--verbose"}));
    EXPECT_EQ(ExpandHosts({}, {"one.com"}), std::vector<std::string>{});
}

void TestCombined() {
    const std::vector<std::string> hosts{"one.com", "two.com"};
    // Исходная строка с кавычками и плейсхолдером сразу в двух видах.
    const std::string input = "--up \"$HOSTS\" -H:$HOSTS --flag";
    EXPECT_EQ(TokenizeArgs(input),
              (std::vector<std::string>{"--up", "$HOSTS", "-H:$HOSTS", "--flag"}));
    EXPECT_EQ(ExpandHosts(TokenizeArgs(input), hosts),
              (std::vector<std::string>{"--up", "one.com", "two.com",
                                        "-H:one.com two.com", "--flag"}));
    // Пустой список хостов оставляет только плейсхолдер-независимые аргументы.
    EXPECT_EQ(ExpandHosts(TokenizeArgs(input), {}),
              (std::vector<std::string>{"--up", "--flag"}));
    // Кавычки, собранные вручную, тоже проходят через ExpandHosts без изменений.
    EXPECT_EQ(ExpandHosts(TokenizeArgs("--file=\"my list.txt\""), hosts),
              (std::vector<std::string>{"--file=my list.txt"}));
}

}  // namespace

int main() {
    TestTokenizeEmpty();
    TestTokenizeSpaces();
    TestTokenizeDoubleQuotes();
    TestTokenizeSingleQuotes();
    TestTokenizeUnclosedQuote();
    TestTokenizeQuoteMidToken();
    TestExpandHostsExactToken();
    TestExpandHostsEmbedded();
    TestExpandHostsPlain();
    TestCombined();

    if (g_failures != 0) {
        std::printf("%d check(s) failed\n", g_failures);
        return 1;
    }
    std::printf("OK\n");
    return 0;
}
