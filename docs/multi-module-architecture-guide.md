# Многомодульная архитектура Korno: подробное объяснение

Этот документ объясняет не только общую идею многомодульной архитектуры, но и то,
как она реализована именно в Korno. Он рассчитан на разработчика, который уже знаком
с Clean Architecture внутри одного `app`-модуля, но впервые видит большой Gradle-проект.

## 1. Короткий ответ: что это за архитектура

Korno использует сочетание трёх подходов:

1. **Clean Architecture** задаёт слои `presentation`, `domain` и `data` и направление
   зависимостей между ними.
2. **Feature-first** сначала группирует код по функциям продукта: запись трека,
   онбординг, список активностей, настройки и так далее.
3. **Gradle multi-module** превращает логические границы в физические: части программы
   компилируются как отдельные библиотеки, а доступ между ними нужно явно разрешать.

То есть это не замена знакомой Clean Architecture. Раньше границы слоёв существовали
в основном в соглашениях о пакетах внутри `app`. Теперь часть этих границ контролирует
Gradle.

Упрощённо проект выглядит так:

```text
                         :app
               сборка приложения целиком
                навигация + запуск Koin
                           |
          +----------------+----------------+
          |                                 |
   :feature:*                         общая инфраструктура
 функции приложения                 :core:* и :data:*
          |
 presentation -> domain <- data
                  |
              :core:domain
```

Самая важная мысль: **модуль отвечает за разрешённые зависимости, а пакет — только за
организацию имён внутри уже выбранного модуля**.

## 2. Чем Gradle-модуль отличается от пакета

Представим прежний одномодульный проект:

```text
app/
  com.example.tracking.presentation
  com.example.tracking.domain
  com.example.tracking.data
  com.example.settings.presentation
```

Разделение аккуратное, но любой класс из `tracking.presentation` технически может
импортировать Room DAO, класс экрана настроек или Android-сервис. Компилятор видит всё
это как один модуль. Запрет держится только на дисциплине команды.

В многомодульном проекте структура может быть такой:

```text
:feature:tracking:presentation
:feature:tracking:domain
:feature:tracking:data
```

Если в `presentation/build.gradle.kts` нет зависимости на Room или на модуль `data`,
такой импорт просто не скомпилируется. Это и есть главная сила модульности: архитектурное
правило становится проверяемым ограничением.

### Пакет

Пакет (`package com.ians.korno...`) решает следующие задачи:

- создаёт пространство имён;
- группирует файлы в IDE;
- влияет на package-level правила доступа Java.

Kotlin-модификатор `internal`, напротив, привязан именно к границе compile-модуля, а не
пакета: `internal`-класс доступен внутри своего Gradle-модуля и скрыт от соседнего.

### Gradle-модуль

Gradle-модуль:

- имеет собственный `build.gradle.kts`;
- объявляет свои плагины и зависимости;
- компилируется отдельной Gradle-задачей;
- создаёт отдельный артефакт: обычно AAR для Android library или JAR для pure Kotlin;
- имеет собственные source set: как минимум `main` и `test`, а Android-модули также
  могут иметь `androidTest`, `debug`, `release` и другие варианты;
- скрывает от потребителей зависимости, которых нет в его публичном API.

Модули не отменяют пакеты. Внутри каждого модуля всё равно остаются пакеты `model`,
`repository`, `usecase`, `di` и другие.

### Модуль — не отдельное приложение и не микросервис

Большинство модулей Korno — обычные библиотечные модули. При сборке их код и ресурсы
попадают в один APK. У них нет отдельных процессов, серверов или сетевых границ.

Название `feature` здесь также **не означает Play Dynamic Feature**. Фичи Korno не
скачиваются по требованию: это обычные Android library/JVM-модули, используемые для
структуры и контроля зависимостей.

## 3. Три независимые оси, которые легко перепутать

В названиях проекта одновременно отражены разные понятия.

| Ось | Вопрос | Примеры |
|---|---|---|
| Функция продукта | Для чего это пользователю? | tracking, onboarding, settings |
| Слой Clean Architecture | Какова роль кода? | presentation, domain, data |
| Тип сборки | Как это компилируется? | Android application, Android library, Kotlin/JVM |

Например, `:feature:tracking:domain` означает:

- функция продукта — запись активности;
- архитектурный слой — бизнес-правила;
- технически — отдельная pure Kotlin/JVM-библиотека.

А `:core:data` означает общий для нескольких фич data-код. Слово `core` говорит о
переиспользовании, а слово `data` — о роли относительно Clean Architecture.

## 4. Feature-first и layer-first

Есть два распространённых способа раскладывать приложение.

### Layer-first

```text
presentation/  все экраны приложения
domain/        все use case и модели
data/          все репозитории и источники данных
```

Это похоже на классический учебный пример Clean Architecture. Для небольшого проекта
структура простая, но при росте одна пользовательская функция оказывается размазана по
трём большим местам.

### Feature-first

```text
feature/tracking/
  presentation/
  domain/
  data/

feature/onboarding/
  presentation/
  domain/
  data/
```

Здесь сначала проводится вертикальная граница по функции продукта, а уже внутри неё —
горизонтальная граница по слоям. Чтобы изменить tracking, разработчик в основном работает
в одном поддереве.

Korno использует **feature-first с общими горизонтальными модулями**. Не всё можно
честно отнести только к одной фиче: модель `Activity`, база треков, дизайн-система,
форматирование единиц и карта нужны нескольким экранам. Такой код вынесен в `core` или
в специализированные верхнеуровневые `data`-модули.

## 5. Clean Architecture в этой схеме

Основное правило Clean Architecture звучит не как «обязательно иметь три папки», а как
**зависимости исходного кода направлены к бизнес-правилам**.

```text
presentation ------> domain <------ data
 UI, ViewModel       контракты       реализации,
 State, Action       и правила       Android, Room, GPS
```

### `domain`

Domain описывает, **что приложение умеет**, но не должен знать, **какой технологией это
сделано**.

Там находятся:

- бизнес-модели;
- интерфейсы репозиториев;
- use case;
- доменные ошибки;
- правила и вычисления.

Domain не должен импортировать `android.*`, Compose, Room, Koin или конкретный сетевой
клиент. Поэтому доменные модули Korno собираются плагином `korno.kotlin.domain` как
Kotlin/JVM, а не как Android library.

### `data`

Data знает, **как получить или сохранить данные**:

- Room и DAO;
- DataStore;
- GPS/Fused Location Provider;
- WorkManager и Android URI;
- сетевой клиент;
- DTO, entity и mapper;
- реализации доменных интерфейсов.

Data зависит от domain, потому что реализует объявленные там контракты.

### `presentation`

Presentation знает, **как показать состояние и принять действие пользователя**:

- Compose-экраны;
- ViewModel;
- UI state;
- action/event/effect;
- навигационные route и feature-граф;
- преобразование доменных значений в текст, цвет и формат для интерфейса.

Presentation вызывает use case из domain. Она не должна напрямую ходить в DAO,
`FusedLocationProviderClient` или `WorkManager`.

### Почему обе стрелки направлены в domain

Допустим, domain объявляет:

```kotlin
interface TrackRepository {
    fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>>
}
```

`presentation` использует этот контракт через use case. `data` содержит
`DefaultTrackRepository`, реализующий контракт через Room. Исходный код domain ничего не
знает о `DefaultTrackRepository`.

В runtime вызов, конечно, доходит от экрана до базы:

```text
Screen -> ViewModel -> UseCase -> TrackRepository -> DefaultTrackRepository -> Room DAO
```

Но compile-time зависимости устроены иначе:

```text
presentation -> domain <- data
```

Конкретную реализацию подставляет Koin при запуске приложения. Это и есть Dependency
Inversion: бизнес-контракт не зависит от инфраструктуры; инфраструктура зависит от
бизнес-контракта.

## 6. Что в проекте действительно является модулем

Список задаётся в [`settings.gradle.kts`](../settings.gradle.kts). Запись
`include(":feature:tracking:domain")` создаёт модуль с Gradle-путём
`:feature:tracking:domain`, которому соответствует каталог
`feature/tracking/domain/`.

На данный момент корневая сборка содержит **30 обычных модулей**:

- 1 модуль приложения;
- 8 модулей `core`;
- 4 верхнеуровневых модуля `data`;
- 17 модулей внутри `feature`.

Каталоги `core/`, `data/`, `feature/`, `feature/tracking/` сами по себе не обязательно
модули. Например, `:feature:tracking` не объявлен и не имеет своего
`build.gradle.kts`; модулями являются три его дочерних каталога.

`build-logic` стоит немного особняком. Он подключён через
`pluginManagement.includeBuild("build-logic")` как **included build**, а не через обычный
`include(":build-logic")`. Это отдельная служебная Gradle-сборка, поставляющая плагины
основной сборке; в APK она не входит.

## 7. Полная карта модулей Korno

### `:app` — оболочка и composition root

Ключевые файлы: `KornoApplication`, `MainActivity`, `KornoApp`, `KornoAppViewModel`.

Ответственность модуля:

- создаёт Android application и итоговый APK;
- запускает Koin и подключает DI-модули;
- собирает корневой `NavHost` из графов отдельных фич;
- связывает переходы между фичами callback-ами;
- выбирает стартовый экран;
- содержит действительно глобальную оркестрацию приложения.

`app` знает обо всех нужных частях, потому что кто-то должен собрать объектный граф и
навигацию целиком. Это допустимое исключение: **composition root зависит от деталей, но
детали не должны зависеть от composition root**.

`app` не должен становиться местом для всей бизнес-логики. Если код относится только к
экрану tracking, он должен жить в tracking, даже если добавить его в `app` проще.

### Группа `core`

`core` — код, который нужен нескольким функциям приложения, либо самостоятельная общая
возможность с достаточно большой реализацией.

| Модуль | Что находится внутри | Почему общий |
|---|---|---|
| `:core:domain` | `Activity`, `GeoPoint`, `UserPreferences`, typed `Result`, ошибки, интерфейсы `TrackRepository`, `TrackFileRepository`, `MapCacheRepository` | Это общий язык, которым пользуются разные фичи |
| `:core:data` | реализации `TrackRepository` и `UserPreferencesRepository`, Room data source, DataStore, mapper | Одни и те же активности и настройки нужны нескольким фичам |
| `:core:database` | `KornoDatabase`, entity, DAO, Room-схемы и DI | Единая база приложения используется несколькими data-компонентами |
| `:core:design-system` | тема, цвета, типографика, формы и общие Compose-элементы | Единый визуальный язык для всех экранов |
| `:core:presentation` | общее UI-форматирование расстояния, скорости, высоты и коротких расстояний | Одинаковое представление величин на разных экранах |
| `:core:track-processing` | фильтрация выбросов, сглаживание, остановки, статистика, коррекция и privacy zone | Чистые алгоритмы нужны записи, деталям и экспорту |
| `:core:map` | MapLibre-обёртки, `TrackMap`, превью, слои и реализация кэша карты | Карта используется на нескольких экранах |
| `:core:logging` | собственный logging API, debug-реализация, release no-op и диагностические файлы | Сквозная техническая возможность для разных модулей |

Важный нюанс: `core` не означает «самый внутренний слой». Например, `:core:domain`
действительно внутренний и чистый, а `:core:data` зависит от Room, `:core:map` — от
MapLibre и Compose. `core` здесь означает прежде всего **shared**, поэтому после двоеточия
всё равно нужно смотреть на конкретную ответственность.

### Верхнеуровневая группа `data`

Эти модули представляют специализированные технические возможности, не принадлежащие
одному экрану.

| Модуль | Назначение | Тип |
|---|---|---|
| `:data:location-tracking` | Fused Location Provider, получение GPS-сэмплов, проверка разрешений и диагностические системные сигналы | Android library |
| `:data:gpx-export` | чтение и запись GPX/TCX, XML codec и промежуточная модель документа | pure Kotlin/JVM |
| `:data:track-io` | Storage Access Framework, `DocumentStore`, WorkManager-задачи импорта/экспорта и реализация `TrackFileRepository` | Android library |
| `:data:remote` | заготовка для Ktor-клиента и будущего сервера | Android library, пока без Kotlin-кода |

Почему это не `feature/.../data`? GPS-провайдер или GPX codec — не самостоятельный экран
и не частная деталь одного UI-сценария. Например, GPS используется tracking и onboarding,
а файловые операции используются списком, деталями и настройками.

Граница между `core:data` и верхнеуровневым `data/*` — проектное соглашение, а не закон
Gradle. В Korno `core:data` хранит центральные общие репозитории приложения, а `data/*` —
более изолированные инфраструктурные возможности. При чтении кода надёжнее смотреть на
зависимости модуля, чем пытаться вывести всё только из имени каталога.

### Группа `feature`

Каждая группа представляет пользовательский сценарий.

| Фича | Модули | Реальная ответственность сейчас |
|---|---|---|
| `tracking` | `domain`, `data`, `presentation` | подготовка, старт, пауза, запись, foreground service, восстановление и экран сохранения |
| `onboarding` | `domain`, `data`, `presentation` | модель и контракт разрешений, Android-реализация, UI онбординга |
| `activity-list` | `domain`, `data`, `presentation` | наблюдение списка, импорт, превью маршрутов и UI списка |
| `activity-detail` | `domain`, `data`, `presentation` | детали, удаление, экспорт/отправка, карта и коррекция трека |
| `settings` | `domain`, `data`, `presentation` | экспорт всех треков, управление кэшем карты и UI настроек |
| `home` | `domain`, `presentation` | агрегат главного экрана и UI; отдельной data-реализации сейчас не требуется |

Не нужно ожидать, что каждая фича всегда обязана иметь непустую тройку модулей.

Сейчас `activity-list/data`, `activity-detail/data` и `settings/data` существуют как
пустые заготовки: их `build.gradle.kts` уже задают предполагаемые зависимости, но Kotlin-
реализаций там нет. Эти фичи работают через общие репозитории из `core:domain`, реализации
которых находятся в `core:data`, `core:map` и `data:track-io`. У `home` data-модуль вообще
не создан, потому что собственной инфраструктуры у фичи нет.

Это хороший пример правила: **слой является смысловой ролью, а не обязательной папкой**.
Пустой модуль можно оставить как подготовленный каркас, но он имеет цену в виде лишней
Gradle-конфигурации и задач. Если долгосрочной необходимости нет, создавать тройку «для
симметрии» необязательно.

## 8. Фактическое направление зависимостей Korno

Полный граф довольно большой, но его полезная часть сводится к следующей схеме:

```text
:app
  |
  +--> :feature:*:presentation --> своя :feature:*:domain
  |             |                         |
  |             +--> :core:presentation   +--> :core:domain
  |             +--> :core:design-system
  |             +--> :core:map (там, где нужна карта)
  |
  +--> :feature:*:data --------> своя :feature:*:domain
  |             +-------------> :core:data / :core:database / :data:*
  |
  +--> :core:data -------------> :core:domain + :core:database
  +--> :core:database ---------> :core:domain
  +--> :data:track-io ---------> :core:domain + :data:gpx-export
  +--> :data:location-tracking -> :core:domain / :core:data
  +--> :core:track-processing -> :core:domain
```

Практические правила, которые уже отражены в Gradle-файлах:

- `feature:*:presentation` зависит от собственного `domain`, но не от собственного
  `data`;
- `feature:*:data` зависит от собственного `domain` и реализует его контракты;
- feature-модули не объявляют прямых зависимостей на другие feature-модули;
- почти все пути сходятся к `:core:domain`;
- `:app` находится наверху графа и связывает всё вместе;
- циклических зависимостей быть не должно: Gradle не сможет построить такой граф.

Есть точечные исключения из самой упрощённой учебной картинки. Например,
`:feature:activity-detail:domain` экспортирует API из `:core:track-processing`, потому что
его публичные use case используют типы обработки трека. `:core:map` объединяет reusable
Compose UI и MapLibre-инфраструктуру в одной ограниченной карте-возможности. Это не ломает
общую идею, но показывает, что реальная архитектура всегда богаче схемы из трёх коробок.

## 9. Как проходит реальный сценарий записи трека

Рассмотрим один вертикальный срез, чтобы архитектура перестала быть абстракцией.

### Команда пользователя

```text
TrackingRecordingScreen
        |
        | TrackingRecordingAction.PauseClicked
        v
TrackingRecordingViewModel                  presentation
        |
        | pauseTracking()
        v
PauseTrackingUseCase                        domain
        |
        | TrackingController.pause()
        v
DefaultTrackingController                   data
        |
        | Intent с ACTION_PAUSE
        v
LocationTrackingService / Coordinator       data
```

Экран не знает о сервисе и Android `Intent`. Use case не знает, как именно выполняется
пауза. Android-детали находятся во внешнем data-слое.

### Поток координат и состояния обратно на экран

```text
FusedLocationProviderClient                 :data:location-tracking
        v
LocationTrackingService                     :feature:tracking:data
        v
TrackingSessionCoordinator                  :feature:tracking:data
        |
        +--> TrackProcessor                  :core:track-processing
        |
        +--> TrackRepository
                    v
             DefaultTrackRepository          :core:data
                    v
                 TrackDao                     :core:database

TrackingSessionCoordinator -- StateFlow --> use case --> ViewModel --> Compose
```

`TrackingSessionCoordinator` — важный интеграционный компонент tracking: он хранит
текущее состояние сессии, обрабатывает GPS-сэмплы, вызывает алгоритмы обработки и
сохраняет точки через общий `TrackRepository`.

Этот пример также показывает, почему не вся реализация помещается в один
`feature:tracking:data`: GPS-клиент, база и алгоритмы имеют самостоятельную ценность и
используются за пределами одной фичи.

## 10. Как фичи общаются, не завися друг от друга

Плохой вариант:

```text
:feature:home:presentation --> :feature:tracking:presentation
```

После нескольких таких связей все фичи превращаются в запутанный монолит, только
разнесённый по каталогам.

В Korno корневую навигацию собирает `KornoApp` из `app`:

```text
homeGraph(onStartSetup = ...)
trackingGraph(onFinished = ...)
activityListGraph(onOpenActivity = ...)
activityDetailGraph(...)
settingsGraph(...)
```

Фича публикует свой route/graph и принимает callback для выхода наружу. Она не обязана
импортировать экран назначения. `app`, который и так знает весь граф приложения,
соединяет callback одной фичи с route другой.

Общие данные передаются через маленькие route-аргументы, например `activityId`, а не
через передачу целого ViewModel или data-реализации между фичами.

## 11. Где и как собирается Dependency Injection

Koin-модули лежат рядом с реализациями:

```kotlin
singleOf(::DefaultTrackRepository) bind TrackRepository::class
singleOf(::MapLibreCacheRepository) bind MapCacheRepository::class
single { DefaultTrackingController(get(), get()) } bind TrackingController::class
```

Такая запись означает: когда потребитель просит доменный интерфейс, Koin отдаёт
конкретную реализацию внешнего слоя.

В `KornoApplication` перечислены `databaseModule`, `coreDataModule`, `mapModule`,
`trackingDataModule`, presentation-модули фич и остальные bindings. Поэтому
`KornoApplication` является **composition root** — единственным местом, где допустимо
знать общую картину реализаций.

Не следует путать:

- **Gradle dependency** разрешает одному модулю скомпилировать импорт другого;
- **Koin dependency injection** создаёт и соединяет объекты во время выполнения.

Koin не может исправить отсутствующую Gradle-зависимость. И наоборот, добавление
`implementation(project(...))` не учит Koin, какой implementation надо подставить вместо
интерфейса.

## 12. Зачем нужен `build-logic`

Без общей логики каждый Android-модуль повторял бы десятки строк:

- Android/Kotlin plugins;
- `compileSdk`, `minSdk`, Java version;
- Compose setup и BOM;
- стандартные test dependencies;
- Koin, Room, Ktor и serialization dependencies;
- namespace и test runner.

В Korno повторяемая настройка оформлена как **convention plugins** — собственные Gradle-
плагины проекта.

| Плагин Korno | Что делает |
|---|---|
| `korno.android.application` | применяет Android application, общие SDK/JVM-настройки, `applicationId`, версию и build types |
| `korno.android.library` | применяет Android library, общие настройки и генерирует namespace из Gradle-пути |
| `korno.android.compose` | включает Compose и добавляет Compose BOM, Material 3, UI, tooling и UI-test зависимости |
| `korno.android.feature` | собирает стандартный presentation-модуль: Android library + Compose + Koin + serialization + собственный domain + общие UI-модули |
| `korno.android.koin` | добавляет Koin Android |
| `korno.android.ktor` | добавляет Ktor/OkHttp, content negotiation, logging и serialization |
| `korno.android.room` | применяет Room и KSP, добавляет зависимости и каталог схем |
| `korno.kotlin.domain` | создаёт pure Kotlin/JVM-модуль с Java toolchain 17, Coroutines и тестовым стеком |
| `korno.kotlin.serialization` | применяет Kotlin serialization и JSON |

Поэтому `feature/home/presentation/build.gradle.kts` может быть почти пустым:

```kotlin
plugins {
    id("korno.android.feature")
}
```

Плагин сам вычисляет соседний путь `:feature:home:domain` и добавляет стандартные
зависимости presentation-слоя.

Название `korno.kotlin.domain` описывает типовой набор Gradle-настроек, а не всегда
архитектурный смысл пользователя этого плагина. Его также применяют
`:core:track-processing` и `:data:gpx-export`, потому что им нужен чистый Kotlin/JVM без
Android, хотя GPX codec архитектурно относится к data.

## 13. За что отвечают остальные Gradle-файлы

### `settings.gradle.kts`

Это каталог сборки. Он:

- задаёт имя root project;
- подключает все 30 модулей;
- подключает `build-logic`;
- задаёт репозитории плагинов и библиотек;
- запрещает отдельным модулям произвольно объявлять свои Maven-репозитории.

Если создать каталог с `build.gradle.kts`, но не добавить его через `include`, основная
сборка не увидит этот модуль.

### Корневой `build.gradle.kts`

Сейчас он почти пуст, потому что общая конфигурация вынесена в `build-logic`. Это
нормально: root build file не обязан содержать код ради самого факта существования.

### `build.gradle.kts` конкретного модуля

Он отвечает только за особенности модуля:

- какой convention plugin применить;
- какие project dependencies разрешить;
- какие внешние библиотеки нужны только здесь;
- редкие локальные Android-настройки.

Например, `:core:map` отдельно включает генерацию `BuildConfig` и записывает туда URL
стилей карты, а `:core:database` применяет Room plugin.

### `gradle/libs.versions.toml`

Это **Version Catalog** — единое место для версий и координат внешних зависимостей.

```kotlin
implementation(libs.maplibre.android.sdk)
```

означает: alias и версия MapLibre берутся из `libs.versions.toml`. Благодаря этому не
нужно искать и синхронно обновлять строку версии в десятках модулей.

Version Catalog управляет внешними библиотеками. Связи между локальными модулями
пишутся как `project(":core:domain")`.

### Gradle Wrapper и `gradle.properties`

Wrapper (`gradlew`, `gradle/wrapper/*`) закрепляет способ запуска и версию Gradle, чтобы
сборка не зависела от глобально установленного Gradle. `gradle.properties` содержит
общие флаги и настройки процесса сборки.

## 14. `implementation`, `api` и variant-specific зависимости

### `implementation`

```kotlin
implementation(project(":core:domain"))
```

Модуль может использовать `core:domain`, но старается не выставлять эту зависимость как
часть своего публичного API. Это уменьшает связанность и область перекомпиляции.

### `api`

```kotlin
api(project(":core:domain"))
```

Зависимость становится видимой потребителю. Это необходимо, если публичные классы и
функции модуля возвращают или принимают типы из этой зависимости, и потребителю нужно
видеть их для компиляции.

Поэтому `:data:gpx-export` использует `api(:core:domain)`: его публичный codec работает с
доменными `Result` и моделями. Аналогично `:feature:activity-detail:domain` использует
`api(:core:track-processing)`, потому что типы обработки присутствуют в публичном API.

Практическое правило: начинать с `implementation`; переходить на `api`, только когда
зависимый тип действительно является частью публичного контракта.

### `debugImplementation` и source sets

`core:logging` хорошо показывает возможности вариантов сборки:

```text
src/main/       общий logging API
src/debug/      настоящий DebugLogger и диагностическое хранилище
src/release/    no-op bindings
```

`debugImplementation` добавляет Kermit, DataStore и JSON только в debug-вариант. Release
не тащит конкретный backend и связывает logging API с no-op реализациями.

Другие часто встречающиеся конфигурации:

- `testImplementation` — JVM unit tests;
- `androidTestImplementation` — тесты на устройстве/эмуляторе;
- `ksp` — процессор кода, здесь Room compiler;
- `testRuntimeOnly` — зависимость нужна только при запуске теста.

## 15. Почему такая архитектура может ускорять сборку

Многомодульность не делает любую сборку автоматически быстрее. Возможные улучшения:

- Gradle может выполнять независимые задачи параллельно;
- результат сборки неизменившегося модуля можно взять из build cache;
- изменение implementation, не затронувшее публичный API, не обязано пересобирать всех
  потребителей;
- разработчик может запускать тест или компиляцию конкретного модуля.

Например, изменение внутреннего XML parser в `:data:gpx-export` не должно заставлять
перекомпилировать весь Compose UI при корректно настроенном графе.

Но есть и обратная сторона:

- каждый модуль добавляет конфигурацию и Gradle-задачи;
- clean build большого числа крошечных модулей может быть медленнее;
- зависимость от часто меняющегося общего модуля, особенно `core`, инвалидирует много
  потребителей;
- неправильное использование `api` расширяет область перекомпиляции.

Поэтому цель — не максимальное число модулей, а разумные стабильные границы.

## 16. Преимущества многомодульной архитектуры

### Проверяемые границы

Компилятор не позволяет presentation случайно использовать недоступную data-деталь. Это
существенно надёжнее договорённости о пакетах.

### Локальность изменений

Код одного сценария собран рядом. Изменение tracking реже затрагивает settings или
activity detail.

### Явный граф зависимостей

По `build.gradle.kts` можно понять, с чем связана часть системы. Скрытые связи становятся
заметнее на code review.

### Независимое тестирование

Pure Kotlin domain и track-processing тестируются без Android-устройства. Можно запустить
тесты одного модуля и получить более быстрый feedback loop.

### Повторное использование без копирования

Дизайн-система, карта, форматирование, модели и обработка трека имеют одно общее место.

### Работа команды

Разные разработчики могут менять разные фичи с меньшим количеством конфликтов. У модуля
можно определить владельца и публичный API.

### Подготовка к KMP

Чистые Kotlin-модули уже отделены от Android API. Это не делает проект KMP автоматически,
но `core:domain`, `core:track-processing` и `data:gpx-export` будет проще перенести в
shared source set, чем извлекать из большого Android `app` постфактум.

### Контроль тяжёлых зависимостей

MapLibre нужен только модулям, использующим `core:map`; Room — data/database слоям;
Compose — presentation. Архитектурный слой не получает всю classpath приложения.

## 17. Недостатки и цена

### Более высокий порог входа

Нужно одновременно понимать Clean Architecture, Gradle paths, dependency configurations,
convention plugins, DI и feature navigation. Это именно та цена, которая заметна при
первом знакомстве с Korno.

### Больше служебных файлов

Появляются `settings.gradle.kts`, много module build files, DI-модули, публичные контракты
и иногда mapper между типами.

### Сложнее найти код без карты проекта

Одно действие проходит через несколько модулей. Без понимания вертикального потока
переходы ViewModel → use case → interface → implementation кажутся лишними.

### Риск чрезмерной модульности

Модуль из одного helper-класса обычно не окупает Gradle overhead. Пустые каркасные модули
тоже следует периодически переоценивать.

### Риск «shared-свалки»

Если переносить в `core` всё, что потенциально когда-нибудь пригодится, core станет новым
монолитом. Код стоит делать общим после реального использования несколькими фичами или
когда он представляет явно самостоятельную платформенную возможность.

### Более сложные рефакторинги публичного API

Перемещение типа через границу модуля требует обновить build dependencies, видимость и
иногда DI. `internal` больше не виден соседнему модулю.

### Возможные проблемы графа

Легко добавить лишнюю транзитивную зависимость или попытаться создать цикл. Иногда ради
разрыва цикла приходится выделять маленький контракт в более внутренний модуль.

## 18. Когда многомодульность оправдана

Она особенно полезна, когда:

- приложение имеет несколько достаточно независимых пользовательских функций;
- кодовая база и команда растут;
- нужно строго отделить Android/Room/network от бизнес-логики;
- есть тяжёлые библиотеки, которые нужны только части проекта;
- важны параллельная разработка и ownership;
- ожидается повторное использование или перенос части кода в KMP;
- сборка может выигрывать от изолированной инкрементальной компиляции.

Она может быть избыточна, когда:

- это прототип из двух-трёх экранов;
- проект короткоживущий;
- один разработчик часто меняет всё приложение целиком;
- границы предметной области ещё не понятны и постоянно двигаются;
- служебная сложность обходится дороже потенциальной изоляции.

Нормальный путь развития — начать с хороших пакетов и выделять модули вокруг зрелых
границ. Модульность — инструмент управления связанностью, а не показатель качества сам
по себе.

Для Korno она в целом оправдана: запись GPS, фоновой сервис, база, карты, алгоритмы,
импорт/экспорт и несколько экранных сценариев уже образуют заметно разные области.

## 19. Как быстро ориентироваться в Korno

При поиске реализации лучше идти по сценарию, а не читать модули по алфавиту.

### Если работа начинается с экрана

1. Открыть `feature/<name>/presentation`.
2. Найти `*Navigation.kt`, затем `*Screen.kt`.
3. Посмотреть `Action`, `State` и `ViewModel`.
4. По зависимостям ViewModel перейти к use case в `feature/<name>/domain`.
5. По интерфейсу репозитория/контроллера найти binding `bind ...::class` через поиск.
6. Из binding перейти к реализации в `data`, `core:data`, `core:map` и так далее.

Полезные поисковые запросы:

```bash
rg "class TrackingRecordingViewModel"
rg "interface TrackingController"
rg "bind TrackingController::class"
rg "trackingPresentationModule|trackingDataModule"
```

### Если работа начинается с данных

1. Найти доменный интерфейс в `core:domain/repository` или `feature/*/domain/repository`.
2. Найти Koin binding этого интерфейса.
3. Перейти к реализации repository/controller.
4. Затем к data source, DAO, Android API или codec.
5. Проверить, какие use case используют контракт.

### Если непонятно, откуда берётся зависимость

Проверять в таком порядке:

1. `build.gradle.kts` текущего модуля;
2. применённый convention plugin в `build-logic`;
3. `libs.versions.toml` для внешней библиотеки;
4. Koin-модуль — только если вопрос о создании runtime-объекта, а не о compile classpath.

## 20. Куда класть новый код

Используйте следующий порядок вопросов.

```text
Код относится только к одной пользовательской функции?
  |
  +-- да --> feature/<name>/...
  |             |
  |             +-- UI/ViewModel/navigation? --> presentation
  |             +-- правило/use case/model/interface? --> domain
  |             +-- Android/Room/network/implementation? --> data
  |
  +-- нет --> он реально нужен двум и более фичам?
                |
                +-- общий бизнес-контракт/модель? --> core:domain
                +-- общий UI? --> core:presentation или core:design-system
                +-- общая БД/data implementation? --> core:database / core:data
                +-- сложная самостоятельная возможность? --> отдельный core/data-модуль
```

Уточняющие правила:

- одноразовый маленький helper не заслуживает нового модуля;
- «возможно пригодится» недостаточно для переноса в `core`;
- фича не должна зависеть от другой фичи;
- cross-feature переход связывает `app`;
- domain не получает Android-зависимости;
- интерфейс кладётся во внутренний слой, реализация — во внешний;
- `app` содержит wiring и глобальную оркестрацию, а не детали конкретного экрана.

### Пример: новая бизнес-функция «цели тренировок»

Если это самостоятельный набор экранов и правил:

```text
:feature:goals:domain
:feature:goals:data
:feature:goals:presentation
```

Нужно будет:

1. добавить нужные модули в `settings.gradle.kts`;
2. применить `korno.kotlin.domain`, `korno.android.library` и
   `korno.android.feature` соответственно;
3. объявить только необходимые project dependencies;
4. создать domain API и use case;
5. реализовать data-контракт и Koin binding;
6. создать presentation graph;
7. подключить DI и graph в `app`;
8. проверить, что ни одна существующая фича не стала зависеть от `feature:goals` напрямую.

Если же это всего один дополнительный показатель на Home, новая тройка модулей может
быть лишней: код разумнее добавить в существующую home-фичу.

## 21. Что в текущей структуре следует помнить особо

1. **Это Android multi-module, пока не KMP.** Некоторые модули чистые Kotlin/JVM и готовы
   к будущему переносу лучше остальных, но KMP targets и source sets сейчас не настроены.
2. **`core` означает shared, а не один конкретный архитектурный слой.** Всегда читайте
   вторую часть имени: `core:domain`, `core:data`, `core:map` имеют разную степень
   близости к платформе.
3. **Три разных места со словом `data` не дублируют друг друга буквально.**
   `feature/*/data` — детали конкретной фичи; `core:data` — центральные общие реализации;
   `data/*` — специализированные инфраструктурные возможности.
4. **Некоторые data-модули — каркасы.** Отсутствие классов в них не означает, что экран
   не работает; его данные могут приходить из shared repository.
5. **Convention plugins скрывают много конфигурации.** Короткий module build file не
   означает отсутствие Compose, Koin или тестовых библиотек.
6. **`app` намеренно зависит от многих модулей.** Он является точкой окончательной
   сборки. Проблемой была бы обратная зависимость фичи на `app`.
7. **Публичный API модуля важнее его внутренней структуры.** Чем меньше публичных типов
   и `api` dependencies, тем легче менять реализацию независимо.

## 22. Справочник прямых project dependencies

Ниже перечислены именно зависимости между локальными Gradle-модулями. Внешние библиотеки
вроде Compose, Room, Koin и MapLibre здесь не показаны.

### Core и общая data-инфраструктура

| Модуль | Прямые локальные зависимости |
|---|---|
| `:core:domain` | нет |
| `:core:track-processing` | `:core:domain` |
| `:core:design-system` | нет |
| `:core:presentation` | `:core:domain`, `:core:design-system` |
| `:core:database` | `:core:domain` |
| `:core:data` | `:core:domain`, `:core:database` |
| `:core:map` | `:core:domain` |
| `:core:logging` | нет локальных project dependencies |
| `:data:gpx-export` | `:core:domain` через `api` |
| `:data:location-tracking` | `:core:data`, `:core:domain` |
| `:data:track-io` | `:core:domain`, `:core:logging`, `:core:track-processing`, `:data:gpx-export` |
| `:data:remote` | `:core:data`, `:core:domain` |

`data:remote` уже имеет сборочную конфигурацию Ktor, но Kotlin-кода пока не содержит.

### Feature domain

| Модуль | Прямые локальные зависимости |
|---|---|
| `:feature:home:domain` | `:core:domain` |
| `:feature:onboarding:domain` | `:core:domain` |
| `:feature:tracking:domain` | `:core:domain` |
| `:feature:activity-list:domain` | `:core:domain` |
| `:feature:settings:domain` | `:core:domain` |
| `:feature:activity-detail:domain` | `:core:domain`, `:core:track-processing` через `api` |

### Feature data

| Модуль | Прямые локальные зависимости |
|---|---|
| `:feature:onboarding:data` | свой `domain`, `:core:data`, `:core:domain`, `:data:location-tracking` |
| `:feature:tracking:data` | свой `domain`, `:core:data`, `:core:database`, `:core:domain`, `:core:logging`, `:core:track-processing`, `:data:location-tracking` |
| `:feature:activity-list:data` | свой `domain`, `:core:data`, `:core:database`, `:core:domain`; пока без Kotlin-кода |
| `:feature:activity-detail:data` | свой `domain`, `:core:data`, `:core:database`, `:core:domain`, `:core:track-processing`, `:data:gpx-export`; пока без Kotlin-кода |
| `:feature:settings:data` | свой `domain`, `:core:data`, `:core:domain`; пока без Kotlin-кода |

### Feature presentation

Для каждого `:feature:<name>:presentation` плагин `korno.android.feature` автоматически
добавляет:

```text
свой :feature:<name>:domain
:core:domain
:core:presentation
:core:design-system
```

Поверх этого конкретные фичи добавляют:

| Модуль | Дополнительные локальные зависимости |
|---|---|
| `:feature:home:presentation` | нет |
| `:feature:onboarding:presentation` | нет |
| `:feature:activity-list:presentation` | `:core:map` |
| `:feature:activity-detail:presentation` | `:core:logging`, `:core:map` |
| `:feature:settings:presentation` | `:core:logging` |
| `:feature:tracking:presentation` | `:core:logging`, `:core:map` |

`app` затем зависит от нужных core/data модулей, data и presentation частей фич и от
двух feature-domain модулей, типы которых использует напрямую (`onboarding:domain` и
`tracking:domain`). Точные строки находятся в [`app/build.gradle.kts`](../app/build.gradle.kts).

Этот список полезен как снимок текущего состояния, но он не является вечным правилом:
по мере развития функций зависимости могут меняться. Неизменным должно оставаться их
правильное направление и отсутствие циклов.

## 23. Итоговая ментальная модель

Можно представить Korno как школу:

- `domain` — учебная программа: чему учим и какие правила результата;
- `data` — лаборатории и оборудование: как добыть реальные данные;
- `presentation` — урок для ученика: как показать материал и принять ответ;
- `core` — общая библиотека, спортзал и инфраструктура, которыми пользуются разные
  классы;
- `feature` — отдельные учебные курсы;
- `app` — расписание и администрация, которые собирают школу в единое целое;
- `build-logic` — единые правила оснащения кабинетов;
- Gradle — система, которая проверяет, какие кабинеты кому доступны, и собирает всё
  здание.

Если оставить одну формулу, то она такая:

> Korno — это Clean Architecture, разрезанная по функциям продукта и закреплённая
> отдельными Gradle-модулями; `app` собирает систему, `feature` содержит пользовательские
> сценарии, `core` — общий код, а `data` — платформенные и инфраструктурные реализации.
