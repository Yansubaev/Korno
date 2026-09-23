---
name: activity-logs
description: |
  Разбор debug-логов записанных активностей Korno (korno-activity-<id>-<sessionId>.zip, logs/*.jsonl, diagnostics.json). Use when the user asks to look at, read or analyze activity logs — «посмотри логи активности», «что в логах тренировки», «почему трек/автопауза/сегменты такие, по логам».
---

# Логи активности Korno

Источник правды о формате, сообщениях и полях — `docs/debug-logging-guide.md`. Прочитай его целиком до первого запроса к логам: навык говорит, **в каком порядке** читать, гайд — **что** значит каждое поле.

## Шаги

### 1. Найти архивы

Архивы лежат в корне репозитория (`korno-activity-*.zip`, в `.gitignore`), если пользователь не дал другой путь. Имя: `<activityId>-<sessionId>`; одинаковый `sessionId` — один запуск приложения. Не распаковывай в репозиторий, читай потоком:

```bash
unzip -p korno-activity-34-c6b7f566.zip diagnostics.json
unzip -p korno-activity-34-c6b7f566.zip 'logs/*.jsonl' | jq -c '...'
```

Файлы по 0.2–2.5 МБ и тысячи строк: читай **агрегатами и выборками** через `jq`, а не целиком.

`loggingLevel` и `locationSamplesIncluded` в `diagnostics.json` — настройки на момент выгрузки, а не записи. Есть ли координаты на самом деле, смотри по числу строк `Location sample coordinates`.

Готово, когда для каждого архива, о котором спросили, известны `activityId`, `sessionId` и есть ли в логе координаты.

### 2. Перепись сообщений

```bash
unzip -p "$Z" 'logs/*.jsonl' | jq -r '"\(.category)\t\(.level)\t\(.message)"' | sort | uniq -c | sort -rn
```

Набор сообщений зависит от сборки, которая писала лог, а `versionName` у всех сборок одинаковый. Отсутствие сообщения — сначала вопрос «эта сборка его писала?», и только потом «событие не случилось». Например, старые логи (сессия `b402c71f`) не содержат `Segment detector decision`, `Step counter reading` и `autoMode`.

Проверь и признаки порчи: `Activity log truncated: size limit reached`, `fields.droppedRecords`.

Готово, когда перепись есть по каждому архиву и отмечены сообщения, которых ждал, но нет.

### 3. Рамка: старт и стоп

- `Tracking session started` — пороги и настройки, **действовавшие во время записи**. Сравнивай цифры автопаузы с ними, а не с текущим `TrackProcessingConfig`: код с тех пор мог измениться.
- `Tracking session stopped` — сводка качества. Если данные плохие (правило в разделе «На что смотреть при расследовании» гайда), дальше иди в `SYSTEM`, а не в алгоритмы.
- В режиме «Авто» — `Activity mode decoded`: живые сегменты против декодированных.

Хронология без шума `DEBUG`, со временем от старта записи:

```bash
unzip -p "$Z" 'logs/*.jsonl' | jq -rs '
  def t: .timestampUtc | sub("\\.[0-9]+Z$"; "Z") | fromdate;
  (.[0] | t) as $t0 | .[]
  | select(.level != "DEBUG" and .level != "VERBOSE")
  | "\((t - $t0) / 60 | floor)m\((t - $t0) % 60)s  \(.message)  \(.fields | tostring)"'
```

### 4. Ветка по вопросу

Разделы гайда, в которые идти:

- **Трек, дистанция, скачки точек** — «Что пишется во время записи трека»: `provider`, `accuracyMeters`, `outlierReason`, `Location sample rejected before processing`, разрывы.
- **Автопауза** — `Automatic recording state changed` и `Auto pause held` рядом с порогами из шага 3, плюс `sampleIntervalMillis` против `stopWindowMillis`.
- **Сегменты / вид движения** — «Сегменты по виду движения» и `docs/auto-activity-segments.md`. `Activity recognized` рядом с `Segment detector decision`, шаги из `Step counter reading`.
- **Крэш, ошибки** — `WARN`/`ERROR`, `throwable`, тег `UncaughtExceptionHandler`, цепочка событий перед первой ошибкой.
- **Дыры и замирания** — `SYSTEM`: Doze, standby bucket, энергосбережение, выключенная геолокация.

### 5. Прогон через текущий код (трек, автопауза, сегменты)

Инструмент калибровки читает ZIP напрямую и прогоняет записанные сырые сэмплы через **текущий** pipeline:

```bash
./gradlew -q :tools:track-calibration:run --args="$PWD/korno-activity-34-c6b7f566.zip --no-sweep"
```

- Путь абсолютный: gradle запускает задачу из каталога модуля.
- Строка `на телефоне: … сейчас: …` — вердикты во время записи против текущего кода. Расхождение показывает, что код менялся после записи.
- В режиме «Авто» печатаются решения детектора и сегменты до и после декодера Витерби.
- Без `--no-sweep` добавляется перебор порогов, как его читать — `docs/track-calibration.md`.
- Нужны координаты — строки `Location sample coordinates` в логе, а не флаг в `diagnostics.json`. Без них остаётся только ручной разбор.

### 6. Вывод

Каждое утверждение опирается на запись из лога: время от старта, `message` и значения полей. Для каждой причины раздели:

- **Данные**: провайдер, погрешность, система задушила.
- **Алгоритм**: пороги, детектор, фильтр. Укажи, воспроизводится ли это на текущем коде (шаг 5).

Готово, когда на вопрос пользователя дан ответ с доказательствами, а всё, что проверить не удалось (сборка не писала сообщение, лог обрезан, нет координат), названо явно.

## Подводные камни jq

- Все значения в `fields` — **строки**. Числа сравнивай через `tonumber`, булевы как `"true"`.
- Отсутствующее значение записано строкой `"null"`, а не JSON-`null`. Фильтр пишется как `.fields.x != "null"`.
- `timestampUtc` — время записи в лог (UTC). `sampleTimestamp`, `readingTimestamp`, `signalTimestamp`, `boundaryTimestamp` — epoch ms самого события. `Location sample received` и `Location sample coordinates` связаны через `sampleTimestamp`.
- В архиве одной активности у всех строк один `activityId`, фильтровать по нему не нужно.
