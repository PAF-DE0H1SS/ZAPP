# ZAPP: устройство, разработка, пресеты

Документ для тех, кто собирает форк, и для тех, кто хочет понять, как работает
приложение. Отдельный раздел — формат пресетов с полным описанием полей и
примерами рукописных конфигов.

Краткая справка по продукту — в [ABOUT.md](ABOUT.md), полная техническая
документация — в [DOC.md](DOC.md).

---

## 1. Стек

| Что | Значение |
|---|---|
| Язык | Kotlin |
| UI | Compose Multiplatform |
| Сборка | Gradle 9.4.1, AGP 9.2.1, Kotlin 2.4.20 |
| JDK | 21 (обязателен, закреплён в `gradle-daemon-jvm.properties`) |
| Android | `minSdk=33` (Android 13), `targetSdk=36`, `versionCode=9` |
| Нативная часть | C++ через CMake, `libbox.so` и демоны в `jniLibs` |

Версия приложения хранится в одном месте — `APP/gradle.properties`,
`zappVersion`. Оттуда её берут и APK, и десктопные установщики.

---

## 2. Модули

```
ZAPP/
├── APP/                     корневой проект Gradle
│   ├── composeApp/          общий код: Android-библиотека + JVM("desktop")
│   ├── app/                 Android-приложение: манифест, MainActivity, ресурсы
│   └── desktopApp/          десктопная точка входа, упаковка jpackage
├── ABOUT.md                 что делает приложение
├── DOC.md                   техническая документация
└── DEVELOPMENT.md           этот файл
```

Два namespace сосуществуют намеренно:

| Модуль | namespace Android |
|---|---|
| `:app` | `xyz.azraellab.zapp` |
| `:composeApp` | `xyz.azraellab.zapp.shared` |

Namespace — это идентичность библиотеки в Android (в него попадают
генерируемые `R` и `BuildConfig`), а не Kotlin-пакет. Merger манифестов
запрещает совпадение namespace, но Kotlin-пакеты совпадать могут.

---

## 3. Исходники `composeApp`

Наборы исходников в `composeApp/src`:

| Набор | Назначение |
|---|---|
| `commonMain` | UI, дизайн-система, модели, ViewModel, платформенно-независимое ядро |
| `androidMain` | `actual`-реализации под Android (SharedPreferences) |
| `desktopMain` | `actual`-реализации под десктоп (файловая система) |
| `jvmShared` | общая JVM-часть, 21 файл; `androidMain` и `desktopMain` зависят от неё |
| `desktopTest` | весь тестовый набор: 1303 кейса, 18 файлов |

`jvmShared` появился потому, что часть кода одинакова для Android и десктопа,
но не может лежать в `commonMain`: это работа с процессами, сокетами и
файлами, доступная только на JVM. Объявлен в `composeApp/build.gradle.kts`:

```kotlin
val jvmShared by creating { ... }
getByName("androidMain").dependsOn(jvmShared)
getByName("desktopMain").dependsOn(jvmShared)
```

Туда попали `core/net`, `core/daemon`, `core/engine`, `core/probe`,
`core/dpi`, `core/root`, `core/geo`, `core/update` — JVM-реализации, у
которых в `commonMain` лежит только контракт. Тестовых наборов у
`jvmShared` нет: тесты общей JVM-части запускаются из `desktopTest`, где
общая JVM уже в classpath.

Основные пакеты:

- `ui/` — `components`, `nav`, `pages`, `theme`, `traffic`, `welcome`,
  `consistency`
- `ui/pages/` — `TorPage`, `VpnPage`, `ZapretPage`, `GoodbyeDpiPage`,
  `GpsPage`, `SettingsPage`, `DaemonControl`, `RoutingCards`, `PerAppSection`,
  `VpnProfileEditor`, `DevTestsPage`
- `core/` — конфигурация и логика: `AppConfig`, `VpnConfig`, `ZapretConfig`,
  `GoodbyeDpiConfig`, `GpsConfig`, `TrafficConfig`, `engine`, `daemon`,
  `net`, `probe`, `dpi`, `perApp`, `gps`, `log`, `update`, `routing`

---

## 4. Как это работает

### Конфигурация

Всё состояние приложения — один объект `AppConfig`. Он сериализуется в JSON
через `ConfigCodec` и лежит на диске. Поля верхнего уровня:

```
zapret, goodbyeDpi, vpn, gps, traffic      блоки настроек
perf                                       производительность UI
usage                                      профиль использования
region                                     код страны (ISO 3166-1 alpha-2)
perApp                                     туннелирование по приложениям
relay                                      тонкая настройка общего релея
trafficMonitorEnabled, detailLogEnabled    переключатели
welcomeDone                                онбординг пройден
updateCompareMode, updateSnoozeUntilMs     автообновление
version                                    версия схемы
```

`ConfigCodec` сконфигурирован так:

```kotlin
prettyPrint = true      файл читается глазами
ignoreUnknownKeys = true  старый файл не падает на новом поле
encodeDefaults = true     в файле видно все ручки, а не только изменённые
```

Разбор повреждённого файла возвращает значения по умолчанию, а не исключение:
сломанный конфиг не должен блокировать запуск.

### Туннели

Приложение не хранит трафик у себя — это набор локальных инструментов.

| Компонент | Что делает |
|---|---|
| VPN | WireGuard / AmneziaWG, OpenVPN, sing-box (vless, vmess, trojan, ss, hysteria2, tuic). Группы конфигов с `urltest`, kill-switch |
| Tor | Мосты из встроенных источников, автовыбор по пингу, фейловер, локальный SOCKS 9050, pluggable-транспорты через `lyrebird` |
| Zapret | `nfqws` под root, цепочка NFQUEUE `ZAPP_DPI`, стратегии обхода DPI |
| GoodbyeDPI | `goodbyedpi` рядом с zapret на той же цепочке |
| DNS | При пустом списке пользователя резолверы публично пробуются настоящим A-запросом до старта |

Запуск демонов идёт через `core/daemon`, трафик считает `core/traffic`
на Android через нативный `libzapp-traffic.so`, на десктопе — Kotlin-реализация.

### Демоны

Нативные бинарники (`tor`, `lyrebird`, `nfqws`, `goodbyedpi`, `wg`,
`openvpn`, `box`) лежат в `APP/app/src/main/jniLibs/<abi>/`. В репозитории
их нет — в CI они скачиваются из релиза с тегом `native-bins`. Без них
сборка пройдёт, но APK будет без VPN/Zapret/Tor и не попадёт в релиз
(проверка `libbox.so` в release-джобе).

---

## 5. Сборка

### Варианты

Два флейвора × два build type дают четыре варианта:

| Флейвор | Отличие |
|---|---|
| `noroot` | без повышенных прав, работает на обычном устройстве |
| `root` | запрашивает суперпользователя, запускает `nfqws` и `goodbyedpi` |

Каждый вариант дополнительно режется по ABI на пять APK: четыре по
архитектурам (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) плюс
универсальный. Выход:

```
app/build/outputs/apk/{root,noroot}/{debug,release}/
    ZAPP[-ROOT]-<версия>.apk              универсал
    ZAPP[-ROOT]-<версия>-<abi>.apk        сплит
    ZAPP[-ROOT]-<версия>-debug[-<abi>].apk  debug
```

У всех пяти APK одного варианта один `versionCode`, поэтому установка
любого поверх любого — замена, а не понижение.

### Команды

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=<путь к JDK 21>

./gradlew :app:assembleDebug --offline
./gradlew :app:assembleRelease --offline

# десктоп
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
./gradlew :desktopApp:packageMsi            # запускать на Windows
```

`--offline` обязателен, если зависимости уже в кеше: без него Gradle ходит в
сеть и тормозит на недоступных репозиториях.

### Две настройки, которые нельзя убрать

В `APP/gradle.properties`:

```properties
org.gradle.parallel=false
org.gradle.workers.max=1
```

Это не для скорости. При сплитах AGP запускает по одному
`IncrementalSplitterRunnable` на каждый ABI через
`workerExecutor.noIsolation()`, то есть пять упаковщиков работают
одновременно внутри одной задачи. Каждый пишет записи через
`ApkFlinger.writeFile` в общий `ForkJoinPool.commonPool()`, а при закрытии
архива подпись V2 отправляет в тот же пул ровно `parallelism` задач и ждёт
все сразу (`IncrementalPackagerBuilder.lambda$withSigning$0`).

Пока в пуле лежат записи чужих упаковщиков, свободных воркеров не хватает
никогда. Сборка останавливается на `packageRootRelease` без единой строки
вывода и не завершается. `org.gradle.workers.max=1` сериализует сами
сплиты, и к моменту подписи в пуле никого нет.

`org.gradle.parallel=false` на эту гонку не действует — он сериализует
задачи, а конкуренция внутри одной. Оставлен, чтобы не гонять разные
проекты одновременно.

Убрать эти флаги = зависание CI, воспроизводимое стабильно.

### Сборка на NixOS

`jlink` и `jpackage` требуют `objcopy`, которого нет в `PATH`:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

Артефакты с NixOS публиковать нельзя — в launcher и runtime jlink
вписываются пути `/nix/store`. `build-all.sh` раскладывает результат по
`build/dist/PUBLISHABLE` и `build/dist/UNPUBLISHABLE`.

### Подпись

Локально: создать keystore, указать путь в `signing/keystore.properties`.
Без этого файла release собирается неподписанным — форки и CI работают без
секретов. В CI подпись ставится секретным keystore на шаге релиза.

`keytool -printcert -jarfile` для APK отвечает «Not a signed jar file» — это
не ошибка. Проверять через:

```bash
apksigner verify --print-certs app/build/outputs/apk/root/release/ZAPP-ROOT-<ver>.apk
```

---

## 6. Тесты

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

1303 кейса: контраст WCAG для компонентов и палитры, разбор локали, кодеки
конфигов, разбор ссылок протоколов, генерация конфига sing-box, выбор мостов,
математика спарклайна, проба DNS-резолверов.

`--rerun-tasks` обязателен: без него неизменившаяся задача отдаёт
`UP-TO-DATE`, и счётчик тестов не пересчитывается.

Каталоги тестов:

Весь набор лежит в `composeApp/src/desktopTest/` — 18 файлов. Основные:

| Файл | Что проверяет |
|---|---|
| `ui/theme/ThemeTest` | контраст WCAG пар палитры |
| `ui/components/ComponentsTest` | контраст компонентов |
| `ui/nav/AppTabTest` | состав и порядок вкладок |
| `core/AppConfigCodecTest` | кодеки `AppConfig` и `VpnConfig` |
| `core/dev/DevTestsParameterized` | прогон всех `core/dev/*Suite.kt` |
| `core/net/ProxyUriTest` | разбор ссылок протоколов |
| `core/net/SingboxConfigTest` | генерация конфига sing-box |
| `core/net/WireGuardIniTest` | разбор WireGuard-конфига |
| `core/TorBridgesTest` | выбор мостов |
| `core/dpi/SplitRelayTest` | математика сплит-релея |
| `core/gps/RouteProgressTest` | прогресс по маршруту |
| `core/update/VersionCompareTest` | сравнение версий |
| `core/I18nTest`, `LinkSpeedTest`, `UsageTunerTest` | локали, скорость, профиль |
| `core/engine/TorBootstrapPercentTest` | процент загрузки Tor |
| `core/VpnConnectsTest` | список коннектов |

Отдельных тестовых наборов у `androidMain` и `jvmShared` нет.

---

## 7. CI и релизы

`.github/workflows/build.yml`, четыре джоба:

| Джоб | Что делает |
|---|---|
| Android APK | тесты, сборка четырёх вариантов со сплитами |
| Windows 10/11 MSI | сборка на `windows-latest` |
| Linux AppImage | сборка на `ubuntu-latest` |
| GitHub Release | только на теги `v*` |

Сборка запускается на пуш в `main` и на теги `v*`. Группа конкурентности —
`build-${{ github.ref }}` с `cancel-in-progress`, пуш в ветку отменяет
незавершённый прогон по ней; тег живёт в своей группе и строится параллельно.

Release-джоб собирает `*.msi`, `*.AppImage` и APK по фильтру:

```
find artifacts -type f -name 'ZAPP-*.apk' ! -name '*-debug*' ! -name '*unsigned*'
```

плюс проверка наличия `libbox.so` внутри — APK без нативных демонов в релиз
не идёт. Звёздочки в фильтре обязательны с обеих сторон: у сплитов имя
заканчивается на архитектуру (`ZAPP-2.7.0-debug-x86.apk`), и вариант
`*-debug.apk` пропускал восемь debug-сборок.

---

## 8. Форк: порядок работы

1. **Скопировать репозиторий и убрать секреты.** В форке не должно быть
   `signing/keystore.properties` и ключей CI. Они и так не в git —
   убедиться, что так и осталось.

2. **Собрать локально.** `./gradlew :app:assembleDebug --offline`. Если
   сборка виснет на `package*Release` — вернулись самодеятельные флаги в
   `gradle.properties`, см. раздел 5.

3. **Прогнать тесты.** `./gradlew :composeApp:desktopTest --offline
   --rerun-tasks`, дождаться 1303/1303.

4. **Проверить на чистой установке.** Старый APK снять, новый поставить:

   ```bash
   adb uninstall xyz.azraellab.zapp
   adb install app/build/outputs/apk/noroot/debug/ZAPP-2.7.0-debug-arm64-v8a.apk
   ```

   Ставить поверх старого нельзя — проверяется именно чистый запуск.

5. **Проверить оба варианта.** `noroot` должен работать без запроса root,
   `root` — сразу выкатить диалог суперпользователя. Отсутствие запроса у
   root-сборки означает, что флейвор не собрался.

6. **Проверить целостность сплитов.** Универсальный APK обязан содержать
   40 библиотек (10 на каждый из четырёх ABI), сплит — 10:

   ```bash
   unzip -l app/build/outputs/apk/root/release/ZAPP-ROOT-2.7.0.apk | grep -c 'lib/'
   ```

   После прерванной сборки задача упаковки может считаться `UP-TO-DATE` и
   оставить недописанный APK. Выходное каталог упаковки лучше удалить
   руками, чем надеяться на up-to-date.

7. **Обновить документацию.** Версия в `README.md` и `DOC.md`, состав
   релиза, изменённые поля пресетов.

8. **Коммит.** Идентификатор Git — обычное имя и почта проекта. Текст
   коммита описывает изменение, а не автора.

---

## 9. Пресеты

### 9.1. Что это

Пресет — сохранённый набор настроек, который применяется к конфигурации
целиком. В приложении пресеты импортируются и выгружаются, лежат отдельным
файлом, переживают обновление приложения.

Файл называется `<имя>.zapp.json`.

Формат — JSON. Свойства разбора:

| Свойство | Следствие |
|---|---|
| `ignoreUnknownKeys = true` | лишние поля в файле игнорируются |
| `encodeDefaults = true` | при выгрузке пишутся все поля, не только изменённые |
| отсутствующее поле | берётся значение по умолчанию из `data class` |

Третье свойство главное для ручной записи: **писать можно только то, что
меняется**, остальное подставится само. Файл, выгруженный приложением,
полный — в нём все поля; файл, написанный руками, может быть в десять строк.

### 9.2. Вид файла

Два допустимых вида.

Обёртка:

```json
{
  "kind": "zapp-preset",
  "preset": {
    "name": "Мой пресет",
    "zapret": { "enabled": true }
  }
}
```

И «голый» пресет без обёртки:

```json
{
  "name": "Мой пресет",
  "zapret": { "enabled": true }
}
```

Второй вид принимается намеренно: файл, положенный руками или выгруженный
другой версией приложения, отклонять молча хуже, чем принять. Разбор
обёртки с неверным `kind` даёт отказ (импорт возвращает `null`).

### 9.3. Структура пресета

Структура (схема, не файл — `{ ... }` означает блок из раздела ниже):

```text
{
  "id": "",
  "name": "",
  "description": "",
  "createdAtMs": 0,
  "region": "",
  "zapret":     { ... },
  "goodbyeDpi": { ... },
  "vpn":        { ... },
  "gps":        { ... },
  "perApp":     { ... }
}
```

| Поле | Тип | Назначение |
|---|---|---|
| `id` | строка | идентификатор; при импорте можно оставить пустым |
| `name` | строка | имя в списке пресетов |
| `description` | строка | описание, показывается в списке |
| `createdAtMs` | число | время создания в миллисекундах, `0` допустимо |
| `region` | строка | код страны ISO 3166-1 alpha-2, `""` — без настройки |
| `zapret` | объект | блок zapret, см. 9.4 |
| `goodbyeDpi` | объект | блок GoodbyeDPI, см. 9.5 |
| `vpn` | объект | блок VPN, см. 9.6 |
| `gps` | объект | блок подмены координат, см. 9.7 |
| `perApp` | объект | выбор приложений, см. 9.8 |

При применении эти пять блоков и `region` подставляются в конфигурацию
приложения. Остальные поля `AppConfig` (`traffic`, `perf`, `relay`,
`updateCompareMode`, `welcomeDone`, `version`) пресет **не трогает**:
устройственные настройки и состояние интерфейса остаются как были.

Поле `region` дополнительно прогоняется через автонстройку региона: для
части стран есть политика, которая прячет неработающие протоколы и
включает обходные механизмы. Пустая строка — нейтральная политика.

### 9.4. Блок `zapret`

Все поля и значения по умолчанию:

```json
"zapret": {
  "enabled": false,
  "binaryPath": "",
  "autoStrategy": true,
  "strategy": "auto",
  "mode": "bypass",
  "verifyStrategy": true,
  "autoRestart": true,

  "domains": "",
  "apps": "",
  "allTraffic": false,
  "exclusions": "",
  "ipv4Only": false,

  "filterTcp": "80,443",
  "filterUdp": "",
  "dpiPorts": "443",
  "filterIp": "",

  "newSyntax": true,
  "bgIncrement": false,
  "hostCase": true,
  "hostSpell": true,
  "hostNoSpace": false,
  "methodSpace": true,
  "methodEol": false,
  "unixEol": false,
  "tlsRec": false,
  "wsSize": null,
  "hostHash": false,

  "desyncMethods": ["fake"],
  "dpiSplit": "",
  "dpiSplitPos": "",
  "dpiSplitSeqovl": null,
  "dpiSplit2Pos": "",
  "dpiSplit2Seqovl": null,

  "fakeTlsMode": "",
  "fakeTlsHost": "",
  "fakeTlsPadLen": null,
  "fakeTlsCutLen": null,
  "fakePacket": false,
  "fakeSkip": false,
  "fakeTcp": false,
  "fakeDport": null,
  "fakeDportTtl": null,
  "fakeSport": null,
  "fakeSrcAddr": "",
  "fakeSeq": null,
  "fakeCsum": false,
  "foolingTs": false,
  "fakeTcpFlags": null,
  "fakeMss": null,
  "fakeRepeats": null,
  "fakeCut": false,
  "fakeCutTls": false,
  "fakeSniff": false,
  "fakeTlsMod": "",

  "autoTtl": "",
  "udpLenIncrement": null,
  "anyProtocol": false,
  "ttl": null,
  "ttlEnd": null,
  "ttl6": false,
  "skipTtl": false,
  "wsize": null,
  "mss": null,
  "multiply": null,
  "multipath": false,
  "ipdiag": false,

  "comment": "",
  "logging": false
}
```

Допустимые значения перечислимых полей:

**`strategy`** — `auto`, `disabled`, `multipath`, `multiply-alpn`,
`desync-fake-ttl-tcp-ack`, `desync-fakedsplit`, `dpi-desync`, `fake-sni`,
`fake-tls-alpn`, `syntax-v3`, `fake`, `multisplit-seqovl`,
`fake-fakedsplit-ts`, `messaging`, `games`, `fake-tls-rnd`.

При `autoStrategy: true` стратегия подбирается автоматически, поле
`strategy` игнорируется.

**`mode`** — `bypass` (обход) или `block` (блокировка).

**`desyncMethods`** — массив строк, методы подмены пакетов.
Значение по умолчанию `["fake"]`.

**Строковые списки** (`domains`, `apps`, `exclusions`, `filterTcp`,
`filterUdp`, `dpiPorts`, `filterIp`, `dpiSplit`, `dpiSplitPos`,
`fakeTlsHost`, `autoTtl`) — перечисления через запятую, а не массивы.
`null` в числовых полях означает «не задавать», и в JSON это ровно `null`,
а не `0`.

**Флаги синтаксиса** (`newSyntax`, `hostCase`, `hostSpell`, `hostNoSpace`,
`methodSpace`, `methodEol`, `unixEol`, `tlsRec`, `hostHash`) правят
подмену HTTP/TLS-заголовков: регистр Host, пробелы, переводы строк,
разрез TLS-записей.

### 9.5. Блок `goodbyeDpi`

```json
"goodbyeDpi": {
  "mode": "auto",
  "splitPos": "2",
  "fakeSeq": null,
  "fakeCsum": false,
  "fakeSni": "",
  "fakeGen": null,
  "fakeResend": null,
  "autoTtl": "",
  "setTtl": null,
  "maxPayload": null,
  "reverseFrag": false,
  "fragBySni": false,
  "hostCheck": true,
  "domains": "",
  "daemonPath": "",
  "autoRestart": true,
  "autoStart": false,
  "logging": false
}
```

**`mode`** — `auto`, `disabled`, `split2`, `split-pos`, `fake-patch`,
`fake-sni`, `fake-ssl`.

### 9.6. Блок `vpn`

```json
"vpn": {
  "enabled": false,
  "autoStart": false,
  "activeProfileId": "",
  "profiles": [],
  "routing": {
    "routesAllTraffic": true,
    "excludePrivateRanges": true,
    "excludeMulticast": true
  },
  "killSwitch": false,
  "killSwitchDns": false,
  "ipv6": false,
  "dnsServers": "",
  "splitTunneling": false,
  "dnsFakeIp": false,
  "dnsViaProxy": false,
  "dnsBackup": "",
  "dnsType": "udp",
  "dnsBootstrap": "",
  "dnsStrategy": "off",
  "dnsRewriteTtl": 60,
  "clashApiEnabled": true,
  "clashApiPort": 9090,
  "clashApiSecret": "",

  "sources": [],
  "torEnabled": true,
  "torSocksPort": 9050,
  "groupMode": false,
  "urlTest": {
    "mode": "disabled",
    "includeNames": "",
    "includeRegex": "",
    "includeCountries": "",
    "excludeNames": "",
    "excludeRegex": "",
    "excludeCountries": "",
    "testingUrl": "",
    "interval": "1m",
    "toleranceMs": 10,
    "idleTimeout": ""
  }
}
```

**`sources`** — откуда берутся коннекты: свои URL и встроенные репозитории.
Каждый элемент:

```json
{
  "id": "my-list",
  "name": "Свой список",
  "url": "https://example.com/list.txt",
  "mirrors": ["https://mirror.example.com/list.txt"],
  "enabled": true,
  "kind": "proxy",
  "refreshSeconds": 7200,
  "top": false,
  "lastRefreshAtMs": 0
}
```

`kind` — `proxy` или `bridges`. `top: true` поднимает источник наверх списка.

**`torSocksPort`** — локальный SOCKS-порт Tor, через него box и пускает
трафик. **`torEnabled`** — исторический флаг «показывать мосты в списке
VPN»: мосты живут отдельной вкладкой, в списки VPN не попадают, поле
оставлено только чтобы старые конфиги читались.

**`groupMode`** — групповое подключение: автоподбор по живым коннектам
вместо фиксированного узла.

**`urlTest`** — фильтр узлов для автоподбора.

**`urlTest.mode`** — `disabled`, `include`, `exclude`, `mixed`.
`includeNames` / `excludeNames` — списки через запятую, `includeRegex` /
`excludeRegex` — регулярные выражения, `includeCountries` /
`excludeCountries` — коды стран. `interval` — период перепроверки
(`1m`, `30s`, `5m`), `toleranceMs` — допуск до смены узла,
`testingUrl` — адрес для замера, `idleTimeout` — пауза без проверок.

**`dnsType`** — `udp`, `tcp`, `tls` (DoT), `https` (DoH). Один тип на весь
конфиг, относится и к `dnsServers`, и к спискам DNS в профилях.

**`dnsStrategy`** — `off`, `prefer_ipv4`, `prefer_ipv6`, `disable_ipv6`.

**`dnsBootstrap`** — адрес для резолва имён серверов. Пусто: серверы
разрешаются через основной DNS и получается петля «DNS-сервер сам себе
резолвер».

Профиль в массиве `profiles`:

```json
{
  "id": "wg-1",
  "name": "Мой сервер",
  "protocol": "wireguard",
  "transport": "udp",
  "gateway": "203.0.113.10",
  "gatewayPort": 51820,
  "address": "10.0.0.2/32",
  "subnetCidr": "0.0.0.0/0",
  "dnsServers": "1.1.1.1",
  "privateKey": "",
  "publicKey": "",
  "presharedKey": "",
  "certPath": "",
  "certPassword": "",
  "keepaliveSeconds": 25,
  "mtu": 1420,
  "autoReconnect": true,
  "reconnectDelaySeconds": 5,
  "maxReconnectAttempts": 0,
  "ipv6": false
}
```

**`protocol`** — `wireguard`, `amneziawg`, `openvpn`, `vless`, `vmess`,
`trojan`, `shadowsocks`, `hysteria2`, `tuic`, `singbox`, `xray`, `tor`,
`socks5`, `http`, `ssh`.

**`transport`** — `udp`, `tcp`, `ws`, `grpc`, `http2`.

`activeProfileId` должен совпадать с `id` нужного профиля, иначе активным
станет первый в списке.

### 9.7. Блок `gps`

```json
"gps": {
  "enabled": false,
  "mode": "fixed",
  "latitude": 0.0,
  "longitude": 0.0,
  "altitudeMeters": 0.0,
  "speedMps": 0.0,
  "speedJitterPercent": 0,
  "positionJitterMeters": 0.0,
  "driftMetersPerSecond": 0.0,
  "route": "",
  "routeLoop": false,
  "routeDwellSeconds": 0,
  "accuracyMeters": 5.0,
  "smoothingSamples": 1,
  "mockElapsedSeconds": 2,
  "reinstallProvider": true,
  "perApp": false,
  "apps": ""
}
```

**`mode`** — `disabled`, `fixed`, `route`, `vpn`.

`route` — список точек маршрута; у каждой точки latitude, longitude,
опционально speed и dwellSeconds. `accuracyMeters`, `smoothingSamples`,
`mockElapsedSeconds` правят подделку: точность, сглаживание, период
обновления.

Координаты обязаны быть в диапазонах: широта −90…90, долгота −180…180.

### 9.8. Блок `perApp`

```json
"perApp": {
  "vpn":     { "enabled": false, "mode": "include", "packages": [] },
  "tor":     { "enabled": false, "mode": "include", "packages": [] },
  "zapret":  { "enabled": false, "mode": "include", "packages": [] },
  "goodbyeDpi": { "enabled": false, "mode": "include", "packages": [] },
  "showSystemApps": false
}
```

`mode` — `include` (только перечисленные) или `exclude` (все, кроме).
`packages` — имена пакетов Android: `com.google.android.youtube`,
`org.telegram.messenger` и так далее.

### 9.9. Проверка после записи

`Preset.validate()` возвращает список проблемных полей. Проверяется:

| Поле | Условие |
|---|---|
| `vpn.profiles[].gatewayPort` | 1…65535 |
| `vpn.profiles[].mtu` | 576…9000 |
| `vpn.profiles[].keepaliveSeconds` | не отрицательное |
| `zapret` | аргументы не должны быть мусором вида `--поле=-` |
| `gps.latitude` | −90…90 |
| `gps.longitude` | −180…180 |

Пустой список — конфиг пригоден. Импорт не блокирует применение при
проблемах, но битые значения дадут ошибку при старте демона.

---

## 10. Примеры пресетов

### Пример 1. Минимальный: обход DPI только для указанных доменов

Всё, что не перечислено, останется по умолчанию.

```json
{
  "name": "Замедленные ресурсы",
  "description": "Обход только для двух доменов",
  "zapret": {
    "enabled": true,
    "autoStrategy": false,
    "strategy": "fake",
    "domains": "youtube.com,youtu.be",
    "allTraffic": false
  }
}
```

### Пример 2. Zapret на весь трафик с ручным разрезом

```json
{
  "name": "Разрез по позиции",
  "zapret": {
    "enabled": true,
    "autoStrategy": false,
    "strategy": "dpi-desync",
    "allTraffic": true,
    "filterTcp": "80,443",
    "dpiPorts": "443",
    "dpiSplit": "host",
    "dpiSplitPos": "2",
    "dpiSplitSeqovl": 1,
    "desyncMethods": ["fake"],
    "fakePacket": true,
    "hostCase": true,
    "methodSpace": true
  }
}
```

`allTraffic: true` вместе с `domains` взаимоисключающи: при `allTraffic`
список доменов не применяется.

### Пример 3. GoodbyeDPI с подменой SNI

У GoodbyeDPI нет отдельного поля включения — режим задаётся через `mode`,
значение `disabled` выключает демон.

```json
{
  "name": "Подмена SNI",
  "goodbyeDpi": {
    "mode": "fake-sni",
    "splitPos": "2",
    "fakeSni": "www.example.com",
    "fakeGen": 3,
    "fakeResend": 1,
    "hostCheck": true,
    "autoRestart": true,
    "domains": "example.com,example.org"
  }
}
```

### Пример 4. Kill-switch и split tunneling

```json
{
  "name": "VPN только для двух приложений",
  "vpn": {
    "enabled": true,
    "killSwitch": true,
    "killSwitchDns": true,
    "splitTunneling": true,
    "dnsServers": "1.1.1.1,8.8.8.8",
    "dnsType": "https",
    "dnsBootstrap": "1.1.1.1",
    "activeProfileId": "wg-main",
    "profiles": [
      {
        "id": "wg-main",
        "name": "Основной",
        "protocol": "wireguard",
        "gateway": "203.0.113.10",
        "gatewayPort": 51820,
        "address": "10.0.0.2/32",
        "dnsServers": "1.1.1.1",
        "publicKey": "qIx5...=",
        "privateKey": "0G8...=",
        "mtu": 1420,
        "keepaliveSeconds": 25
      }
    ]
  },
  "perApp": {
    "vpn": {
      "enabled": true,
      "mode": "include",
      "packages": [
        "com.google.android.youtube",
        "org.telegram.messenger"
      ]
    },
    "showSystemApps": false
  }
}
```

`killSwitch: true` при наличии root поднимает iptables-правила, которые
пропускают только адреса коннекта, пока туннель поднят. `killSwitchDns`
разрешает DNS-серверы конфига — без него резолвер мёртв вместе с сетью,
и переподключение по доменному имени не сможет разрешиться.

### Пример 5. Маршрут и дрожание координат

```json
{
  "name": "Маршрут по городу",
  "gps": {
    "enabled": true,
    "mode": "route",
    "route": "55.7558,37.6173,55.7601,37.6288,55.7710,37.6310",
    "routeLoop": true,
    "routeDwellSeconds": 5,
    "speedMps": 8.5,
    "positionJitterMeters": 12,
    "driftMetersPerSecond": 0.4,
    "accuracyMeters": 6.0,
    "smoothingSamples": 3,
    "mockElapsedSeconds": 2,
    "perApp": true,
    "apps": "com.yandex.taxi"
  }
}
```

`route` — координаты через запятую, пары широта/долгота.

### Пример 6. Полный пресет в форме, которую выгружает приложение

Со всеми полями, как после `encodeDefaults = true`. Читать целиком не
нужно — это показывает, как выглядит полностью заполненный файл.

```json
{
  "id": "preset-1728000000000",
  "name": "Комплексный",
  "description": "Обход DPI плюс гео",
  "createdAtMs": 1728000000000,
  "region": "ru",
  "zapret": {
    "enabled": true,
    "binaryPath": "",
    "autoStrategy": true,
    "strategy": "auto",
    "mode": "bypass",
    "verifyStrategy": true,
    "autoRestart": true,
    "domains": "youtube.com",
    "apps": "",
    "allTraffic": false,
    "exclusions": "",
    "ipv4Only": false,
    "filterTcp": "80,443",
    "filterUdp": "",
    "dpiPorts": "443",
    "filterIp": "",
    "newSyntax": true,
    "bgIncrement": false,
    "hostCase": true,
    "hostSpell": true,
    "hostNoSpace": false,
    "methodSpace": true,
    "methodEol": false,
    "unixEol": false,
    "tlsRec": false,
    "wsSize": null,
    "hostHash": false,
    "desyncMethods": ["fake"],
    "dpiSplit": "",
    "dpiSplitPos": "",
    "dpiSplitSeqovl": null,
    "dpiSplit2Pos": "",
    "dpiSplit2Seqovl": null,
    "fakeTlsMode": "",
    "fakeTlsHost": "",
    "fakeTlsPadLen": null,
    "fakeTlsCutLen": null,
    "fakePacket": false,
    "fakeSkip": false,
    "fakeTcp": false,
    "fakeDport": null,
    "fakeDportTtl": null,
    "fakeSport": null,
    "fakeSrcAddr": "",
    "fakeSeq": null,
    "fakeCsum": false,
    "foolingTs": false,
    "fakeTcpFlags": null,
    "fakeMss": null,
    "fakeRepeats": null,
    "fakeCut": false,
    "fakeCutTls": false,
    "fakeSniff": false,
    "fakeTlsMod": "",
    "autoTtl": "",
    "udpLenIncrement": null,
    "anyProtocol": false,
    "ttl": null,
    "ttlEnd": null,
    "ttl6": false,
    "skipTtl": false,
    "wsize": null,
    "mss": null,
    "multiply": null,
    "multipath": false,
    "ipdiag": false,
    "comment": "",
    "logging": false
  },
  "goodbyeDpi": {
    "mode": "auto",
    "splitPos": "2",
    "fakeSeq": null,
    "fakeCsum": false,
    "fakeSni": "",
    "fakeGen": null,
    "fakeResend": null,
    "autoTtl": "",
    "setTtl": null,
    "maxPayload": null,
    "reverseFrag": false,
    "fragBySni": false,
    "hostCheck": true,
    "domains": "",
    "daemonPath": "",
    "autoRestart": true,
    "autoStart": false,
    "logging": false
  },
  "vpn": {
    "enabled": false,
    "autoStart": false,
    "activeProfileId": "",
    "profiles": [],
    "routing": {
      "routesAllTraffic": true,
      "excludePrivateRanges": true,
      "excludeMulticast": true
    },
    "killSwitch": false,
    "killSwitchDns": false,
    "ipv6": false,
    "dnsServers": "",
    "splitTunneling": false,
    "dnsFakeIp": false,
    "dnsViaProxy": false,
    "dnsBackup": "",
    "dnsType": "udp",
    "dnsBootstrap": "",
    "dnsStrategy": "off",
    "dnsRewriteTtl": 60,
    "clashApiEnabled": true,
    "clashApiPort": 9090,
    "clashApiSecret": "",
    "sources": [],
    "torEnabled": true,
    "torSocksPort": 9050,
    "groupMode": false,
    "urlTest": {
      "mode": "disabled",
      "includeNames": "",
      "includeRegex": "",
      "includeCountries": "",
      "excludeNames": "",
      "excludeRegex": "",
      "excludeCountries": "",
      "testingUrl": "",
      "interval": "1m",
      "toleranceMs": 10,
      "idleTimeout": ""
    }
  },
  "gps": {
    "enabled": false,
    "mode": "fixed",
    "latitude": 0.0,
    "longitude": 0.0,
    "altitudeMeters": 0.0,
    "speedMps": 0.0,
    "speedJitterPercent": 0,
    "positionJitterMeters": 0.0,
    "driftMetersPerSecond": 0.0,
    "route": "",
    "routeLoop": false,
    "routeDwellSeconds": 0,
    "accuracyMeters": 5.0,
    "smoothingSamples": 1,
    "mockElapsedSeconds": 2,
    "reinstallProvider": true,
    "perApp": false,
    "apps": ""
  },
  "perApp": {
    "vpn":     { "enabled": false, "mode": "include", "packages": [] },
    "tor":     { "enabled": false, "mode": "include", "packages": [] },
    "zapret":  { "enabled": false, "mode": "include", "packages": [] },
    "goodbyeDpi": { "enabled": false, "mode": "include", "packages": [] },
    "showSystemApps": false
  }
}
```

---

## 11. Типовые ошибки

| Симптом | Причина |
|---|---|
| Сборка виснет на `packageRootRelease`, вывода нет | в `gradle.properties` убраны `parallel=false` или `workers.max=1` |
| В сплите нет всех библиотек | сборку прерывали, задача упаковки отчелась `UP-TO-DATE`; удалить `app/build/outputs/apk` и пересобрать |
| Тесты отдают `UP-TO-DATE` вместо счётчика | нет `--rerun-tasks` |
| `keytool` говорит «Not a signed jar file» | это нормально, проверять через `apksigner verify` |
| Нет `objcopy` при десктопной сборке | не добавлен binutils в `PATH` |
| Android Studio не открывает проект от основного пользователя | после сборки от root каталоги стали root-владельными: `chown -R <пользователь>:users .` |
| AGP тянет Kotlin 2.2.x, `--offline` падает | в корневом `build.gradle.kts` не перечислены все плагины с `apply false` |
| Merger манифестов ругается на namespace | namespace `:app` и `:composeApp` совпали |
| `projects.composeApp` не резолвится | Gradle 9.x, писать `project(":composeApp")` |
