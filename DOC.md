# ZAPP: техническая документация

Документ описывает, что лежит в проекте, чем он собран и где что находится.
Все версии и числа взяты из конфигов и из собранных артефактов, а не из
описания. Там, где значение проверялось запуском, это отмечено.

Документ лежит здесь в двух видах: этот файл и `DOCX.docx` из того же текста.
Markdown читается прямо на GitHub, DOCX открывается в Word и LibreOffice.

---

## 1. Стек

| Слой | Что | Версия | Где прописано |
|---|---|---|---|
| Язык | Kotlin | 2.4.20 | `APP/gradle/libs.versions.toml` |
| Сборка | Gradle | 9.4.1 | `APP/gradle/wrapper/gradle-wrapper.properties` |
| Android-плагин | AGP | 9.2.1 | `APP/gradle/libs.versions.toml` |
| UI | Compose Multiplatform | 1.12.1 | там же |
| Сериализация | kotlinx-serialization-json | 1.9.0 | там же |
| Android-активити | androidx.activity:activity-compose | 1.13.0 | там же |
| Android-ядро | androidx.core:core-ktx | 1.10.1 | там же |
| Тесты | kotlin("test") | 2.4.20 | `APP/composeApp/build.gradle.kts` |
| Упаковка десктопа | jpackage, jlink | JDK 21 | `APP/desktopApp/build.gradle.kts` |
| AppImage | appimagetool | continuous | `.github/workflows/build.yml` |
| Целевая ОС Android | API 33 и выше | -- | `APP/app/build.gradle.kts` |
| Целевая ОС Android | targetSdk 36 | -- | там же |

Единая точка правды по версиям -- `APP/gradle/libs.versions.toml`. В
`build.gradle.kts` версии не дублируются, только alias'ы.

Версия самого приложения живёт отдельно, в `APP/gradle.properties`:

```properties
zappVersion=0.1.0
```

Её читают `APP/app/build.gradle.kts` как `versionName` и
`APP/desktopApp/build.gradle.kts` как `packageVersion`, чтобы APK и десктопный
дистрибутив не разошлись по версиям. Проверено на собранном APK:
`versionName=0.1.0`, `versionCode=1`, `minSdk=33`, `targetSdk=36`.

---

## 2. Модули

Три модуля в `APP/`. Общий код один, платформенные части разъезжаются по
source set'ам внутри модуля.

### `:composeApp`

Главный модуль. Kotlin Multiplatform с двумя таргетами:

- `androidLibrary` -- Android-библиотека, `namespace = xyz.azraellab.zapp.shared`,
  `compileSdk = 37`, `minSdk = 33`, `jvmTarget = JVM_11`;
- `jvm("desktop")` -- обычная JVM-библиотека.

Source set'ы:

| Source set | Что в нём |
|---|---|
| `commonMain` | UI, дизайн-система, строки, объявления `expect` |
| `androidMain` | реализация `expect` через Context и `SharedPreferences` |
| `desktopMain` | реализация через файл в `~/.config/zapp/` |
| `desktopTest` | тесты палитры и языка |

Зависимости `commonMain`:

```kotlin
implementation(compose.runtime)
implementation(compose.foundation)
implementation(compose.material3)
implementation(compose.ui)
implementation(compose.materialIconsExtended)
implementation(libs.kotlinx.serialization.json)
```

`desktopMain` добавляет `compose.desktop.currentOs`, `androidMain` --
`androidx.activity.compose`, `desktopTest` -- `kotlin("test")`.

`androidResources` включены, потому что дизайн-система тянет ресурсы Compose.

### `:app`

Только Android. Приложение, а не библиотека.

| Параметр | Значение |
|---|---|
| `namespace` | `xyz.azraellab.zapp` |
| `applicationId` | `xyz.azraellab.zapp` |
| `minSdk` | 33 |
| `targetSdk` | 36 |
| `compileSdk` | 37, minorApiLevel 2 |
| `sourceCompatibility` | Java 11 |

Release-сборка включает минификацию и усадку ресурсов:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

Одна activity -- `MainActivity` на `ComponentActivity`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    AndroidCtx.attach(this)
    setContent { App() }
}
```

`AndroidCtx` даёт доступ к Context из кода, которому Context передавать нечем.
Он объявлен в `AppThemeStore.android.kt` рядом с хранилищем темы, потому что
используется тем же способом.

В манифесте нет ни одного `permission`. Приложение ещё ничего не просит: на
этапе 4 появятся разведка устройства и запрос прав, и они пойдут в манифест
вместе с первым экраном, который их потребует.

### `:desktopApp`

Только десктоп. Kotlin JVM, зависит на `:composeApp` и `compose.desktop.currentOs`.
Точка входа -- `xyz.azraellab.zapp.desktop.MainKt`.

```kotlin
mainClass = "xyz.azraellab.zapp.desktop.MainKt"
targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
packageName = "zapp"
packageVersion = zappVersion
description = "ZAPP - network toolkit"
vendor = "ZAPP"
linux {
    appCategory = "Network"
    menuGroup = "Network"
}
```

---

## 3. Про namespace

`:app` и `:composeApp` не могут иметь одинаковый `namespace` -- merger манифестов
ругается на дубли. Поэтому:

| Модуль | namespace | Kotlin-пакет |
|---|---|---|
| `:app` | `xyz.azraellab.zapp` | `xyz.azraellab.zapp` |
| `:composeApp` | `xyz.azraellab.zapp.shared` | `xyz.azraellab.zapp.*` |

`namespace` Android-библиотеки и пакет Kotlin -- разные вещи. Namespace попадает
в сгенерированные `R` и `BuildConfig`, а Kotlin-пакет определяет видимость кода
по classpath. Совпадение пакетов у модулей нормально, совпадение namespace -- нет.

---

## 4. Грабли сборки

### Плагины в корневом `build.gradle.kts`

Все плагины перечислены с `apply false`. Это не стилистика: AGP тянет транзитивно
`kotlin-gradle-plugin` 2.2.x, и если нашего `kotlin.multiplatform` в classpath нет,
Gradle остаётся на версии из AGP. Со списком конфликт разрешается в сторону
2.4.20, иначе сборка `--offline` падает с `No cached version of ... 2.2.10`.

### Gradle 9 и аксессоры

`projects.composeApp` не резолвится. Использовать `project(":composeApp")`.

### desktopTest без `--rerun-tasks`

Неизменившаяся задача отдаёт `UP-TO-DATE`, счётчик тестов не пересчитывается.
Всегда запускать с `--rerun-tasks`.

### objcopy на NixOS

`jlink` и `jpackage` требуют `objcopy`, а в `PATH` его нет:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

Без этого `createRuntimeImage` падает с `External tool execution failed`.

### fakeroot и dpkg-deb

`.deb` собирается через fakeroot и dpkg-deb. На NixOS их тоже нет в `PATH`, и
без них задача падает с `Invalid or unsupported type: [deb]`.

### packageAppImage

Задача отрабатывает с `BUILD SUCCESSFUL` и **не создаёт AppImage**: jpackage
отдаёт только каталог `app/zapp` с бинарником и библиотеками. Сборку настоящего
AppImage делает внешний `appimagetool`, поэтому в CI он ставится явно, а `AppRun`,
`.desktop` и иконка собираются вручную. Проверено: после задачи в каталоге есть
только подкаталог `zapp`, файла `.AppImage` нет.

### Требования дистрибутива к JDK

JBR из Android Studio не содержит `jpackage`. Для десктоп-сборки нужен полный
JDK 21. Gradle-демон держит JBR, поэтому перед релизной сборкой:

```bash
./gradlew --stop
```

### Подпись APK

Keystore в репозитории нет и не должен быть: `signing/` в `.gitignore`, подпись
делается локально через `signing/keystore.properties`. Без этого файла
release-сборка выходит неподписанной.

### Проверка подписи

```bash
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

`keytool -printcert -jarfile` на APK отвечает `Not a signed jar file`. Это не
ошибка подписи, просто инструмент так не умеет.

### Владение файлами

Сборка от root создаёт root-owned каталоги `build/` и ломает Android Studio у
azrael. После сборки:

```bash
chown -R azrael:users .
```

---

## 5. Дизайн-система

Всё в `APP/composeApp/src/commonMain/kotlin/xyz/azraellab/zapp/ui/theme/`.

### Палитра (`Color.kt`)

| Токен | Значение | Роль |
|---|---|---|
| `AzraelPrimary` | `#4ADE80` | акцент |
| `AzraelOnPrimary` | `#07170C` | текст на акценте |
| `AzraelBgDeep` | `#0A0A0A` | фон тёмной схемы |
| `AzraelBgCard` | `#0DFFFFFF` | полупрозрачная карточка |
| `AzraelBorder` | `#2EFFFFFF` | декоративная граница |
| `AzraelTextDim` | `#D9EDEDED` | приглушённый текст |
| `AzraelDanger` | `#F43F5E` | опасное действие |
| `AzraelWarning` | `#F59E0B` | предупреждение |
| `AzraelInfo` | `#38BDF8` | информация |
| `AzraelSuccess` | `#22C55E` | успех |
| `AzraelStrong` | `#16A34A` | усиленный успех |
| `AzraelErrorSoft` | `#F87171` | мягкая ошибка |

Оценки подряд: `#F4585A` бедно, `#F59E0B` средне, `#22C55E` хорошо,
`#16A34A` отлично. Роли `#FFB74D` и `#E6C86A` есть, но на экране пока не
показываются.

Светлая схема: фон `#FAFAFA`, поверхность `#FFFFFF`, `primary` `#166534`,
`outline` `#78787F`.

Значение `#166534` выбрано вместо `#15803D` сознательно: `#15803D` даёт контраст
4.14:1, и этого не хватает для текста на кнопке.

### Две границы

`outline` и `outlineVariant` разведены по смыслу, и это проверяется тестом:

- `outline` -- смысловая граница элемента управления. WCAG 1.4.11 требует 3:1,
  обе схемы дают (3.77 и 4.20 к фону страницы);
- `outlineVariant` -- декоративная: стеклянная карточка, разделитель, пассивная
  подложка. Правило 3:1 к ней не применяется, её не нужно искать глазом, чтобы
  начать взаимодействие.

Раньше обе границы брались из одного места и молча разъехались: где-то `outline`,
где-то `alpha=0.10` от `onSurface`. Теперь токены разделены по смыслу, а не по
красоте, и формулы лежат в `Contrast.kt` отдельным файлом -- тест не должен
повторять числа, которые он же и проверяет.

### Отступы (`Spacing.kt`)

Шкала: `xxs` 2, `xs` 4, `sm` 8, `md` 12, `lg` 16, `xl` 24, `xxl` 32 (в dp).

Экраны: `screenPadding` 16, `cardPadding` 14, `cardGap` 12, `listGap` 8,
`fieldGap` 10, `controlHeight` 44, `controlHeightSm` 36, `touchTarget` 48,
`divider` 1, `stroke` 1.

`touchTarget` 48 -- это минимум WCAG 2.5.8 для пальца, а `controlHeight` 44
виден глазом. Разные числа для разных задач, поэтому и токена два.

### Скругления (`Shape.kt`)

`xs` 6, `sm` 10, `md` 14, `lg` 20, `xl` 26, `glass` 22 (в dp).

### Типографика (`Type.kt`)

`FontFamily.Default`, `LineHeightStyle.Alignment.Center`. Заголовки `display*`
набраны Bold и SemiBold с отрицательным трекингом от -1.0 до -0.25, текст
`body` и `label` -- Normal и Medium с трекингом от 0.1 до 0.4. Отрицательный
трекинг у крупного текста и положительный у мелкого -- противоположные
правила, иначе либо заголовки разъезжаются, либо текст в упор читается.

### Ripple (`Ripple.kt`)

`ripple(bounded, onSurface)`, обёрнут в отдельную тему индикации, чтобы
компоненты не задавали ripple каждый раз заново.

### Фон (`StarfieldBackground.kt`)

Звёздное небо на канвасе, а не картинка: картинку нельзя инвертировать под тему
и она не реагирует на смену темы на лету.

```kotlin
const val STAR_COUNT = 110
const val HIT_FRAMES = 90
const val MIN_COMETS = 1
const val MAX_COMETS = 5
const val COMET_SPAWN_MIN_MS = 2600
const val COMET_SPAWN_MAX_MS = 7200
private const val TRAIL_LEN_PX = 120f
```

Координаты звёзд хранятся долями экрана (0..1), иначе на повороте экрана или
на другом размере звёзды разъезжаются. Радиусы комет, наоборот, в пикселях:
хвост кометы должен быть одинаковой длиной в пикселях независимо от размера окна.

На светлой теме палитра инвертируется, иначе белые звёзды на `#FAFAFA` не видны.
Перекрашивание уже созданных звёзд делает `LaunchedEffect(palette)`: `remember`
без ключа переживает смену темы и оставил бы их старого цвета.

Звезда и кома пересекаются -- ком даёт вспышку, для этого `HIT_FRAMES`.

---

## 6. Тема и язык

### Хранение

Обе настройки на expect/actual и переживают перезапуск одинаково.

| | Тема | Язык |
|---|---|---|
| Android | `SharedPreferences` | `SharedPreferences` |
| Desktop | `~/.config/zapp/theme` | `~/.config/zapp/lang` |

На десктопе запись атомарная: пишем во временный файл и переименовываем. Падение
на середине иначе оставило бы файл, который следующий запуск не смог бы прочитать.

### Режимы темы

Три режима: `system`, `light`, `dark`. Неизвестное значение и пустая строка
падают в `system`, а не в значение по умолчанию наугад.

### Язык

Три языка: EN, RU, ZH. Правило выбора:

1. явный выбор пользователя, если он сделан;
2. иначе язык системы по кодам стран и языков;
3. иначе английский.

`AppLangStore.systemCode()` отдаёт полный тег (`ro-MD`, `zh-Hans-CN`), а не только
язык. В Молдове язык системы `ro`, отдельного кода «молдавский» нет (ISO 639-1
`mo` deprecated), и русский там ставится по региону `MD`. Раньше отдавался только
`language`, и настоящий `ro-MD` уезжал в английский.

Регион проверяется отдельно от языка, потому что `ro` без региона -- это просто
румынский, а Румыния не в СНГ. Русским Молдова становится по `ro-MD`.

Второй компонент кода брать нельзя: в `zh-Hans-CN` это script `Hans`, а не
страна. Поэтому берётся последний компонент длиной ровно в две буквы.

Порядок проверок важен: китайский смотрится раньше региона, иначе `zh-TW` и
`zh-HK` уехали бы в русский.

Языки СНГ, которые дают русский интерфейс: `ru`, `be`, `uk`, `kk`, `ky`, `tg`,
`tk`, `uz`, `az`, `hy`, `ka`. Страны: `RU`, `BY`, `UA`, `KZ`, `KG`, `TJ`, `TM`,
`UZ`, `AZ`, `AM`, `GE`, `MD`.

Немецкий или турецкий по умолчанию дают английский: пользователь русский интерфейс
в Казахстане оценит, а в Германии или Турции -- нет.

### Строки (`core/Str.kt`)

Ключи -- элементы `enum`, а не строки. Забытый перевод тогда не собирается, а не
показывается на экране как `CORE_START`.

Всего 20 ключей, и все двадцать переведены на три языка. Имена собственные
(`ZAPP`, `VPN`, `Zapret`, `GoodbyeDPI`, `GPS`) переведены одинаково во всех трёх
ветках -- так их и найти взглядом, а держать три одинаковые копии смысла нет.

В `Str.of` задумана поддержка `null` как «одинаково на всех языках», но сейчас
ею не пользуется ни один ключ: вырожденный случай разобрали вручную, и он виден
глазом. Если ключей станет много, этот случай стоит вернуть.

---

## 7. Тесты

Source set `desktopTest`, 20 тестов.

| Файл | Тестов | Что проверяет |
|---|---|---|
| `ui/theme/ThemeTest.kt` | 8 | контраст каждой пары цветов в обеих схемах, разбор режима темы |
| `ui/theme/Contrast.kt` | -- | сами формулы WCAG, без продублированных чисел |
| `core/I18nTest.kt` | 12 | разбор кода, правила языка, полнота переводов |

`ThemeTest` проверяет восемь вещей: определение светлой и тёмной схемы по системному
значению, что режим `system` следует за системой, что явные режимы системное
значение игноририруют, что неизвестный код и пустая строка падают в `system`, что
известные коды читаются без учёта регистра и пробелов, что `outline` виден как
граница в обеих темах, что каждый слот схемы читается на своём фоне, что
полупрозрачные цвета измеряются после наложения на фон.

Тест палитры не декоративный: подстановка `#D4D4D8` вместо `outline` его роняет.
Проверено вручную.

Запуск:

```bash
./gradlew :composeApp:desktopTest --rerun-tasks
```

`--rerun-tasks` обязателен, иначе задача отдаст `UP-TO-DATE` и счётчик не
пересчитается.

---

## 8. Сборка

### Скрипт `APP/build-all.sh`

| Режим | Что делает |
|---|---|
| `dev` | debug APK + uber-JAR |
| `release` | debug и release APK + дистрибутив текущей ОС + uber-JAR |
| `linux` | AppImage + `.deb` |

Скрипт сам находит полный JDK с `jpackage` и `objcopy` в nix store, потому что в
`PATH` их нет. Результаты складываются в `APP/build/dist/`.

Скрипт зовёт `scripts/finish-deb.sh`, а тот в репозиторий не входит. На чистой
машине его нет, и тогда `.deb` остаётся сырым и получает имя
`zapp_UNFINISHED_amd64.deb` -- чтобы спутать дополненный пакет с сырым было нельзя.

### Публикуемость артефактов

NixOS-сборка вписывает `/nix/store` в интерпретатор launcher'а и в runtime jlink,
поэтому AppImage и `.deb` отсюда на Debian, Ubuntu и Windows не запустятся.
Проверено: у ELF-файла `bin/zapp` внутри пакета `interpreter` указывает на
`/nix/store/...-glibc-2.42/lib/ld-linux-x86-64.so.2`, и то же в `libapplauncher.so`
и в `lib/runtime/lib/*.so`.

Скрипт распаковывает каждый артефакт и раскладывает их по спискам:

| Список | Что попадает |
|---|---|
| `PUBLISHABLE` | APK и uber-JAR |
| `UNPUBLISHABLE` | AppImage и `.deb` |

Распаковка обязательна. AppImage -- это squashfs, `.deb` -- сжатый tar в ar,
`.apk` и `.jar` -- zip. Поиск байтов прямо в файле в сжатом контейнере ничего не
находит и объявляет NixOS-сборку годной к публикации. Поэтому `.deb`
распаковывается через `dpkg-deb -x`, zip -- через `unzip -p`, а AppImage
проверяется по appdir, из которого он собран.

Полные публикуемые дистрибутивы собирает CI на `ubuntu` и `windows`.

Размеры собранного на этой машине: APK 58.6 МБ, uber-JAR 74.6 МБ, дополненный
`.deb` 85.7 МБ, AppImage 100.9 МБ. Uber-JAR проверен запуском: процесс живёт и
не падает.

### `.deb`

Сырой jpackage-пакет непригоден по пяти причинам, и `finish-deb.sh` их закрывает:

1. `/usr/bin/zapp` -- симлинк-лаунчер. jpackage кладёт исполняемый файл только в
   `/opt/<pkg>/bin/` и в `/usr/bin` не делает ничего, поэтому после установки
   команду нельзя набрать в терминале, только мышкой через меню;
2. `/usr/share/applications/zapp.desktop` -- jpackage регистрирует `.desktop` только
   вызовом `xdg-desktop-menu install` из `postinst`, и без `xdg-utils` запись в
   меню просто не появляется;
3. `/usr/share/icons/hicolor/512x512/apps/zapp.png` -- чтобы иконка нашлась по
   стандарту, а не абсолютным путём в `/opt`;
4. `Depends` в `control` заменяется целиком. jpackage считает зависимости через
   `jdeps`, а на NixOS тот не находит библиотеки вне `/usr/lib`, и в `control`
   попадает одинокая `xdg-utils` без `libstdc++6` и `libx11-6`. На реальной
   Debian-машине пакет встал бы и упал бы при запуске;
5. `prerm` от jpackage снимает меню голым `xdg-desktop-menu uninstall` без
   `command -v` и без `|| true` при `set -e`, из-за чего `apt remove` падал бы
   с кодом 1.

`libasound2` записывается как `libasound2 | libasound2t64`: на Debian 13 (trixie)
пакет называется `libasound2t64`, на bookworm -- `libasound2`.

Собирать `finish-deb.sh` нужно на файле `*_amd64.deb` без суффикса `_zapp`,
иначе получится `*_zapp_zapp.deb`.

### Подпись

```properties
# signing/keystore.properties, в .gitignore
storeFile=/путь/к/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

---

## 9. CI

`.github/workflows/build.yml`, четыре джобы:

| Джоба | Runner | Артефакты |
|---|---|---|
| `android` | `ubuntu-latest` | debug и unsigned release APK |
| `windows` | `windows-latest` | MSI |
| `linux` | `ubuntu-latest` | AppImage, `.deb` |
| `release` | `ubuntu-latest` | GitHub Release по тегу `v*` |

Триггеры: push в `main`, push тега `v*`, pull request в `main`, ручной запуск.
Параллельные прогоны отменяются по группе ветки.

Все gradle-задачи запускаются из `APP/`, потому что в корне репозитория лежат
ещё `ABOUT.md` и `DOCX.docx`.

Джоба `release` кладёт в релиз только десктоп-артефакты. APK туда не идёт: в CI
нет keystore, а неподписанный APK в релиз выкладывать нельзя. Если десктопных
артефактов не найдено, релиз не создаётся вовсе, а не создаётся пустой.

На момент написания файл workflow лежит локально и не запушен: токен доступа
репозитория не имеет области `workflow`, а GitHub не даёт записать такой файл ни
через push, ни через API. Все остальные файлы в репозитории.

---

## 10. Карта файлов

```
ZAPP/
├── ABOUT.md                     описание приложения, три языка
├── DOCX.docx                    этот документ
├── DOC.md                       тот же текст в markdown
├── LICENSE
├── README.md                    три языка
└── APP/
    ├── gradle/libs.versions.toml   версии всех библиотек
    ├── gradle.properties            версия приложения
    ├── build.gradle.kts             плагины с apply false, autoBuild
    ├── build-all.sh                 сборка всех платформ
    ├── app/                         Android-приложение
    │   ├── .gitignore
    │   ├── proguard-rules.pro
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/xyz/azraellab/zapp/MainActivity.kt
    │       └── res/                  иконки, темы, строки, backup-правила
    ├── composeApp/                 общий код
    │   ├── .gitignore
    │   └── src/
    │       ├── commonMain/kotlin/xyz/azraellab/zapp/
    │       │   ├── App.kt
    │       │   ├── core/
    │       │   │   ├── AppLang.kt        expect, правила выбора языка
    │       │   │   ├── AppThemeStore.kt
    │       │   │   └── Str.kt            строки, три языка
    │       │   └── ui/
    │       │       ├── AppLang.kt        состояние языка, tr()
    │       │       ├── StarfieldBackground.kt
    │       │       ├── Theme.kt
    │       │       └── theme/            Color, Spacing, Shape, Type, Ripple
    │       ├── androidMain/.../core/     AppLang, AppThemeStore (+ AndroidCtx)
    │       ├── desktopMain/.../core/     AppLang, AppThemeStore
    │       └── desktopTest/.../          ThemeTest, Contrast, I18nTest
    └── desktopApp/                 десктоп-приложение
        ├── .gitignore
        └── build.gradle.kts        mainClass, форматы упаковки
```

---

## 11. Проверки после изменений

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

cd APP
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:desktopTest --rerun-tasks
./gradlew :app:assembleDebug
./gradlew :desktopApp:createDistributable
```

Перед коммитом от root:

```bash
chown -R azrael:users .
```

Проверка, что приложение не падает на устройстве:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n xyz.azraellab.zapp/.MainActivity
adb logcat -d | grep -iE "FATAL|AndroidRuntime"
```

---

## 12. Что ещё предстоит

Основа сделана: общий код собирается под обе платформы, дизайн-система закончена,
тесты палитры и языка проходят. Экраны функций не написаны, приложение открывает
оформленное окно и на этом всё.

Очередь по этапам без буквенных префиксов, как в README:

| Этап | Содержание | Критерий |
|---|---|---|
| 1 | компоненты дизайн-системы | каждый компонент покрыт тестом контраста |
| 2 | навигация, каркас экранов | работающий back-stack, две панели на широком экране |
| 3 | слой данных | ни один экран не знает про системные вызовы |
| 4 | разведка устройства и права | честный отчёт о том, что доступно, без лишних запросов |
| 5 | парсеры форматов подключения | битый вход отбрасывается, а не роняет разбор |
| 6 | сбор конфигов | список наполняется, дубликаты убираются |
| 7 | проверка живости | три состояния, никаких ложных «живых» |
| 8 | подключение | туннель поднимается, трафик идёт через ноду |
| 9 | обход на уровне сети | с повышенными правами реальные правила, без них честный экран |
| 10 | обход в рукопожатии | проверка домена даёт вердикт |
| 11 | подмена координат | подмена видна в стороннем приложении |
| 12 | упаковка | все четыре формата собираются |
| 13 | полировка | контраст от 3:1, подписанный APK |
