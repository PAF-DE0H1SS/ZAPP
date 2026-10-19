// checks.go — правила линта и разборщики исходников ZAPP.
//
// Все проверки — чистые функции над строками: тесты скармливают им фикстуры,
// а lint() подставляет реальные файлы репозитория.
package main

import (
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// sourceFile — содержимое файла с путём относительно корня репо.
type sourceFile struct {
	path    string
	content string
}

// ---------------------------------------------------------------------------
// Разбор Kotlin: маска контекстов, комментарии, скобки, строки.
// ---------------------------------------------------------------------------

// ctx — контекст байта исходника.
type ctx uint8

const (
	ctxCode         ctx = iota // обычный код
	ctxString                  // строковый литерал, включая ${...}
	ctxChar                    // символьный литерал
	ctxTemplate                // выражение внутри ${...}
	ctxLineComment             // комментарий до конца строки
	ctxBlockComment            // комментарий /* ... */, включая вложенные
)

// contextMask определяет контекст каждого байта: код, строка, шаблон,
// комментарий. Нужен, чтобы запятые, скобки и переводы строк внутри строк
// и комментариев не считались разметкой.
func contextMask(src string) []ctx {
	mask := make([]ctx, len(src))
	stack := []ctx{ctxCode}
	blockDepth := 0 // глубина вложенных /* */ (только внутри блока)
	tmplDepth := 0  // глубина фигурных скобок внутри ${...}
	set := func(i int, c ctx) {
		if i >= 0 && i < len(mask) {
			mask[i] = c
		}
	}
	// Начало комментария: текущий байт и следующий — уже комментарий.
	startComment := func(i int, kind ctx, depth int) {
		set(i, kind)
		set(i+1, kind)
		blockDepth = depth
		stack = append(stack, kind)
	}
	for i := 0; i < len(src); i++ {
		c := src[i]
		var nb byte
		if i+1 < len(src) {
			nb = src[i+1]
		}
		switch stack[len(stack)-1] {
		case ctxLineComment:
			set(i, ctxLineComment)
			if c == '\n' {
				stack = stack[:len(stack)-1] // сам перевод строки остаётся
			}
		case ctxBlockComment:
			set(i, ctxBlockComment)
			if c == '/' && nb == '*' {
				set(i+1, ctxBlockComment)
				blockDepth++
				i++
			} else if c == '*' && nb == '/' {
				set(i+1, ctxBlockComment)
				blockDepth--
				i++
				if blockDepth == 0 {
					stack = stack[:len(stack)-1]
				}
			}
		case ctxString:
			set(i, ctxString)
			switch {
			case c == '\\' && i+1 < len(src):
				i++
				set(i, ctxString)
			case c == '"':
				stack = stack[:len(stack)-1]
			case c == '$' && nb == '{':
				i++
				set(i, ctxString)
				tmplDepth = 0
				stack = append(stack, ctxTemplate)
			}
		case ctxChar:
			set(i, ctxChar)
			switch {
			case c == '\\' && i+1 < len(src):
				i++
				set(i, ctxChar)
			case c == '\'':
				stack = stack[:len(stack)-1]
			}
		case ctxTemplate:
			set(i, ctxTemplate)
			switch {
			case c == '{':
				tmplDepth++
			case c == '}':
				if tmplDepth == 0 {
					stack = stack[:len(stack)-1]
				} else {
					tmplDepth--
				}
			case c == '"':
				set(i, ctxString)
				stack = append(stack, ctxString)
			case c == '\'':
				set(i, ctxChar)
				stack = append(stack, ctxChar)
			case c == '/' && nb == '/':
				startComment(i, ctxLineComment, 0)
				i++
			case c == '/' && nb == '*':
				startComment(i, ctxBlockComment, 1)
				i++
			}
		case ctxCode:
			set(i, ctxCode)
			switch {
			case c == '/' && nb == '/':
				startComment(i, ctxLineComment, 0)
				i++
			case c == '/' && nb == '*':
				startComment(i, ctxBlockComment, 1)
				i++
			case c == '"':
				set(i, ctxString) // и открывающая кавычка — часть литерала
				stack = append(stack, ctxString)
			case c == '\'':
				set(i, ctxChar)
				stack = append(stack, ctxChar)
			}
		}
	}
	return mask
}

// stripComments вырезает комментарии пробелами, сохраняя длину и переводы
// строк: позиции символов не сдвигаются. Строковые литералы остаются.
func stripComments(src string) string {
	mask := contextMask(src)
	out := []byte(src)
	for i, c := range mask {
		if (c == ctxLineComment || c == ctxBlockComment) && out[i] != '\n' {
			out[i] = ' '
		}
	}
	return string(out)
}

// matchBracket возвращает индекс скобки, закрывающей src[open] ({, ( или [).
// Скобки внутри строк и комментариев не считаются.
func matchBracket(src string, mask []ctx, open int) int {
	var closeC byte
	switch src[open] {
	case '{':
		closeC = '}'
	case '(':
		closeC = ')'
	case '[':
		closeC = ']'
	default:
		return -1
	}
	depth := 0
	for i := open; i < len(src); i++ {
		if mask[i] != ctxCode {
			continue
		}
		switch src[i] {
		case src[open]:
			depth++
		case closeC:
			depth--
			if depth == 0 {
				return i
			}
		}
	}
	return -1
}

// splitTopLevel режет диапазон на части по запятым вне скобок.
func splitTopLevel(src string, mask []ctx, from, to int) [][2]int {
	var parts [][2]int
	depth, start := 0, from
	for i := from; i < to; i++ {
		if mask[i] != ctxCode {
			continue
		}
		switch src[i] {
		case '(', '[', '{':
			depth++
		case ')', ']', '}':
			depth--
		case ',':
			if depth == 0 {
				parts = append(parts, [2]int{start, i})
				start = i + 1
			}
		}
	}
	if start < to {
		parts = append(parts, [2]int{start, to})
	}
	return parts
}

// splitBranches режет тело when на ветки: по запятой или переводу строки
// вне скобок и литералов; перевод строки после оператора «+» ветку не обрывает.
func splitBranches(src string, mask []ctx, from, to int) [][2]int {
	var segs [][2]int
	depth, start := 0, from
	endsWithPlus := func(end int) bool {
		j := end - 1
		for j >= start && (src[j] == ' ' || src[j] == '\t' || src[j] == '\r') {
			j--
		}
		return j >= start && src[j] == '+'
	}
	splitAt := func(i int) {
		segs = append(segs, [2]int{start, i})
		start = i + 1
	}
	for i := from; i < to; i++ {
		m := mask[i]
		if m == ctxString || m == ctxChar || m == ctxTemplate {
			continue // содержимое литералов разметкой не считаем
		}
		c := src[i]
		if m == ctxCode {
			switch c {
			case '(', '[', '{':
				depth++
			case ')', ']', '}':
				depth--
			case ',':
				if depth == 0 {
					splitAt(i)
				}
				continue
			case '\n':
			default:
				continue
			}
		}
		// перевод строки в коде или в конце комментария
		if c == '\n' && depth == 0 && !endsWithPlus(i) {
			splitAt(i)
		}
	}
	if start < to {
		segs = append(segs, [2]int{start, to})
	}
	return segs
}

// isLiteralExpr истинен, если диапазон — только строковые литералы,
// слитые оператором «+» (и пробелы/комментарии).
func isLiteralExpr(src string, mask []ctx, from, to int) bool {
	lit := false
	for i := from; i < to; i++ {
		switch mask[i] {
		case ctxString:
			lit = true
		case ctxCode:
			switch src[i] {
			case '+', ' ', '\t', '\n', '\r':
			default:
				return false
			}
		}
	}
	return lit
}

// lineOf — номер строки (с 1) для позиции в исходнике.
func lineOf(s string, pos int) int {
	if pos > len(s) {
		pos = len(s)
	}
	return 1 + strings.Count(s[:pos], "\n")
}

// ---------------------------------------------------------------------------
// git и обход файлов
// ---------------------------------------------------------------------------

// gitLsFiles — отслеживаемые файлы репозитория, пути относительно root.
func gitLsFiles(root string) ([]string, error) {
	out, err := exec.Command("git", "-C", root, "ls-files").Output()
	if err != nil {
		return nil, fmt.Errorf("git ls-files в %s: %w", root, err)
	}
	var files []string
	for _, ln := range strings.Split(string(out), "\n") {
		if ln = strings.TrimSpace(ln); ln != "" {
			files = append(files, ln)
		}
	}
	return files, nil
}

// collectKotlin собирает .kt-файлы под subdir; keep (или nil — все) фильтрует
// по пути относительно subdir.
func collectKotlin(root, subdir string, keep func(rel string) bool) ([]sourceFile, error) {
	base := filepath.Join(root, subdir)
	if _, err := os.Stat(base); err != nil {
		return nil, fmt.Errorf("%s: %w", subdir, err)
	}
	var files []sourceFile
	err := filepath.Walk(base, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() || !strings.HasSuffix(p, ".kt") {
			return nil
		}
		rel, err := filepath.Rel(base, p)
		if err != nil {
			return err
		}
		if keep != nil && !keep(filepath.ToSlash(rel)) {
			return nil
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		files = append(files, sourceFile{
			path:    filepath.ToSlash(filepath.Join(subdir, rel)),
			content: string(b),
		})
		return nil
	})
	if err != nil {
		return nil, err
	}
	return files, nil
}

// isSettingsPath — путь вида **/ui/**/*.kt относительно commonMain/kotlin.
func isSettingsPath(rel string) bool {
	rel = filepath.ToSlash(rel)
	if !strings.HasSuffix(rel, ".kt") {
		return false
	}
	parts := strings.Split(rel, "/")
	for i, p := range parts {
		if p == "ui" && i >= 1 && i < len(parts)-1 {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------------------
// 1. forbidden-tracked
// ---------------------------------------------------------------------------

var (
	forbiddenDocs = []string{"JOURNAL.md", "TODO.md", "TESTLOG.md", "TESTREPORT.md",
		"PLAN.md", "PLAN2.md", "AGENTS.md"}
	forbiddenDirs = []string{".idea/", "scripts/", ".devtest/"}
)

// checkForbiddenTracked — локальные документы и каталоги не должны попасть в git.
func checkForbiddenTracked(tracked []string) ([]string, string) {
	var fails []string
	for _, raw := range tracked {
		p := filepath.ToSlash(strings.TrimSpace(raw))
		if p == "" {
			continue
		}
		base := p[strings.LastIndex(p, "/")+1:]
		switch {
		case hasBase(forbiddenDocs, base):
			fails = append(fails, fmt.Sprintf("отслеживается локальный документ: %s", p))
		case hasDir(forbiddenDirs, p):
			fails = append(fails, fmt.Sprintf("отслеживается служебный каталог: %s", p))
		case strings.HasSuffix(strings.ToLower(p), ".apk"):
			fails = append(fails, fmt.Sprintf("отслеживается APK: %s", p))
		}
	}
	return fails, fmt.Sprintf("%d файлов в индексе", len(tracked))
}

func hasBase(list []string, base string) bool {
	for _, v := range list {
		if v == base {
			return true
		}
	}
	return false
}

func hasDir(list []string, p string) bool {
	for _, d := range list {
		if p == strings.TrimSuffix(d, "/") || strings.HasPrefix(p, d) || strings.Contains(p, "/"+d) {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------------------
// 2. manifest-classes
// ---------------------------------------------------------------------------

var declRe = regexp.MustCompile(`\b(?:class|object)\s+([A-Za-z_][A-Za-z0-9_]*)`)

// manifestRef — ссылка android:name вида ".Имя" на компонент.
type manifestRef struct {
	elem  string
	class string
}

// checkManifestClasses — каждый относительный компонент манифеста обязан
// существовать как class/object в Kotlin-исходниках.
func checkManifestClasses(manifest string, sources []sourceFile) ([]string, string) {
	refs, err := manifestClassRefs(manifest)
	if err != nil {
		return []string{"AndroidManifest.xml не разбирается: " + err.Error()}, ""
	}
	declared := map[string]bool{}
	for _, f := range sources {
		for _, m := range declRe.FindAllStringSubmatch(stripComments(f.content), -1) {
			declared[m[1]] = true
		}
	}
	var fails []string
	seen := map[string]bool{}
	for _, r := range refs {
		name := r.class
		if i := strings.LastIndex(name, "."); i >= 0 {
			name = name[i+1:]
		}
		if declared[name] || seen[name] {
			continue
		}
		seen[name] = true
		fails = append(fails, fmt.Sprintf(
			"AndroidManifest.xml: <%s> android:name=\".%s\" — нет class/object %s",
			r.elem, r.class, name))
	}
	return fails, fmt.Sprintf("%d относительных компонентов", len(refs))
}

// manifestClassRefs собирает android:name, начинающиеся с точки.
// activity-alias пропускается: его android имя — псевдоним, а не класс.
func manifestClassRefs(manifest string) ([]manifestRef, error) {
	dec := xml.NewDecoder(strings.NewReader(manifest))
	var refs []manifestRef
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
		se, ok := tok.(xml.StartElement)
		if !ok || se.Name.Local == "activity-alias" {
			continue
		}
		for _, a := range se.Attr {
			if a.Name.Local == "name" && strings.HasPrefix(a.Value, ".") && len(a.Value) > 1 {
				refs = append(refs, manifestRef{elem: se.Name.Local, class: a.Value[1:]})
			}
		}
	}
	return refs, nil
}

// ---------------------------------------------------------------------------
// 3. namespace-clash
// ---------------------------------------------------------------------------

var namespaceRe = regexp.MustCompile(`\bnamespace\s*=\s*"([^"]+)"`)

// checkNamespaceClash — namespace :app и :composeApp обязаны различаться.
func checkNamespaceClash(appGradle, composeGradle string) ([]string, string) {
	app := firstSub(namespaceRe, stripComments(appGradle))
	shared := firstSub(namespaceRe, stripComments(composeGradle))
	var fails []string
	if app == "" {
		fails = append(fails, "APP/app/build.gradle.kts: namespace не найден")
	}
	if shared == "" {
		fails = append(fails, "APP/composeApp/build.gradle.kts: namespace не найден")
	}
	if app != "" && shared != "" && app == shared {
		fails = append(fails, fmt.Sprintf(
			"namespace совпадают: %q — сборка упадёт на merger манифестов", app))
	}
	return fails, fmt.Sprintf("app=%s, composeApp=%s", dash(app), dash(shared))
}

func firstSub(re *regexp.Regexp, s string) string {
	if m := re.FindStringSubmatch(s); m != nil {
		return m[1]
	}
	return ""
}

func dash(s string) string {
	if s == "" {
		return "—"
	}
	return s
}

// ---------------------------------------------------------------------------
// 4–5. Str.kt: биекция и арность pick()
// ---------------------------------------------------------------------------

var capsKeyRe = regexp.MustCompile(`[A-Z][A-Z0-9_]*`)

type strEntry struct {
	name string
	pos  int
}

type strBranch struct {
	key    string
	keyPos int
	from   int // начало выражения после ->
	to     int // конец выражения
}

type strParsed struct {
	stripped  string
	mask      []ctx
	entries   []strEntry
	branches  []strBranch
	malformed []string // ветки, которые не удалось разобрать
	err       string   // фатальная ошибка разбора (нет enum или of())
}

// parseStr разбирает Str.kt: записи enum class Str и ветки fun of().
func parseStr(src string) strParsed {
	stripped := stripComments(src)
	p := strParsed{stripped: stripped, mask: contextMask(src)}

	enumAt := strings.Index(stripped, "enum class Str")
	if enumAt < 0 {
		p.err = "Str.kt: enum class Str не найден"
		return p
	}
	open := strings.IndexByte(stripped[enumAt:], '{')
	if open < 0 {
		p.err = "Str.kt: тело enum не найдено"
		return p
	}
	open += enumAt
	regionEnd := -1
	depth := 0
	for i := open; i < len(stripped); i++ {
		if p.mask[i] != ctxCode {
			continue
		}
		switch stripped[i] {
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				regionEnd = i
			}
		case ';':
			if depth == 1 {
				regionEnd = i
			}
		}
		if regionEnd >= 0 {
			break
		}
	}
	if regionEnd < 0 {
		p.err = "Str.kt: конец записей enum не найден"
		return p
	}
	region := stripped[open+1 : regionEnd]
	for _, loc := range capsKeyRe.FindAllStringIndex(region, -1) {
		p.entries = append(p.entries, strEntry{name: region[loc[0]:loc[1]], pos: open + 1 + loc[0]})
	}

	ofAt := strings.Index(stripped, "fun of(")
	if ofAt < 0 {
		p.err = "Str.kt: fun of() не найден"
		return p
	}
	whenAt := strings.Index(stripped[ofAt:], "when")
	if whenAt < 0 {
		p.err = "Str.kt: when в of() не найден"
		return p
	}
	whenOpen := strings.IndexByte(stripped[ofAt+whenAt:], '{')
	if whenOpen < 0 {
		p.err = "Str.kt: тело when не найдено"
		return p
	}
	whenOpen += ofAt + whenAt
	whenClose := matchBracket(stripped, p.mask, whenOpen)
	if whenClose < 0 {
		p.err = "Str.kt: when не закрыт"
		return p
	}

	for _, seg := range splitBranches(stripped, p.mask, whenOpen+1, whenClose) {
		s, e := seg[0], seg[1]
		// Пустота и комментарии по краям сегмента не значат.
		for s < e && (isSpaceByte(stripped[s]) || isCommentCtx(p.mask[s])) {
			s++
		}
		for e > s && (isSpaceByte(stripped[e-1]) || isCommentCtx(p.mask[e-1])) {
			e--
		}
		if s >= e || strings.TrimSpace(stripSpaces(stripped[s:e])) == "" {
			continue // пустые строки и комментарии между ветками
		}
		arrow := arrowIndex(stripped, p.mask, s, e)
		if arrow < 0 {
			p.malformed = append(p.malformed, fmt.Sprintf(
				"Str.kt:%d: не разобрана ветка: %s", lineOf(stripped, s), oneLine(stripped[s:e])))
			continue
		}
		key := strings.TrimSpace(stripped[s:arrow])
		if key == "" {
			p.malformed = append(p.malformed, fmt.Sprintf(
				"Str.kt:%d: ветка без ключа: %s", lineOf(stripped, s), oneLine(stripped[s:e])))
			continue
		}
		exprFrom := arrow + 2
		for exprFrom < e && (isSpaceByte(stripped[exprFrom]) || isCommentCtx(p.mask[exprFrom])) {
			exprFrom++
		}
		p.branches = append(p.branches, strBranch{
			key:    key,
			keyPos: s,
			from:   exprFrom,
			to:     e,
		})
	}
	return p
}

func isSpaceByte(c byte) bool {
	return c == ' ' || c == '\t' || c == '\r' || c == '\n'
}

func isCommentCtx(c ctx) bool {
	return c == ctxLineComment || c == ctxBlockComment
}

func stripSpaces(s string) string {
	return strings.Map(func(r rune) rune {
		if r == ' ' || r == '\t' || r == '\r' || r == '\n' || r == ';' {
			return -1
		}
		return r
	}, s)
}

// oneLine — короткий фрагмент для сообщения об ошибке.
func oneLine(s string) string {
	s = strings.Join(strings.Fields(s), " ")
	if len(s) > 60 {
		s = s[:60] + "…"
	}
	return s
}

// arrowIndex ищет «->» вне строк и комментариев.
func arrowIndex(src string, mask []ctx, from, to int) int {
	for i := from; i+1 < to; i++ {
		if mask[i] == ctxCode && src[i] == '-' && src[i+1] == '>' {
			return i
		}
	}
	return -1
}

var pickHeadRe = regexp.MustCompile(`^pick\s*\(`)

// checkStrBijection — у каждой записи enum есть ветка of() и наоборот.
func checkStrBijection(strSrc string) ([]string, string) {
	p := parseStr(strSrc)
	if p.err != "" {
		return []string{p.err}, ""
	}
	fails := append([]string{}, p.malformed...)
	branchKeys := map[string]int{}
	for _, b := range p.branches {
		branchKeys[b.key] = lineOf(p.stripped, b.keyPos)
	}
	entryNames := map[string]bool{}
	for _, e := range p.entries {
		entryNames[e.name] = true
		if _, ok := branchKeys[e.name]; !ok {
			fails = append(fails, fmt.Sprintf("Str.kt:%d: запись %s без ветки of()",
				lineOf(p.stripped, e.pos), e.name))
		}
	}
	for _, b := range p.branches {
		if !entryNames[b.key] {
			fails = append(fails, fmt.Sprintf("Str.kt:%d: ветка %s без записи в enum",
				lineOf(p.stripped, b.keyPos), b.key))
		}
	}
	return fails, fmt.Sprintf("%d ключей, %d веток", len(p.entries), len(p.branches))
}

// checkStrPickArity — каждая ветка of(): pick(lang, en, ru, zh) с тремя
// литералами либо строковый литерал (общий для языков текст).
func checkStrPickArity(strSrc string) ([]string, string) {
	p := parseStr(strSrc)
	if p.err != "" {
		return []string{p.err}, ""
	}
	fails := append([]string{}, p.malformed...)
	for _, b := range p.branches {
		expr := p.stripped[b.from:b.to]
		line := lineOf(p.stripped, b.keyPos)
		if loc := pickHeadRe.FindStringIndex(expr); loc != nil {
			open := b.from + loc[1] - 1
			close := matchBracket(p.stripped, p.mask, open)
			if close < 0 || close > b.to {
				fails = append(fails, fmt.Sprintf("Str.kt:%d: ветка %s: pick() не закрыт", line, b.key))
				continue
			}
			args := splitTopLevel(p.stripped, p.mask, open+1, close)
			if len(args) != 4 {
				fails = append(fails, fmt.Sprintf(
					"Str.kt:%d: ветка %s: pick() — ожидались lang и 3 литерала, аргументов: %d",
					line, b.key, len(args)))
				continue
			}
			if got := strings.TrimSpace(p.stripped[args[0][0]:args[0][1]]); got != "lang" {
				fails = append(fails, fmt.Sprintf(
					"Str.kt:%d: ветка %s: первый аргумент pick() — %q, ожидалось lang",
					line, b.key, oneLine(got)))
				continue
			}
			for i, a := range args[1:] {
				if !isLiteralExpr(p.stripped, p.mask, a[0], a[1]) {
					fails = append(fails, fmt.Sprintf(
						"Str.kt:%d: ветка %s: аргумент %d не строковый литерал: %s",
						line, b.key, i+1, oneLine(p.stripped[a[0]:a[1]])))
				}
			}
			continue
		}
		if !isLiteralExpr(p.stripped, p.mask, b.from, b.to) {
			fails = append(fails, fmt.Sprintf(
				"Str.kt:%d: ветка %s: не-pick ветка без строкового литерала: %s",
				line, b.key, oneLine(expr)))
		}
	}
	return fails, fmt.Sprintf("%d веток", len(p.branches))
}

// ---------------------------------------------------------------------------
// 6. settings-paths
// ---------------------------------------------------------------------------

var (
	pathRe    = regexp.MustCompile(`\bpath\s*=\s*"([^"]*)"`)
	funNameRe = regexp.MustCompile(`\bfun\s+([A-Za-z_][A-Za-z0-9_]*)`)
)

type funSpan struct {
	name string
	pos  int
}

// checkSettingsPaths — литералы path = "..." в ui-файлах: без дублей внутри
// одной функции, без пустых и пробельных значений. Шаблоны с $ пропускаются:
// их значение статически неизвестно.
func checkSettingsPaths(files []sourceFile) ([]string, string) {
	var fails []string
	total, templates := 0, 0
	for _, f := range files {
		stripped := stripComments(f.content)
		funs := funSpans(stripped)
		seen := map[string]int{}
		for _, m := range pathRe.FindAllStringSubmatchIndex(stripped, -1) {
			val := stripped[m[2]:m[3]]
			if strings.Contains(val, "$") {
				templates++
				continue
			}
			total++
			line := lineOf(stripped, m[0])
			switch {
			case val == "":
				fails = append(fails, fmt.Sprintf("%s:%d: пустой путь", f.path, line))
				continue
			case strings.ContainsAny(val, " \t\r\n"):
				fails = append(fails, fmt.Sprintf("%s:%d: путь с пробелом %q", f.path, line, val))
				continue
			}
			fun := funAt(funs, m[0])
			key := fun + "\x00" + val
			if first, ok := seen[key]; ok {
				fails = append(fails, fmt.Sprintf(
					"%s:%d: дубль пути %q в %s (первый — строка %d)",
					f.path, line, val, fun, first))
				continue
			}
			seen[key] = line
		}
	}
	return fails, fmt.Sprintf("%d путей, шаблонов пропущено: %d", total, templates)
}

// funSpans — позиции имен функций в порядке появления.
func funSpans(stripped string) []funSpan {
	var spans []funSpan
	for _, m := range funNameRe.FindAllStringSubmatchIndex(stripped, -1) {
		spans = append(spans, funSpan{name: stripped[m[2]:m[3]], pos: m[0]})
	}
	sort.Slice(spans, func(i, j int) bool { return spans[i].pos < spans[j].pos })
	return spans
}

// funAt — имя функции, содержащей позицию pos (последняя объявленная выше).
func funAt(spans []funSpan, pos int) string {
	name := "<файл>"
	for _, s := range spans {
		if s.pos > pos {
			break
		}
		name = s.name
	}
	return name
}

// ---------------------------------------------------------------------------
// 7. versions
// ---------------------------------------------------------------------------

var (
	zappVersionRe = regexp.MustCompile(`(?m)^\s*zappVersion\s*=\s*([^\s#]+)`)
	versionCodeRe = regexp.MustCompile(`(?m)^\s*versionCode\s*=\s*([^\s]+)`)
	versionNameRe = regexp.MustCompile(`(?m)^\s*versionName\s*=\s*([^\s]+)`)
	versionRe     = regexp.MustCompile(`^\d+\.\d+\.\d+$`)
)

// checkVersions — zappVersion вида X.Y.Z, versionCode целое >= 1,
// versionName ссылается на zappVersion.
func checkVersions(gradleProps, appGradle string) ([]string, string) {
	var fails []string

	ver := firstSub(zappVersionRe, gradleProps)
	switch {
	case ver == "":
		fails = append(fails, "APP/gradle.properties: zappVersion не найден")
	case !versionRe.MatchString(ver):
		fails = append(fails, fmt.Sprintf(
			"APP/gradle.properties: zappVersion=%q, ожидалось X.Y.Z", ver))
	}

	g := stripComments(appGradle)
	code := firstSub(versionCodeRe, g)
	switch {
	case code == "":
		fails = append(fails, "APP/app/build.gradle.kts: versionCode не найден")
	default:
		n, err := strconv.Atoi(code)
		if err != nil || n < 1 {
			fails = append(fails, fmt.Sprintf(
				"APP/app/build.gradle.kts: versionCode=%q, ожидалось целое >= 1", code))
		}
	}

	name := firstSub(versionNameRe, g)
	switch {
	case name == "":
		fails = append(fails, "APP/app/build.gradle.kts: versionName не найден")
	case name != "zappVersion":
		fails = append(fails, fmt.Sprintf(
			"APP/app/build.gradle.kts: versionName=%q, ожидалось zappVersion", name))
	}

	return fails, fmt.Sprintf("zappVersion=%s, versionCode=%s, versionName=%s",
		dash(ver), dash(code), dash(name))
}
