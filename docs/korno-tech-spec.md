# Korno — Техническая спецификация (Android MVP)

## 1. Общий стек

| Слой | Технология | Комментарий |
|---|---|---|
| UI | Jetpack Compose | Единственный UI-тулкит, без View/XML |
| Архитектура | Clean Architecture (data / domain / presentation) | Модульно, с разделением по фичам |
| Паттерн презентации | MVI (Model-View-Intent) | Однонаправленный поток данных |
| DI | Koin | Легче Hilt, проще мигрировать на KMP в будущем |
| Сеть | Ktor Client | Пригодится сразу, когда появится сервер; сейчас — заглушка |
| Локальная БД | Room | На Android; при переходе на KMP — миграция на SQLDelight |
| Асинхронность | Kotlin Coroutines + Flow | Стандарт для MVI/Compose |
| Геолокация | FusedLocationProviderClient (Google Play Services) | + Activity Recognition API |
| Карта | Mapbox SDK или MapLibre (offline-friendly) | См. раздел 7 |
| Сериализация | kotlinx.serialization | Единый формат с Ktor |
| Навигация | Navigation Compose | Type-safe destinations (sealed classes) |
| Фоновая работа | Foreground Service + WorkManager | Service — во время записи; WorkManager — для отложенных задач (экспорт, синхронизация) |
| Хранение настроек | DataStore (Preferences) | Замена SharedPreferences |
| Тестирование | JUnit5, MockK, Turbine (для Flow), Compose UI Test | |
| Логирование | Kermit или Timber | Kermit — если планируется KMP в будущем |
| Работа с БД миграциями | Room Migrations / SQLDelight | |
| Экспорт треков | Собственный модуль GPX/TCX writer | Нет стабильной готовой библиотеки — пишем сами (это просто XML) |
| Барометр/сенсоры | SensorManager | Для будущей фичи уклона |
| Crash reporting | Firebase Crashlytics | Опционально, стоит решить вопрос приватности заранее |

---

## 2. Модульная структура проекта

Рекомендуется multi-module Gradle проект — это упростит будущий переход на KMP (shared-модули будет проще вычленить) и ускорит сборку.

```
Korno/
├── app/                          # точка входа, DI-graph, навигация
├── core/
│   ├── core-domain/              # общие доменные модели, use case интерфейсы
│   ├── core-ui/                  # темы, общие Composable, дизайн-система
│   ├── core-common/              # утилиты, Result-wrapper, расширения
│   └── core-database/            # Room, DAO, миграции
├── feature/
│   ├── feature-tracking/         # запись активности (сервис, экран записи)
│   │   ├── data/
│   │   ├── domain/
│   │   └── presentation/
│   ├── feature-activity-list/    # список активностей
│   ├── feature-activity-detail/  # детальный экран + корректировка трека
│   ├── feature-onboarding/       # онбординг + разрешения
│   └── feature-settings/         # настройки
├── data/
│   ├── location-tracking/        # обёртка над FusedLocationProvider, Activity Recognition
│   ├── gpx-export/                # экспорт/импорт GPX/TCX
│   └── remote/                    # Ktor client, DTO (задел под будущий сервер)
└── buildSrc/ или gradle/libs.versions.toml  # version catalog
```

**Принцип зависимостей**: `feature-*` модули не знают друг о друге напрямую, общаются через `core-domain` (интерфейсы use case) и навигацию по маршрутам. `data-*` модули не знают о presentation.

---

## 3. Clean Architecture — слои внутри каждой фичи

```
presentation/
  ├── TrackingScreen.kt          (Composable)
  ├── TrackingViewModel.kt       (MVI: принимает Intent, эмиттит State)
  ├── TrackingState.kt           (data class — State)
  ├── TrackingIntent.kt          (sealed interface — Intent)
  └── TrackingEffect.kt          (sealed interface — одноразовые события: навигация, снекбар)

domain/
  ├── model/                     (Track, GeoPoint, ActivityType — чистые Kotlin-модели)
  ├── usecase/                   (StartTrackingUseCase, StopTrackingUseCase, FilterOutliersUseCase...)
  └── repository/                (интерфейсы, например TrackRepository)

data/
  ├── repository/                (реализация TrackRepositoryImpl)
  ├── local/                     (Room entities, DAO)
  └── mapper/                    (Entity ↔ Domain model мапперы)
```

### MVI-контракт (пример для экрана записи)

```kotlin
sealed interface TrackingIntent {
    data object StartRecording : TrackingIntent
    data object PauseRecording : TrackingIntent
    data object ResumeRecording : TrackingIntent
    data object StopRecording : TrackingIntent
    data class SelectActivityType(val type: ActivityType) : TrackingIntent
}

data class TrackingState(
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val currentSpeed: Double = 0.0,
    val averageSpeed: Double = 0.0,
    val distance: Double = 0.0,
    val elapsedTime: Duration = Duration.ZERO,
    val movingTime: Duration = Duration.ZERO,
    val gpsSignalQuality: SignalQuality = SignalQuality.Unknown,
    val trackPoints: List<GeoPoint> = emptyList()
)

sealed interface TrackingEffect {
    data object NavigateToSummary : TrackingEffect
    data class ShowError(val message: String) : TrackingEffect
}

enum class RecordingStatus { Idle, Recording, ManualPause, AutoPause, Stopped }
```

ViewModel держит `MutableStateFlow<TrackingState>` и `Channel<TrackingEffect>` — стандартная связка для MVI на Compose.

---

## 4. Работа с фоновой геолокацией — ключевой технический узел

Это самая рискованная часть с точки зрения стабильности, стоит спроектировать отдельно.

### Компоненты
- **`LocationTrackingService`** (Foreground Service, тип `location`) — держит подписку на `FusedLocationProviderClient`, пишет точки в буфер и периодически флашит в Room.
- **`ActivityRecognitionReceiver`** — подписка на Activity Recognition API (Google Play Services), даёт грубую классификацию (still / walking / running / on_bicycle / in_vehicle) — используется для автоопределения остановок и как подсказка для типа активности.
- **Repository как единая точка правды**: ViewModel не общается с сервисом напрямую, а подписывается на Flow из репозитория (Service → Repository → ViewModel через shared Flow/StateFlow, синхронизация через биндинг к сервису или через локальную БД с realtime-подпиской Room `Flow<List<GeoPointEntity>>`).

### Обязательно предусмотреть
- **Восстановление после kill процесса**: состояние "идёт запись" должно переживать смерть процесса — хранить флаг в DataStore/БД, при перезапуске проверять и восстанавливать UI/сервис.
- **Adaptive GPS-частота**: при статусе `still` (от Activity Recognition) снижать частоту опроса или переходить на passive-режим — экономия батареи, критично для доверия пользователей.
- **Разрешения по шагам**: `ACCESS_FINE_LOCATION` → затем отдельно `ACCESS_BACKGROUND_LOCATION` (с pre-permission экраном перед системным диалогом) → `ACTIVITY_RECOGNITION`.
- **Battery optimization exemption**: попросить пользователя добавить приложение в исключения (диалог, не хардкор-требование).

---

## 5. Обработка трека (доменная логика — чистый Kotlin, легко тестируется и переносится на KMP)

Отдельный domain-модуль `core-track-processing` (не зависит от Android SDK вообще):

- **`OutlierFilterUseCase`** — фильтрация "телепортаций" по максимально правдоподобной скорости/ускорению между соседними точками.
- **`SmoothingUseCase`** — сглаживание (простой moving average или Kalman-фильтр) поверх отфильтрованных точек.
- **`StopDetectionUseCase`** — скользящее окно по скорости/смещению + данные Activity Recognition, помечает точки как "остановка".
- **`StatisticsCalculatorUseCase`** — считает дистанцию (Haversine/Vincenty), среднюю/макс скорость, время в движении (исключая остановки), набор высоты.
- **`ManualCorrectionUseCase`** — применяет пользовательские правки точек и пересчитывает статистику.

Важно: весь этот модуль должен работать с моделью `GeoPoint(lat, lon, timestamp, altitude, accuracy)` — своей, а не `android.location.Location`, чтобы модуль был чистым Kotlin и без изменений переехал в KMP shared в будущем.

---

## 6. База данных (Room) — примерная схема

```
Table: activities
  id, start_time, end_time, activity_type, title, note,
  distance, moving_time, elapsed_time, avg_speed, max_speed,
  elevation_gain, is_manually_edited

Table: geo_points
  id, activity_id (FK), lat, lon, altitude, timestamp,
  accuracy, speed, is_stop, is_outlier, is_manually_edited

Table: activity_type_segments (опционально, если сегментировать по видам активности внутри одного трека)
```

Индекс по `activity_id` в `geo_points` — обязателен, треки могут быть тысячами точек.

---

## 7. Карта

Варианты:
- **Mapbox SDK** — качественный рендеринг, хорошая offline-поддержка, но платный после лимита и требует токен/учёт трафика.
- **MapLibre** (форк Mapbox, open source) — бесплатно, хорошо подходит под идею "приватности" и независимости от вендора, требует своего источника тайлов (например, самостоятельно поднятый или сторонний OSM-based провайдер).
- **osmdroid** — более старый, но простой и полностью offline-friendly вариант для MVP, если не хочется сразу возиться с вендорами тайлов.

**Рекомендация для MVP**: начать с MapLibre + готовый бесплатный/дешёвый OSM-тайл провайдер (например, MapTiler free tier), т.к. это согласуется с позиционированием на приватность и не создаёт вендор-лока.

---

## 8. Экспорт/импорт (GPX/TCX)

Отдельный модуль `data-gpx-export`, чистый Kotlin + `kotlinx.serialization` для XML (или ручная сборка XML-строки, т.к. форматы простые). Не тянуть тяжёлые внешние библиотеки — GPX/TCX — это компактный XML-стандарт, легче написать свой сериализатор/десериализатор, чем разбираться с чужой малоподдерживаемой либой.

---

## 9. Сетевой слой (задел под будущий сервер)

Даже без готового сервера — заложить сразу:
- `Ktor Client` с `ContentNegotiation` (kotlinx.serialization)
- Интерфейс `RemoteApi` в data-слое, с заглушкой (`NoOpRemoteApi`), которая ничего не делает — это позволит безболезненно подключить реальный сервер потом, не трогая domain/presentation.
- Слой аутентификации — предусмотреть интерфейс `AuthRepository` заранее, даже если сейчас единственная реализация — "гость без аккаунта".

---

## 10. Дополнительные технологии, которые стоит заложить

| Технология | Зачем |
|---|---|
| **WorkManager** | Отложенный экспорт, будущая фоновая синхронизация с сервером |
| **DataStore** | Настройки пользователя (единицы измерения, авто-пауза и т.д.) |
| **Baseline Profiles** | Ускорение старта приложения — Android рекомендует для production |
| **Detekt / ktlint** | Статический анализ кода и единый стиль — важно держать чистоту в Clean Architecture с самого начала |
| **Version Catalog (libs.versions.toml)** | Управление версиями зависимостей в multi-module проекте |
| **Compose Navigation type-safe routes (Kotlin Serialization based)** | Безопасная навигация между экранами |
| **LeakCanary** (debug-сборка) | Поиск утечек памяти, критично при работе с сервисами/подписками |
| **Macrobenchmark** | Тестирование производительности записи трека (много точек = нагрузка на UI) |

---

## 11. Тестирование — что покрывать в первую очередь

- **Domain use cases** (фильтрация выбросов, расчёт статистики, определение остановок) — юнит-тесты, это самая критичная логика с точки зрения корректности данных.
- **Repository** — тесты с in-memory Room БД.
- **ViewModel (MVI)** — тесты через Turbine на потоки State/Effect.
- **UI** — Compose UI тесты для критичных экранов (запись, корректировка трека).

---

## 12. Итоговая схема потока данных (запись активности)

```
FusedLocationProviderClient
        ↓ (raw Location)
LocationTrackingService (Foreground Service)
        ↓ (GeoPoint, каждые N секунд)
TrackRepository (буферизация + запись в Room)
        ↓ (Flow<List<GeoPoint>>)
Domain UseCases (OutlierFilter → Smoothing → StopDetection → StatisticsCalculator)
        ↓
TrackingViewModel (State)
        ↓
TrackingScreen (Compose UI)
```

---