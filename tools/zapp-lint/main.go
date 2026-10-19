// zapp-lint — QA-линтер репозитория ZAPP: семь проверок для локального
// запуска и для CI. Только стандартная библиотека.
//
// Использование:
//
//	zapp-lint [корень репо]
//
// Коды выхода: 0 — нарушений нет, 1 — есть нарушения, 2 — ошибка запуска.
package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
)

const (
	exitClean      = 0
	exitViolations = 1
	exitError      = 2
)

// checkResult — результат одной проверки: имя, краткая справка, нарушения.
type checkResult struct {
	name  string
	info  string
	fails []string
}

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

// run — тело CLI, вынесено ради тестируемости кодов выхода.
func run(args []string, stdout, stderr io.Writer) int {
	if len(args) > 1 {
		fmt.Fprintln(stderr, "использование: zapp-lint [корень репо]")
		return exitError
	}
	root := "."
	if len(args) == 1 {
		root = args[0]
	}
	st, err := os.Stat(root)
	if err != nil || !st.IsDir() {
		fmt.Fprintf(stderr, "нет каталога: %s\n", root)
		return exitError
	}
	results, err := lint(root)
	if err != nil {
		fmt.Fprintf(stderr, "ошибка запуска: %v\n", err)
		return exitError
	}
	total := 0
	for _, r := range results {
		if len(r.fails) == 0 {
			fmt.Fprintf(stdout, "%-18s ok   %s\n", r.name, r.info)
			continue
		}
		total += len(r.fails)
		fmt.Fprintf(stdout, "%-18s fail (%d)\n", r.name, len(r.fails))
		for _, f := range r.fails {
			fmt.Fprintf(stdout, "    - %s\n", f)
		}
	}
	if total > 0 {
		fmt.Fprintf(stdout, "нарушений: %d\n", total)
		return exitViolations
	}
	fmt.Fprintln(stdout, "нарушений нет")
	return exitClean
}

// lint — читает нужные файлы репозитория и прогоняет все проверки по порядку.
func lint(root string) ([]checkResult, error) {
	tracked, err := gitLsFiles(root)
	if err != nil {
		return nil, err
	}
	manifest, err := readFile(root, filepath.Join("APP", "app", "src", "main", "AndroidManifest.xml"))
	if err != nil {
		return nil, err
	}
	strSrc, err := readFile(root, filepath.Join("APP", "composeApp", "src", "commonMain",
		"kotlin", "xyz", "azraellab", "zapp", "core", "Str.kt"))
	if err != nil {
		return nil, err
	}
	appGradle, err := readFile(root, filepath.Join("APP", "app", "build.gradle.kts"))
	if err != nil {
		return nil, err
	}
	composeGradle, err := readFile(root, filepath.Join("APP", "composeApp", "build.gradle.kts"))
	if err != nil {
		return nil, err
	}
	props, err := readFile(root, filepath.Join("APP", "gradle.properties"))
	if err != nil {
		return nil, err
	}

	sources, err := collectKotlin(root, filepath.Join("APP", "app", "src"), nil)
	if err != nil {
		return nil, err
	}
	more, err := collectKotlin(root, filepath.Join("APP", "composeApp", "src"), nil)
	if err != nil {
		return nil, err
	}
	sources = append(sources, more...)

	settingsRoot := filepath.Join("APP", "composeApp", "src", "commonMain", "kotlin")
	settingsFiles, err := collectKotlin(root, settingsRoot, isSettingsPath)
	if err != nil {
		return nil, err
	}
	if len(settingsFiles) == 0 {
		return nil, fmt.Errorf("не найдены файлы в %s/**/ui/**/*.kt", settingsRoot)
	}

	f1, i1 := checkForbiddenTracked(tracked)
	f2, i2 := checkManifestClasses(manifest, sources)
	f3, i3 := checkNamespaceClash(appGradle, composeGradle)
	f4, i4 := checkStrBijection(strSrc)
	f5, i5 := checkStrPickArity(strSrc)
	f6, i6 := checkSettingsPaths(settingsFiles)
	f7, i7 := checkVersions(props, appGradle)

	return []checkResult{
		{"forbidden-tracked", i1, f1},
		{"manifest-classes", i2, f2},
		{"namespace-clash", i3, f3},
		{"str-bijection", i4, f4},
		{"str-pick-arity", i5, f5},
		{"settings-paths", i6, f6},
		{"versions", i7, f7},
	}, nil
}

// readFile читает файл относительно корня репо; отсутствие файла — ошибка запуска.
func readFile(root, rel string) (string, error) {
	b, err := os.ReadFile(filepath.Join(root, rel))
	if err != nil {
		return "", fmt.Errorf("%s: %w", rel, err)
	}
	return string(b), nil
}
