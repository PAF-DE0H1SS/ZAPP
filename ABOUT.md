# About ZAPP

A network toolkit that runs entirely on your own device. There is no account to
create, no sign-up, no server of ours in the middle, and nothing is reported
anywhere. Open the app, and it does its work locally.

---

## How it works

The app is built around four separate areas. Each one has its own screen and its
own state, and you can use any of them without touching the others.

### Private channel

Wraps your traffic into an encrypted tunnel, so that anyone watching the line
sees only that a tunnel exists, not what is inside it.

The app does not run any server for this. It reads connection settings that other
people have already published, checks which of them actually answer, and offers
you the working ones. You can also point it at any source you like yourself.

Settings come in several shapes, and the app understands the common ones. A
setting that does not parse is dropped, not treated as an error: one bad entry
must not empty the list.

A setting is never called "working" on the strength of being in a list. Before it
is shown as alive, the app opens a real connection to it. Three states are shown:
not yet checked, answered, and did not answer. Results are cached briefly so the
app does not keep knocking on the same door.

The engine is a choice, not a fixed decision. One option is stronger on some
kinds of connections, another is lighter on phones. Both are open source, and the
choice sits in settings.

### Unblocking at the network level

Some providers inspect traffic in the middle of the path and decide what may
pass. This area changes the shape of the traffic itself, so the inspection finds
nothing to object to.

It needs elevated rights, because it works with system packet rules. Several
strategies are available, from the quietest to the most effective, and the
choice between them is yours rather than fixed.

Where elevated rights are missing, this area cannot work honestly. Rather than
pretend, the screen says so and points at the approach below, which does not need
them.

### Unblocking inside the handshake

A second, finer approach. In the first moment a connection is opened, the label
attached to it is changed right there, before anyone reads it, and the connection
is then passed on unchanged. Applications see nothing unusual.

On desktop this works without administrator rights, which makes it the easier
option when you only need to get past a single restriction. The app can check a
domain and tell you whether the restriction is there and whether this approach
gets past it.

### Location

On phones, the app can report a different position than the real one. The system
asks the user for permission, and the app asks for it honestly, in plain sight.

On desktop this area is absent, not hidden. There is no system-wide way to do it
there, and a half-working imitation would be worse than nothing.

This is worth stating plainly: some applications notice that a location is
simulated, because the system provides a standard way to ask. The app does not
promise to hide that.

---

## How the areas fit together

The two unblocking areas remove a restriction, the private channel then carries
your traffic, and location is unrelated to the network entirely.

They compose without one depending on another. You may use any single area on its
own.

---

## What the app asks for, and when

The order is deliberate: first find out what the device can do, then ask for the
minimum.

Before asking for anything, the app determines what is actually available on this
device and shows an honest picture: whether elevated rights are present, whether
the required components are installed, which areas will work and which will not.
The answer includes the reasons, not just a yes or no. "Not the maximum" is a
useful answer, provided it says why.

Permissions are requested on the screen that needs them, not as a pile on first
launch. The main area asks on first entry, because that is what most people
installed the app for. Everything else waits until the area is first opened.

Where a step runs with elevated rights, a dialog lists exactly what will be
performed before anything is performed. No command runs without explicit agreement.

Where an area needs no elevation, it says so, and it does not ask for elevation
just to keep the code uniform.

---

## Honest limits

The app is not a way around anything forbidden by law. It does not attempt
content protection or banking protocols that sign their requests.

It does not hide the fact that location is being changed, because the system
requires permission and the app requests it openly.

Some approaches are more noticeable than others. Aggressive strategies can be
seen by sophisticated equipment. Quieter strategies exist, and they are labelled
as such, because resilience is not guaranteed either way.

Some areas behave differently across devices and versions. The app therefore
checks what is possible before applying anything, and shows the result instead of
assuming it.

The four areas are separate projects with their own update cycles. The app pins
what it uses and verifies it, rather than taking whatever is newest at the time.

Different platforms have different limits, and a device that works does not mean
every device works. Each platform gets its own attention before a release.

**Public settings are public settings.** Whoever runs the other end of a
connection sees your traffic. A settings file can be changed by whoever published
it, including by someone who copied it. Such settings usually live for hours or
days, and half of any list is always non-working.

The app says all of this on the screen where you choose a connection, not in a
readme nobody opens. It shows a warning above the list, checks that an option
really answers before showing it as working, and lets you remove it from your
history for good.

---

## Privacy

No telemetry. The app goes to very few places: it reads public settings, it
connects to the option you picked, and if you entered an address yourself, to
that address. There are no developer endpoints in the build.

Data read from public settings, such as identifiers and passwords, is not written
to logs and not stored in plain text. Your history keeps only enough to recognise
an entry, not its secrets. Exported logs do not contain the domains you visited.

Privileged actions run only from a fixed list, with no user text spliced into
commands. Addresses you enter are checked against a known list rather than
fetched as given.

---

## How it is built

One set of source code, one design system, two platforms: phones and desktops.
The interface, the colours and the wording are shared, so a feature does not
exist in one version and be missing from the other.

The interface follows one visual idea. A dark surface, one accent colour, a star
field that drifts slowly behind the content, and translucent panels over it. There
is one theme, and it is dark.

Colour contrast is not left to judgement. Every pair of foreground and background
colours is measured against a published standard, and the check runs as a test. Borders are split in two: ones that carry meaning must be
visible enough to see, and decorative ones are not held to the same rule. A
border colour that is too faint to see fails the build.

Language follows the system: Russian for the region, Chinese for Chinese, English
for everything else, and an explicit choice always wins if you make one. Every
string exists in all three languages, and a test fails the build if a key is
missing a translation. A missing translation must never reach the screen.

The source is free and open. You may read it, change it, build it yourself, and
use it commercially, including in paid products. No fees, no cut of your income,
no agreement to sign, no permission asked in advance.

---

## Where it is now

The interface is in place: the shared code builds for both platforms, the design
system is finished, and the component, palette and language tests pass. There is a
bottom bar with five sections, a settings page and a separate traffic monitor
window, and the settings survive a restart. What is not there yet is the actual
tunnelling and DPI unblocking, device probing with permissions, pulling configs
from outside, and a map for location spoofing.

The work is staged, and each stage ends with a green build and green tests.

---

# О приложении (RU)

Сетевой инструмент, который целиком работает на вашем устройстве. Не нужно ни
создавать аккаунт, ни где-то регистрироваться, ни доверять нашему серверу, и никто
никуда ничего не отправляет. Открыли приложение, оно работает локально.

---

## Как это работает

Приложение построено вокруг четырёх отдельных областей. У каждой свой экран и своё
состояние, и пользоваться любой из них можно, не трогая остальные.

### Приватный канал

Оборачивает трафик в зашифрованный туннель, так что тот, кто смотрит на линию,
видит только факт существования туннеля, но не его содержимое.

Собственных серверов для этого приложение не поднимает. Оно читает настройки
подключения, которые уже опубликовали другие люди, проверяет, какие из них реально
отвечают, и предлагает рабочие. Можно также указать любой свой источник.

Настройки бывают разных видов, и приложение понимает распространённые. Неразобранная
настройка отбрасывается, а не считается ошибкой: одна испорченная строка не должна
опустошать список.

Настройка никогда не называется рабочей только потому, что она в списке. Прежде чем
показать её как рабочую, приложение открывает настоящее соединение. Показываются три
состояния: ещё не проверено, ответил, не ответил. Результаты ненадолго запоминаются,
чтобы приложение не стучалось в одну и ту же дверь постоянно.

Движок -- это выбор, а не фиксированное решение. Один вариант сильнее на одних
соединениях, другой легче на телефонах. Оба открытые, и выбор лежит в настройках.

### Обход на уровне сети

Часть провайдеров просматривает трафик посередине пути и решает, что пропустить.
Эта область меняет сам вид трафика так, чтобы проверке не нашлось повода.

Нужны права повыше обычных, потому что работа идёт с системными правилами пакетов.
Доступно несколько стратегий, от самой тихой до самой действенной, и выбор между ними
ваш, а не фиксированный.

Где повышенных прав нет, эта область не может работать честно. Вместо притворства
экран говорит об этом прямо и отправляет к способу ниже, которому права не нужны.

### Обход внутри рукопожатия

Второй, более тонкий способ. В самый момент открытия соединения метка, прикреплённая
к нему, меняется прямо там, до того как её кто-нибудь прочитает, а соединение затем
уходит дальше без изменений. Приложения не видят ничего необычного.

На десктопе это работает без прав администратора, поэтому это более простой вариант,
когда нужно обойти только одно ограничение. Приложение может проверить домен и
сказать, есть ли ограничение и обходит ли его этот способ.

### Местоположение

На телефонах приложение умеет сообщать не настоящую координату. Система спрашивает
разрешение у пользователя, и приложение просит его честно, открыто.

На десктопе этой области нет, и она не спрятана. Там нет системного способа сделать
это, а наполовину работающая имитация хуже, чем ничего.

Стоит сказать прямо: некоторые приложения замечают, что местоположение подменено,
потому что система даёт стандартный способ это спросить. Приложение не обещает
этого скрыть.

---

## Как области сочетаются

Две области обхода снимают ограничение, приватный канал затем несёт трафик, а
местоположение с сетью не связано вовсе.

Они складываются, не завися друг от друга. Можно пользоваться любой одной из них.

---

## Что приложение просит и когда

Порядок намеренный: сначала выяснить, что устройство умеет, потом просить минимум.

Прежде чем что-то просить, приложение определяет, что на этом устройстве реально
есть, и показывает честную картину: есть ли повышенные права, установлено ли нужное,
какие области заработают, а какие нет. В ответе есть и причины, а не только да или
нет. «Не максимум» -- полезный ответ, если сказано, почему.

Разрешения запрашиваются на том экране, который их требует, а не пачкой при первом
запуске. Главная область спрашивает при первом входе, потому что ради неё приложение
и ставят. Остальное ждёт, пока область откроют впервые.

Там, где шаг выполняется с повышенными правами, диалог перечисляет, что именно
будет сделано, до того как это будет сделано. Ни одна команда не выполняется без
явного согласия.

Там, где области права не нужны, она так и говорит и не требует повышения прав
только ради единообразия кода.

---

## Честные ограничения

Приложение не обходит то, что запрещено законом. Оно не трогает защиту содержимого и
банковские протоколы, подписывающие свои запросы.

Оно не скрывает сам факт подмены местоположения, потому что система требует
разрешения, и приложение просит его открыто.

Часть способов заметнее других. Агрессивные стратегии видны усовершенствованному
оборудованию. Тихие стратегии тоже есть, и они помечены как таковые, потому что
устойчивость не гарантирована ни в одну сторону.

Поведение разных областей отличается на разных устройствах и версиях. Поэтому
приложение проверяет, что возможно, прежде чем что-то применять, и показывает
результат вместо предположения.

Четыре области -- отдельные проекты со своими циклами обновления. Приложение
закрепляет то, что использует, и проверяет это, а не берёт что попалось новым.

У разных платформ разные ограничения, и то, что работает на одном устройстве, не
значит, что работает на любом. Перед релизом каждой платформе уделяется своё
внимание.

**Публичные настройки остаются публичными настройками.** Тот, кто держит второй
конец соединения, видит ваш трафик. Файл настроек может изменить кто угодно,
включая того, кто его только что скопировал. Такие настройки живут часами или днями,
и половина любого списка всегда нерабочая.

Приложение говорит обо всём этом на экране выбора подключения, а не в файле, который
никто не откроет. Оно показывает предупреждение над списком, проверяет, что вариант
действительно отвечает, прежде чем показать его рабочим, и позволяет удалить его из
истории навсегда.

---

## Приватность

Никакой телеметрии. Приложение ходит в очень немного мест: читает публичные
настройки, подключается к выбранному варианту и, если вы сами ввели адрес, к нему.
В сборке нет ни одного адреса разработчика.

Данные, прочитанные из публичных настроек, такие как идентификаторы и пароли, не
попадают в логи и не хранятся открытым текстом. В истории остаётся только достаточно,
чтобы опознать запись, но не её секреты. Экспортируемые логи не содержат доменов,
которые вы посещали.

Привилегированные действия выполняются только из фиксированного списка, без
подстановки пользовательского текста в команды. Вводимые адреса проверяются по
известному списку, а не запрашиваются как есть.

---

## Как это устроено

Один набор исходников, одна дизайн-система, две платформы: телефоны и десктопы.
Интерфейс, цвета и формулировки общие, поэтому функции не бывает в одной версии и
нет в другой.

Интерфейс держится на одной визуальной идее. Тёмная поверхность, один акцентный
цвет, медленно плывущее звёздное небо за содержимым и полупрозрачные панели поверх
него. Тема одна, и она тёмная.

Контраст цветов не оставлен на глаз. Каждая пара цвета текста и цвета фона
измеряется по опубликованному стандарту, и проверка выполняется как тест. Границы делятся на два вида: несущие смысл должны быть видны достаточно, а
декоративные под это правило не попадают. Слишком бледный цвет границы роняет
сборку.

Язык следует за системой: для региона СНГ русский, для китайского китайский, для
всего остального английский, а явный выбор всегда главнее. Каждая строка есть на
всех трёх языках, и тест роняет сборку, если ключ остался без перевода. Потерянный
перевод не должен доходить до экрана.

Исходный код свободен и открыт. Его можно читать, менять, собирать самому и
использовать в коммерции, в том числе в платных продуктах. Без платы, без доли от
дохода, без договора, без разрешения, о котором надо заранее просить.

---

## Где мы сейчас

Интерфейс на месте: общий код собирается под обе платформы, дизайн-система
закончена, тесты компонентов, палитры и языка проходят. Есть нижняя панель из пяти
разделов, страница настроек и отдельное окно мониторинга трафика, настройки
переживают перезапуск. Нет пока реального туннеля и обхода DPI, разведки устройства
с правами, загрузки конфигов извне и карты для подмены координат.

Работа разбита на этапы, и каждый этап заканчивается зелёной сборкой и зелёными
тестами.

---

# 关于本应用 (ZH)

一个完全在你自己的设备上运行的网络工具。无需注册账号，无需登录，没有我们的
服务器夹在中间，也不会向任何地方上报任何信息。打开应用，它在本地完成所有工作。

---

## 它如何工作

应用围绕四个彼此独立的领域构建。每个领域有自己的界面和自己的状态，可以单独
使用，不牵动其他领域。

### 私密通道

把流量封装进加密隧道，这样在链路上观察的人只能看到隧道存在，看不到里面的内容。

应用不为此运行任何自己的服务器。它读取他人已经公开的连接配置，检查哪些真的能
连通，然后提供可用的那些。你也可以自己指定任意来源。

配置有多种形式，常见的都能识别。解析不了的配置会被丢弃，而不是当成错误：一条
坏记录不该让整个列表清空。

配置不会只因为出现在列表里就被称作可用。在显示它可用之前，应用会真正建立一次
连接。界面呈现三种状态：尚未检查、有响应、无响应。结果会短暂缓存，避免应用
反复敲同一扇门。

用哪个引擎是可以选的，不是写死的决定。某个选项在某些连接上更强，另一个在手机上
更轻。两者都是开源的，选择放在设置里。

### 网络层解锁

有些服务方会在链路中途检查流量并决定放行什么。这个领域改变流量本身的形态，让
检查找不到可以反对的地方。

它需要较高权限，因为它工作在系统级数据包规则上。可选的策略有几种，从最安静到
最有效，在它们之间选哪个由你决定，而不是固定不变。

在拿不到较高权限的地方，这个领域没法诚实地工作。它不会假装，屏幕上会直接说明，
并指向下面那个不需要权限的做法。

### 握手内部解锁

第二种更细的办法。在连接建立的第一瞬间，此刻贴在其上的标识就在原地被改掉，赶在
有人读到它之前，连接随后原样传下去。应用看不出什么异常。

在桌面上它不需要管理员权限，所以当只想越过一个限制时，这是更省事的方案。应用
可以检查一个域名，并告诉你那里有没有限制、这个办法能不能绕过去。

### 位置

在手机上，应用可以报告与真实位置不同的坐标。系统会向用户询问权限，应用也如实、
公开地询问。

在桌面上这个领域不存在，而不是被隐藏。那里没有系统级的做法，一个只能工作一半的
仿制品还不如没有。

这一点值得直说：有些应用能察觉位置是被模拟的，因为系统提供了标准的询问方式。
应用不承诺把这一点藏起来。

---

## 这些领域如何组合

两个解锁领域解除限制，私密通道随后承载流量，位置则与网络完全无关。

它们可以组合而不互相依赖。单独使用其中任何一个都可以。

---

## 应用何时要什么

顺序是刻意的：先弄清楚这台设备能做什么，再只要最少的授权。

在请求任何东西之前，应用先确认这台设备上实际具备什么，并如实展示：有没有较高
权限、所需组件装没装、哪些领域能用、哪些不能用。答案里带着原因，不只是一句能或
不能。“不是满配”是个有用的答案，前提是说清了为什么。

权限在需要它的那块屏幕上单独请求，不是在首次启动时一股脑全要。主要领域在首次进入
时请求，因为大多数人装这个应用就是为了它。剩下的等到那块领域第一次被打开再说。

需要以较高权限执行的步骤，会先用一个对话框逐条列出将要做什么，做完这一步之前不
执行任何命令。任何一个命令都不会在没有明确同意的情况下跑起来。

不需要较高权限的领域会直接说明，并且不会仅仅为了让代码统一就去要求提权。

---

## 诚实的限制

应用不是用来绕开法律禁止的事项的。它也不去动内容保护和对请求签名的银行协议。

它不隐瞒位置被更改这一事实，因为系统要求权限，应用也公开地请求它。

有些做法比另一些更显眼。激进的策略会被精密设备看到。更安静的策略也有，并且标注
了出来，因为稳定性两个方向都不保证。

有些领域在不同设备和版本上表现不同。所以应用在应用任何东西之前先确认可行，并
展示结果，而不是假定。

四个领域是各自独立更新的项目。应用固定住它所使用的东西并加以校验，而不是每次
都取当时最新的。

不同平台的限制不同，某个设备能用不代表所有设备都能用。发布之前，每个平台都会
各自过一遍。

**公开配置终究是公开配置。** 连接另一端的人能看到你的流量。配置文件可以被任何人
修改，包括刚把它复制走的人。这类配置通常只活几小时或几天，任何列表里都总有一半
是坏的。

应用把这一切说在选择连接的那块屏幕上，而不是放在没人会打开的说明文件里。列表
上方会显示警告，在显示某项可用之前先确认它真的能连通，并且允许把它从历史里永久
删掉。

---

## 隐私

没有遥测。应用去的地方非常少：读取公开配置，连上你选的那一项，如果你自己填了
地址，就去那个地址。构建产物里没有任何开发者的地址。

从公开配置里读到的数据，比如标识和密码，不会写进日志，也不会明文保存。历史里只
留够认出某一条记录的部分，不留它的秘密。导出的日志不含你访问过的域名。

特权操作只从固定清单里执行，不会把用户输入拼进命令。填写的地址会对照已知清单
校验，而不是照原样去请求。

---

## 它是怎么构建的

一套源码，一套设计系统，两个平台：手机和桌面。界面、配色和措辞是共用的，所以
功能不会只存在于某个版本而在另一个版本里缺失。

界面围绕一个视觉想法展开。深色底、一个强调色、内容背后缓缓流动的星空，以及浮在
其上的半透明面板。主题只有一个，而且是深色的。

颜色对比度不靠眼睛判断。每一组前景色和背景色都按公开标准测量，检查以测试的形式跑。边框分成两种：承载意义的必须清晰可见，装饰性的不适用同一条规则。
一个看不见的边框颜色会让构建失败。

语言跟随系统：独联体地区用俄语，中文用中文，其余用英文，而明确的用户选择永远优先。
每条文案在三种语言里都存在，某个键缺翻译时测试会让构建失败。丢掉的翻译不允许
走到屏幕上。

源码自由且开放。你可以阅读、修改、自行构建，并用于商业用途，包括在付费产品中。
没有费用，没有收入分成，没有需要签的协议，也不需要事先征得许可。

---

## 现在到哪一步了

界面已经在了：共享代码在两个平台上都能构建，设计系统完成，组件、调色板和语言的
测试都通过。底部五个分区、设置页和独立的流量监控窗口都在，设置可以跨重启保留。
还没有的是真正的隧道与 DPI 解绕、带权限的设备探测、外部配置拉取，以及坐标伪装用的地图。

工作按阶段推进，每个阶段都以构建通过、测试通过收尾。
