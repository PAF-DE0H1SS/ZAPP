package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// TestMain отправляет временные каталоги в /tmp/opencode, если он есть.
func TestMain(m *testing.M) {
	if st, err := os.Stat("/tmp/opencode"); err == nil && st.IsDir() {
		_ = os.Setenv("TMPDIR", "/tmp/opencode")
	}
	os.Exit(m.Run())
}

// ---------------------------------------------------------------------------
// Общие фикстуры
// ---------------------------------------------------------------------------

const goodStr = `package xyz.azraellab.zapp.core

/**
 * Строки интерфейса.
 */
enum class Str {
    /** Запись. */
    ALPHA,
    BETA;

    /** Перевод для языка. */
    fun of(lang: AppLang): String = when (this) {
        // комментарий между ветками
        ALPHA -> pick(
            lang,
            "a " +
                "b",
            "а",
            "甲"
        ) // хвостовой комментарий
        /* блочный
           комментарий */
        BETA -> "same"
    }
}
`

const goodManifest = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:name=".GoodApp">
        <activity android:name=".GoodActivity" />
        <activity-alias android:name=".Alias" android:targetActivity=".GoodActivity" />
        <service android:name="com.x.FullService" />
        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
        </intent-filter>
    </application>
</manifest>
`

// badManifest — тот же манифест с компонентом, которого нет в исходниках.
var badManifest = strings.Replace(goodManifest,
	`<activity android:name=".GoodActivity" />`,
	`<activity android:name=".GoodActivity" />
        <receiver android:name=".pkg.BadReceiver" />`, 1)

var goodSources = []sourceFile{
	{path: "APP/app/src/main/java/a/Good.kt", content: "package a\n\nclass GoodApp\nclass GoodActivity\n"},
}

const goodAppGradle = `android {
    namespace = "xyz.azraellab.zapp"

    defaultConfig {
        versionCode = 59
        versionName = zappVersion
    }
}
`

const goodComposeGradle = `kotlin {
    android {
        namespace = "xyz.azraellab.zapp.shared"
    }
}
`

const goodProps = `org.gradle.jvmargs=-Xmx2g
zappVersion=2.8.17
`

func noFails(t *testing.T, name string, fails []string) {
	t.Helper()
	if len(fails) != 0 {
		t.Errorf("%s: ожидалась чистка, нарушения: %v", name, fails)
	}
}

func wantFails(t *testing.T, name string, fails []string, n int, substr string) {
	t.Helper()
	if len(fails) != n {
		t.Errorf("%s: ожидалось %d нарушений, получено %d: %v", name, n, len(fails), fails)
		return
	}
	if substr != "" && !strings.Contains(strings.Join(fails, "\n"), substr) {
		t.Errorf("%s: нарушения не содержат %q: %v", name, substr, fails)
	}
}

// ---------------------------------------------------------------------------
// Разбор комментариев
// ---------------------------------------------------------------------------

func TestStripComments(t *testing.T) {
	src := "val u = \"http://x//y\" // хвост\n" +
		"val b = 1 /* блок\nещё */ + 2\n" +
		"/* влож /* */ */\n" +
		"val c = 'a'\n"
	got := stripComments(src)
	if len(got) != len(src) {
		t.Fatalf("длина изменилась: %d != %d", len(got), len(src))
	}
	if !strings.Contains(got, "http://x//y") {
		t.Errorf("строка URL разобрана как комментарий: %q", got)
	}
	for _, sub := range []string{"хвост", "блок", "ещё", "влож"} {
		if strings.Contains(got, sub) {
			t.Errorf("комментарий %q не вырезан: %q", sub, got)
		}
	}
	if !strings.Contains(got, "'a'") {
		t.Errorf("символьный литерал повреждён: %q", got)
	}
}

// ---------------------------------------------------------------------------
// 1. forbidden-tracked
// ---------------------------------------------------------------------------

func TestForbiddenTrackedClean(t *testing.T) {
	clean := []string{
		".github/workflows/build.yml",
		"README.md",
		"APP/app/build.gradle.kts",
		"native/patches/zapp-tun-fd.go",
	}
	fails, _ := checkForbiddenTracked(clean)
	noFails(t, "forbidden-tracked/чисто", fails)
}

func TestForbiddenTrackedDirty(t *testing.T) {
	dirty := []string{
		"README.md",
		"JOURNAL.md",
		"PLAN2.md",
		".idea/misc.xml",
		"scripts/build.sh",
		".devtest/log.txt",
		"APP/release.apk",
		"APP/qa/TESTLOG.md",
	}
	fails, _ := checkForbiddenTracked(dirty)
	wantFails(t, "forbidden-tracked/грязно", fails, 7, "JOURNAL.md")
	joined := strings.Join(fails, "\n")
	for _, sub := range []string{".idea/", "scripts/", ".devtest/", ".apk", "TESTLOG.md"} {
		if !strings.Contains(joined, sub) {
			t.Errorf("forbidden-tracked: нет находки %q в: %v", sub, fails)
		}
	}
}

// ---------------------------------------------------------------------------
// 2. manifest-classes
// ---------------------------------------------------------------------------

func TestManifestClassesClean(t *testing.T) {
	fails, info := checkManifestClasses(goodManifest, goodSources)
	noFails(t, "manifest-classes/чисто", fails)
	if !strings.Contains(info, "2") { // .GoodApp и .GoodActivity; alias и FQCN не считаются
		t.Errorf("manifest-classes: неожиданная справка %q", info)
	}
}

func TestManifestClassesMissing(t *testing.T) {
	fails, _ := checkManifestClasses(badManifest, nil)
	wantFails(t, "manifest-classes/нет исходников", fails, 3, "BadReceiver")
}

func TestManifestClassesBrokenRef(t *testing.T) {
	m := `<manifest><application android:name=".GoodApp"><receiver android:name=".Ghost" /></application></manifest>`
	fails, _ := checkManifestClasses(m, goodSources)
	wantFails(t, "manifest-classes/битая ссылка", fails, 1, ".Ghost")
}

func TestManifestClassesBrokenXML(t *testing.T) {
	fails, _ := checkManifestClasses("<manifest><application", goodSources)
	wantFails(t, "manifest-classes/битый xml", fails, 1, "не разбирается")
}

// ---------------------------------------------------------------------------
// 3. namespace-clash
// ---------------------------------------------------------------------------

func TestNamespaceClashClean(t *testing.T) {
	fails, _ := checkNamespaceClash(goodAppGradle, goodComposeGradle)
	noFails(t, "namespace-clash/чисто", fails)
}

func TestNamespaceClashEqual(t *testing.T) {
	app := `android { namespace = "xyz.azraellab.zapp" }`
	shared := `android { namespace = "xyz.azraellab.zapp" }`
	fails, _ := checkNamespaceClash(app, shared)
	wantFails(t, "namespace-clash/совпадение", fails, 1, "merger")
}

func TestNamespaceClashMissing(t *testing.T) {
	fails, _ := checkNamespaceClash("android {}\n", goodComposeGradle)
	wantFails(t, "namespace-clash/нет namespace", fails, 1, "APP/app/build.gradle.kts")
}

// ---------------------------------------------------------------------------
// 4. str-bijection
// ---------------------------------------------------------------------------

func TestStrBijectionClean(t *testing.T) {
	fails, info := checkStrBijection(goodStr)
	noFails(t, "str-bijection/чисто", fails)
	if !strings.Contains(info, "2 ключей, 2 веток") {
		t.Errorf("str-bijection: неожиданная справка %q", info)
	}
}

func TestStrBijectionMissingBranch(t *testing.T) {
	src := `enum class Str {
    ALPHA,
    BETA;

    fun of(lang: AppLang): String = when (this) {
        ALPHA -> pick(lang, "a", "а", "甲")
    }
}`
	fails, _ := checkStrBijection(src)
	wantFails(t, "str-bijection/пропущенная ветка", fails, 1, "BETA")
}

func TestStrBijectionExtraBranch(t *testing.T) {
	src := `enum class Str {
    ALPHA;

    fun of(lang: AppLang): String = when (this) {
        ALPHA -> pick(lang, "a", "а", "甲")
        GHOST -> pick(lang, "g", "г", "鬼")
    }
}`
	fails, _ := checkStrBijection(src)
	wantFails(t, "str-bijection/ветка без записи", fails, 1, "GHOST")
}

// ---------------------------------------------------------------------------
// 5. str-pick-arity
// ---------------------------------------------------------------------------

func TestStrPickArityClean(t *testing.T) {
	fails, _ := checkStrPickArity(goodStr)
	noFails(t, "str-pick-arity/чисто", fails)
}

func TestStrPickArityBad(t *testing.T) {
	src := `enum class Str {
    LONG_ONE,
    SHORT_PICK,
    DYN_ARG,
    NOT_PICK,
    CONST_ONE;

    fun of(lang: AppLang): String = when (this) {
        LONG_ONE -> pick(
            lang,
            "part one " +
                "part two",
            "часть",
            "部分"
        )
        SHORT_PICK -> pick(lang, "a", "б")
        DYN_ARG -> pick(lang, lang.name, "б", "в")
        NOT_PICK -> computeValue()
        CONST_ONE -> "same"
    }
}`
	fails, _ := checkStrPickArity(src)
	wantFails(t, "str-pick-arity/нарушения", fails, 3, "SHORT_PICK")
	joined := strings.Join(fails, "\n")
	for _, sub := range []string{"DYN_ARG", "NOT_PICK"} {
		if !strings.Contains(joined, sub) {
			t.Errorf("str-pick-arity: нет находки %q в: %v", sub, fails)
		}
	}
	if strings.Contains(joined, "CONST_ONE") {
		t.Errorf("str-pick-arity: ложное срабатывание на литерале: %v", fails)
	}
	if strings.Contains(joined, "LONG_ONE") {
		t.Errorf("str-pick-arity: ложное срабатывание на многострочном pick: %v", fails)
	}
}

// ---------------------------------------------------------------------------
// 6. settings-paths
// ---------------------------------------------------------------------------

func TestIsSettingsPath(t *testing.T) {
	cases := map[string]bool{
		"xyz/azraellab/zapp/ui/Theme.kt":      true,
		"xyz/azraellab/zapp/ui/pages/Page.kt": true,
		"pkg/ui/sub/Deep.kt":                  true,
		"xyz/core/uihelper/Helper.kt":         false,
		"xyz/uitools/Tool.kt":                 false,
		"xyz/ui.kt":                           false,
		"xyz/core/Str.kt":                     false,
	}
	for rel, want := range cases {
		if got := isSettingsPath(rel); got != want {
			t.Errorf("isSettingsPath(%q) = %v, ожидалось %v", rel, got, want)
		}
	}
}

func TestSettingsPathsClean(t *testing.T) {
	files := []sourceFile{
		{path: "a/First.kt", content: `
fun FirstStep() {
    Btn(path = "step.next")
    Btn(path = "step.next")
}

fun SecondStep() {
    Btn(path = "step.next")
    Btn(path = "step.ok")
}
`},
		{path: "a/Bad.kt", content: `
fun F() {
    Btn(path = "")
    Btn(path = "has space")
    Btn(path = "dyn.${x}")
    Btn(path = literalOnly)
}
`},
	}
	fails, info := checkSettingsPaths(files)
	// дубль step.next в FirstStep + пустой путь + путь с пробелом
	wantFails(t, "settings-paths/нарушения", fails, 3, "дубль")
	joined := strings.Join(fails, "\n")
	if strings.Contains(joined, "dyn.") || strings.Contains(joined, "literalOnly") {
		t.Errorf("settings-paths: ложное срабатывание на шаблоне/идентификаторе: %v", fails)
	}
	if !strings.Contains(info, "6 путей") || !strings.Contains(info, "шаблонов пропущено: 1") {
		t.Errorf("settings-paths: неожиданная справка %q", info)
	}
}

func TestSettingsPathsSamePathDifferentFuns(t *testing.T) {
	files := []sourceFile{
		{path: "a/Screen.kt", content: `
fun StepA() { Btn(path = "shared.next") }
fun StepB() { Btn(path = "shared.next") }
`},
	}
	fails, _ := checkSettingsPaths(files)
	noFails(t, "settings-paths/разные функции", fails)
}

// ---------------------------------------------------------------------------
// 7. versions
// ---------------------------------------------------------------------------

func TestVersionsClean(t *testing.T) {
	fails, _ := checkVersions(goodProps, goodAppGradle)
	noFails(t, "versions/чисто", fails)
}

func TestVersionsBad(t *testing.T) {
	cases := []struct {
		name       string
		props      string
		gradle     string
		wantFails  int
		wantSubstr string
	}{
		{"короткая версия", "zappVersion=2.8\n", goodAppGradle, 1, "X.Y.Z"},
		{"нет версии", "# zappVersion=1.2.3\n", goodAppGradle, 1, "не найден"},
		{"versionCode = 0", goodProps, strings.Replace(goodAppGradle, "59", "0", 1), 1, "versionCode"},
		{"versionCode не целое", goodProps, strings.Replace(goodAppGradle, "59", "abc", 1), 1, "versionCode"},
		{"versionName литерал", goodProps, strings.Replace(goodAppGradle, "zappVersion", `"1.2.3"`, 1), 1, "versionName"},
		{"нет versionCode", goodProps, "android { namespace = \"x.y\" }\n", 2, "versionCode"},
	}
	for _, c := range cases {
		fails, _ := checkVersions(c.props, c.gradle)
		wantFails(t, "versions/"+c.name, fails, c.wantFails, c.wantSubstr)
	}
}

// ---------------------------------------------------------------------------
// CLI: коды выхода
// ---------------------------------------------------------------------------

// writeTestRepo собирает минимальный чистый репозиторий ZAPP во временном каталоге.
func writeTestRepo(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	write := func(rel, content string) {
		p := filepath.Join(root, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("APP/app/src/main/AndroidManifest.xml", `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name=".GoodApp">
        <activity android:name=".GoodActivity" />
    </application>
</manifest>
`)
	write("APP/app/src/main/java/a/App.kt", "class GoodApp\nclass GoodActivity\n")
	write("APP/composeApp/src/commonMain/kotlin/xyz/azraellab/zapp/core/Str.kt", goodStr)
	write("APP/app/build.gradle.kts", goodAppGradle)
	write("APP/composeApp/build.gradle.kts", goodComposeGradle)
	write("APP/gradle.properties", goodProps)
	write("APP/composeApp/src/commonMain/kotlin/xyz/azraellab/zapp/ui/Main.kt", `
fun MainScreen() {
    Btn(path = "main.ok")
}
`)
	write("README.md", "# ZAPP\n")
	return root
}

func gitRun(t *testing.T, root string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", append([]string{"-C", root}, args...)...)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git %v: %v (%s)", args, err, out)
	}
}

func TestRunExitCodes(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git не найден")
	}

	var out, errOut strings.Builder
	if code := run([]string{filepath.Join(t.TempDir(), "нет-такого")}, &out, &errOut); code != exitError {
		t.Errorf("нет каталога: код %d, ожидалось %d", code, exitError)
	}

	out.Reset()
	errOut.Reset()
	if code := run([]string{"a", "b"}, &out, &errOut); code != exitError {
		t.Errorf("лишний аргумент: код %d, ожидалось %d", code, exitError)
	}

	root := writeTestRepo(t)
	gitRun(t, root, "init", "-q")
	gitRun(t, root, "add", "-A")

	out.Reset()
	errOut.Reset()
	if code := run([]string{root}, &out, &errOut); code != exitClean {
		t.Errorf("чистый репозиторий: код %d, ожидалось %d\nвывод:\n%s\nошибки:\n%s",
			code, exitClean, out.String(), errOut.String())
	}
	if !strings.Contains(out.String(), "нарушений нет") {
		t.Errorf("нет итоговой строки: %q", out.String())
	}

	// Локальный документ в индексе — нарушения, код 1.
	journal := filepath.Join(root, "JOURNAL.md")
	if err := os.WriteFile(journal, []byte("журнал\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	gitRun(t, root, "add", "-f", "JOURNAL.md")

	out.Reset()
	errOut.Reset()
	if code := run([]string{root}, &out, &errOut); code != exitViolations {
		t.Errorf("грязный репозиторий: код %d, ожидалось %d\nвывод:\n%s", code, exitViolations, out.String())
	}
	if !strings.Contains(out.String(), "forbidden-tracked") || !strings.Contains(out.String(), "JOURNAL.md") {
		t.Errorf("вывод не содержит forbidden-tracked: %q", out.String())
	}
}

func TestRunNotARepo(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git не найден")
	}
	dir := t.TempDir()
	var out, errOut strings.Builder
	if code := run([]string{dir}, &out, &errOut); code != exitError {
		t.Errorf("каталог вне git: код %d, ожидалось %d (%s)", code, exitError, errOut.String())
	}
}
