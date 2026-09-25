#!/usr/bin/env python3
"""klog — разбор debug-логов Korno без ручных unzip и jq.

Принимает любую смесь архивов (korno-activity-*.zip, korno-diagnostics-*.zip), .jsonl
и каталогов и собирает из них один пул записей. Одна и та же запись лежит в нескольких
местах побайтно одинаковой строкой: INFO и выше записи активности дублируются в общий
поток, context/app.jsonl — выборка из общего потока, архив одной активности бывает
выгружен дважды. Поэтому пул склеивает записи по точной строке.

Имена файлов не источник правды: в имени архива стоит сессия выгрузки, а не записи,
а сами файлы переименовывают. Сессия и сборка берутся из записей.
"""

import argparse
import json
import re
import subprocess
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

HEADER = "Log file opened"
STARTED = "Tracking session started"
RECOVERED = "Active tracking session recovered"
STOPPED = "Tracking session stopped"
TRUNCATED = "Activity log truncated: size limit reached"
PROCESS_STARTED = "Application started"
PROCESS_EXIT = "Previous process exit"
DETAIL_LEVELS = {"DEBUG", "VERBOSE"}

# После сноса базы activityId начинаются заново, и один id означает разные записи.
# ponytail: порог по времени; если запись с ручной паузой будет молчать дольше, делить по сессиям записи
INSTANCE_GAP_SECONDS = 12 * 3600

ACTIVITY_ZIP = re.compile(r"korno-activity-(\d+)-(\w+)\.zip$")
APP_ZIP = re.compile(r"korno-diagnostics-(\w+)\.zip$")
ACTIVITY_ENTRY = re.compile(r"(?:^|/)activity-(\d+)\.jsonl$")


@dataclass(eq=False)
class Record:
    d: dict
    t: float
    activity: int | None
    kinds: set = field(default_factory=set)  # activity | app | context
    inputs: set = field(default_factory=set)  # индексы Pool.inputs

    @property
    def message(self) -> str:
        return self.d.get("message", "")

    @property
    def level(self) -> str:
        return self.d.get("level", "")

    @property
    def fields(self) -> dict:
        return self.d.get("fields") or {}


@dataclass
class Input:
    path: str
    export_session: str | None = None  # из diagnostics.json
    name_session: str | None = None  # из имени файла
    entries: dict = field(default_factory=dict)  # entry -> число строк
    has_activity_entry: bool = False
    bad_lines: int = 0
    unique: int = 0
    total: int = 0
    covered_by: set | None = None  # источники, в которых есть все записи этого


@dataclass
class Session:
    id: str
    first: float
    last: float
    build: str | None = None
    exits: list = field(default_factory=list)
    process_starts: int = 0
    app_stream: bool = False  # есть записи из общего потока этой сессии
    app_detail: bool = False  # в общем потоке лежат DEBUG-записи активностей
    warn: int = 0
    error: int = 0


@dataclass
class Activity:
    id: int
    records: list = field(default_factory=list)

    def first(self, *messages):
        return next((r for r in self.records if r.message in messages), None)

    def count(self, message):
        return sum(1 for r in self.records if r.message == message)

    @property
    def start(self) -> float:
        record = self.first(STARTED, RECOVERED)
        return record.t if record else self.records[0].t

    @property
    def end(self) -> float:
        stopped = [r for r in self.records if r.message == STOPPED]
        return stopped[-1].t if stopped else self.records[-1].t

    @property
    def label(self) -> str:
        return f"{self.id}@{utc(self.start):%m-%d}"

    @property
    def sessions(self) -> list:
        return sorted({r.d["sessionId"] for r in self.records if r.d.get("sessionId")})

    @property
    def completeness(self) -> str:
        if any("activity" in r.kinds for r in self.records):
            return "full"
        if any(r.level in DETAIL_LEVELS for r in self.records):
            return "full(app)"
        return "skeleton"


class Pool:
    def __init__(self):
        self.inputs: list[Input] = []
        self._by_key: dict = {}
        self.records: list[Record] = []
        self.sessions: dict[str, Session] = {}
        self.activities: list[Activity] = []

    def load(self, path: Path):
        source = Input(path=str(path))
        index = len(self.inputs)
        self.inputs.append(source)
        if match := ACTIVITY_ZIP.search(path.name) or APP_ZIP.search(path.name):
            source.name_session = match.groups()[-1]

        if path.suffix == ".zip":
            with zipfile.ZipFile(path) as archive:
                for entry in archive.namelist():
                    if entry == "diagnostics.json":
                        meta = json.loads(archive.read(entry))
                        source.export_session = meta.get("sessionId")
                    elif entry.endswith(".jsonl"):
                        text = archive.read(entry).decode("utf-8")
                        self._read(index, f"{path}:{entry}", entry, text.splitlines())
        else:
            self._read(index, str(path), path.name, path.read_text("utf-8").splitlines())

    def _read(self, index, where, entry, lines):
        source = self.inputs[index]
        if match := ACTIVITY_ENTRY.search(entry):
            kind, file_activity = "activity", int(match.group(1))
            source.has_activity_entry = True
        else:
            kind, file_activity = ("context" if entry.startswith("context/") else "app"), None

        count = 0
        previous_t = 0.0
        for number, line in enumerate(lines):
            line = line.strip()
            if not line:
                continue
            count += 1
            try:
                d = json.loads(line)
            except ValueError:
                source.bad_lines += 1
                continue
            timestamp = d.get("timestampUtc")
            # У маркера обрезки нет ни времени, ни activityId: он встаёт за предыдущей
            # строкой своего файла, а склеивать одинаковые маркеры разных файлов нельзя.
            t = parse_time(timestamp) if timestamp else previous_t
            previous_t = t
            key = line if timestamp else (where, number)
            record = self._by_key.get(key)
            if record is None:
                activity = d.get("activityId", file_activity)
                record = self._by_key[key] = Record(d, t, activity)
            record.kinds.add(kind)
            record.inputs.add(index)
        source.entries[entry] = count
        source.total += count

    def build(self):
        self.records = sorted(self._by_key.values(), key=lambda r: r.t)
        for record in self.records:
            if len(record.inputs) == 1:
                self.inputs[next(iter(record.inputs))].unique += 1
            for index in record.inputs:
                source = self.inputs[index]
                others = record.inputs - {index}
                source.covered_by = others if source.covered_by is None else source.covered_by & others
            self._track_session(record)
        self._split_activities()
        return self

    def _track_session(self, record):
        session_id = record.d.get("sessionId")
        if not session_id:
            return
        session = self.sessions.get(session_id)
        if session is None:
            session = self.sessions[session_id] = Session(session_id, record.t, record.t)
        session.first = min(session.first, record.t)
        session.last = max(session.last, record.t)
        if record.message == HEADER:
            dirty = "+dirty" if record.fields.get("gitDirty") == "true" else ""
            session.build = f"{record.fields.get('gitSha', '?')}{dirty}"
        elif record.message == PROCESS_EXIT:
            session.exits.append(record.fields.get("reason", "?"))
        elif record.message == PROCESS_STARTED:
            session.process_starts += 1
        if "app" in record.kinds:
            session.app_stream = True
            if record.activity is not None and record.level in DETAIL_LEVELS:
                session.app_detail = True
        if record.level == "WARN":
            session.warn += 1
        elif record.level == "ERROR":
            session.error += 1

    def _split_activities(self):
        latest: dict[int, Activity] = {}
        for record in self.records:
            if record.activity is None:
                continue
            current = latest.get(record.activity)
            restarted = (
                current is not None
                and record.message == STARTED
                and current.first(STARTED) is not None
            )
            if current is None or restarted or record.t - current.records[-1].t > INSTANCE_GAP_SECONDS:
                current = latest[record.activity] = Activity(record.activity)
                self.activities.append(current)
            current.records.append(record)
        self.activities.sort(key=lambda a: a.start)


def parse_time(timestamp: str) -> float:
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00")).timestamp()


def utc(t: float) -> datetime:
    return datetime.fromtimestamp(t, timezone.utc)


def span(first: float, last: float, date: bool = True) -> str:
    start, end = utc(first), utc(last)
    head = f"{start:%m-%d %H:%M}" if date else f"{start:%H:%M}"
    tail = f"{end:%H:%M}" if start.date() == end.date() else f"{end:%m-%d %H:%M}"
    return f"{head}–{tail}"


def expand(paths: list[str]) -> list[Path]:
    files = []
    for raw in paths:
        path = Path(raw)
        if path.is_dir():
            files += sorted(path.glob("korno-*.zip")) + sorted(path.glob("*.jsonl"))
        elif path.exists():
            files.append(path)
        else:
            sys.exit(f"klog: нет такого файла: {raw}")
    return files


def load_pool(paths: list[str]) -> Pool:
    pool = Pool()
    for path in expand(paths):
        pool.load(path)
    if not pool.inputs:
        sys.exit("klog: логов не найдено")
    return pool.build()


# --- index --------------------------------------------------------------------------


def print_index(pool: Pool):
    print(f"Источники ({len(pool.inputs)}):")
    for source in pool.inputs:
        kind = "активность" if source.has_activity_entry else "общий"
        parts = [f"  {source.path}  {kind}  {source.total} строк  уникальных {source.unique}"]
        if source.export_session and source.name_session and source.export_session != source.name_session:
            parts.append(f"выгружен в {source.export_session}, а в имени {source.name_session}")
        elif source.export_session:
            parts.append(f"выгружен в {source.export_session}")
        if source.covered_by:
            names = ", ".join(Path(pool.inputs[i].path).name for i in sorted(source.covered_by))
            parts.append(f"целиком внутри {names}")
        elif source.unique == 0:
            parts.append("всё есть в других источниках")
        if source.bad_lines:
            parts.append(f"битых строк {source.bad_lines}")
        print(" · ".join(parts))

    print(f"\nСессии приложения ({len(pool.sessions)}), время UTC:")
    for session in sorted(pool.sessions.values(), key=lambda s: s.first):
        stream = "—"
        if session.app_stream:
            stream = "с сэмплами" if session.app_detail else "каркас"
        activities = " ".join(a.label for a in pool.activities if session.id in a.sessions)
        parts = [
            f"  {session.id}  {span(session.first, session.last)}",
            f"сборка {session.build or '?'}",
            f"общий поток: {stream}",
        ]
        if session.process_starts:
            parts.append(f"запусков {session.process_starts}")
        if session.exits:
            parts.append(f"прошлый выход {','.join(session.exits)}")
        if session.warn or session.error:
            parts.append(f"warn {session.warn} error {session.error}")
        if activities:
            parts.append(f"активности {activities}")
        print(" · ".join(parts))

    print(f"\nАктивности ({len(pool.activities)}), время UTC:")
    builds = {s.id: s.build or "?" for s in pool.sessions.values()}
    for activity in pool.activities:
        print(" · ".join(activity_row(pool, activity, builds)))


def activity_row(pool, activity, builds):
    minutes = (activity.end - activity.start) / 60
    parts = [
        f"  {activity.label:<9} {span(activity.start, activity.end, date=False)} {minutes:4.0f}м",
        f"{activity.completeness:<9}",
        f"{len(activity.records)} строк",
    ]
    started = activity.first(STARTED)
    if started:
        mode = started.fields.get("activityType") or started.fields.get("segmentKind") or "?"
        if started.fields.get("autoMode") == "true":
            mode += " авто"
        parts.append(mode)
    samples = activity.count("Location sample received")
    coordinates = activity.count("Location sample coordinates")
    if samples or coordinates:
        parts.append(f"сэмплов {samples}, координат {coordinates}")

    flags = []
    if activity.first(STARTED, RECOVERED) is None:
        flags.append("начала нет")
    if activity.first(STOPPED) is None:
        flags.append("стопа нет")
    if recovered := activity.count(RECOVERED):
        flags.append(f"восстановлена {recovered}×")
    if activity.first(TRUNCATED):
        flags.append("обрезан")
    dropped = sum(int(r.fields.get("droppedRecords", 0)) for r in activity.records)
    if dropped:
        flags.append(f"потеряно записей {dropped}")
    warn = sum(1 for r in activity.records if r.level == "WARN")
    error = sum(1 for r in activity.records if r.level == "ERROR")
    if warn or error:
        flags.append(f"warn {warn} error {error}")
    if flags:
        parts.append(", ".join(flags))

    sessions = activity.sessions
    parts.append("сессия " + ",".join(f"{s} ({builds.get(s, '?')})" for s in sessions))
    kind = "activity" if activity.completeness == "full" else "app"
    sources = sorted({
        Path(pool.inputs[i].path).name
        for r in activity.records if kind in r.kinds
        for i in r.inputs
    })
    parts.append("из " + ", ".join(sources))
    return parts


# --- общее для команд по одной активности -------------------------------------------

LEVELS = ["VERBOSE", "DEBUG", "INFO", "WARN", "ERROR"]
# Окно событий приложения вокруг записи — то же, что у context/app.jsonl в архиве активности.
ENV_BEFORE_SECONDS = 600
ENV_AFTER_SECONDS = 60

NUMBER = re.compile(r"-?\d+(\.\d+)?(e-?\d+)?$", re.IGNORECASE)
FLOAT = re.compile(r"-?\d+\.\d+(e-?\d+)?$", re.IGNORECASE)
EXACT_FIELDS = {"latitude", "longitude", "altitudeMeters"}
STRING_FIELDS = {"gitSha"}


def select_activity(pool: Pool, spec: str) -> Activity:
    ident, _, _ = spec.partition("@")
    matches = [a for a in pool.activities if str(a.id) == ident and ("@" not in spec or a.label == spec)]
    if len(matches) == 1:
        return matches[0]
    if not matches:
        sys.exit(f"klog: активности {spec} нет. Есть: {' '.join(a.label for a in pool.activities)}")
    sys.exit(f"klog: {spec} неоднозначно — {' '.join(a.label for a in matches)}; укажи дату")


def scope(pool: Pool, activity: Activity) -> list[Record]:
    """Записи активности и записи приложения без activityId вокруг неё."""
    low = activity.records[0].t - ENV_BEFORE_SECONDS
    high = activity.records[-1].t + ENV_AFTER_SECONDS
    env = [r for r in pool.records if r.activity is None and low <= r.t <= high]
    return sorted(activity.records + env, key=lambda r: r.t)


def at(t: float, origin: float) -> str:
    seconds = round(t - origin)
    sign = "-" if seconds < 0 else ""
    minutes, rest = divmod(abs(seconds), 60)
    return f"{sign}{minutes}:{rest:02d}"


def parse_offset(text: str) -> float:
    """`6m`, `6m30s`, `390s`, `390`, `6:30`, со знаком минус — секунды от старта."""
    sign = -1 if text.startswith("-") else 1
    body = text.lstrip("-")
    if match := re.fullmatch(r"(\d+):(\d{1,2})", body):
        return sign * (int(match[1]) * 60 + int(match[2]))
    if match := re.fullmatch(r"(?:(\d+)m)?(?:(\d+)s?)?", body):
        if match[1] or match[2]:
            return sign * (int(match[1] or 0) * 60 + int(match[2] or 0))
    sys.exit(f"klog: не понимаю время {text!r}, пример: 6m30s, 6:30, 390")


def short(key: str, value: str) -> str:
    if key not in EXACT_FIELDS and FLOAT.match(value):
        text = f"{float(value):.2f}".rstrip("0").rstrip(".")
        return "0" if text == "-0" else text
    return value


def number(value) -> float | None:
    return float(value) if isinstance(value, str) and NUMBER.match(value) else None


def render(fields: dict, skip=()) -> str:
    return " ".join(f"{k}={short(k, v)}" for k, v in fields.items() if k not in skip)


INCOMING = ("Location sample received", "Location sample rejected before processing")


def incoming_providers(records) -> Counter:
    """Провайдеры всех пришедших сэмплов. Отброшенный до конвейера — в том числе сетевой фикс,
    `rejectedBecause=PROVIDER` — пишется только как rejected, без `Location sample received`."""
    return Counter(r.fields.get("provider") for r in records if r.message in INCOMING)


def builds_of(pool: Pool) -> dict:
    return {s.id: s.build or "?" for s in pool.sessions.values()}


# --- frame --------------------------------------------------------------------------

SNAPSHOTS = [
    "Permissions snapshot",
    "Location providers changed",
    "Power save mode changed",
    "Screen state changed",
    "Device idle mode changed",
    "Background restriction changed",
    "Battery state changed",
]
PROCESS_EVENTS = {
    PROCESS_STARTED,
    PROCESS_EXIT,
    RECOVERED,
    "Stale activity recognition subscription removed",
    "Unhandled exception",
}


def print_frame(pool: Pool, activity: Activity):
    records = scope(pool, activity)
    origin = activity.start
    own = activity.records

    def of(message):
        return [r for r in records if r.message == message]

    print(" · ".join(activity_row(pool, activity, builds_of(pool))).strip())

    for record in (r for r in records if r.message in PROCESS_EVENTS):
        where = "" if record.activity is not None else " [app]"
        print(f"Процесс {at(record.t, origin)}{where}: {record.message} "
              f"{render(record.fields, {'exitTimestamp', 'activityId'})}".rstrip())

    started = activity.first(STARTED)
    if started:
        print(f"Старт {at(started.t, origin)}: {render(started.fields, {'activityId'})}")

    snapshot, changes = [], []
    for message in SNAPSHOTS:
        found = [r for r in own if r.message == message]
        if not found:
            continue
        snapshot.append(render(found[0].fields))
        if len(found) > 1:
            changes.append(f"{message} ×{len(found) - 1}{value_counts(found[1:])}")
    if snapshot:
        print(f"Окружение на старте: {' '.join(snapshot)}")
    if changes:
        print(f"По ходу: {'; '.join(changes)}")

    policies = of("Location update policy changed")
    before = [r for r in policies if r.t <= origin]
    after = [r for r in policies if r.t > origin]
    requested = before[-1].fields if before else (after[0].fields if after else {})
    if policies:
        print(f"Запрос геолокации: {render(requested)}"
              + (f"; смен по ходу {len(after)}{value_counts(after)}" if after else ""))

    print_recognition(records, own, bool(started) and started.fields.get("autoMode") == "true")
    print_samples(own)

    heartbeats = [r for r in own if r.message == "Recording heartbeat"]
    if heartbeats:
        totals = {k: v for k, v in heartbeats[-1].fields.items() if k.endswith("Total")}
        silent = {
            key: sum(1 for r in heartbeats if r.fields.get(key) == "0")
            for key in ("locationSamples", "recognitionBroadcasts", "stepReadings")
        }
        quiet = ", ".join(f"{k} {n}" for k, n in silent.items() if n)
        print(f"Пульс ×{len(heartbeats)}: {render(totals)}"
              + (f"; минут с нулём: {quiet}" if quiet else ""))

    transitions = Counter(r.fields.get("to") for r in own if r.message == "Automatic recording state changed")
    manual = [r.message for r in own if r.message in ("Tracking paused manually", "Tracking resumed manually")]
    if transitions or manual:
        parts = [f"→{state} ×{n}" for state, n in transitions.items()]
        parts += [f"{m} ×{n}" for m, n in Counter(manual).items()]
        print(f"Автопауза: {', '.join(parts)}")

    confirmed = [r for r in own if r.message == "Segment detector decision" and r.fields.get("decision") == "CONFIRMED"]
    marks = [r.fields.get("kind") for r in own if r.message == "Ground truth mark"]
    decoded = activity.first("Activity mode decoded")
    if decoded or confirmed or marks:
        text = render(decoded.fields) if decoded else "Activity mode decoded нет"
        print(f"Сегменты: {text}; CONFIRMED ×{len(confirmed)}"
              + (f"; эталонных меток {len(marks)} ({','.join(marks)})" if marks else ""))

    stopped = [r for r in own if r.message == STOPPED]
    if stopped:
        print(f"Стоп {at(stopped[-1].t, origin)}: {render(stopped[-1].fields, {'activityId'})}")

    problems = Counter((r.level, r.message) for r in records if r.level in ("WARN", "ERROR"))
    if problems:
        print("WARN/ERROR: " + "; ".join(f"{level} {message} ×{n}" for (level, message), n in problems.most_common()))

    print(f"Данные: {data_verdict(own, stopped[-1] if stopped else None, requested)}")


def value_counts(records) -> str:
    """Для полей с парой-тройкой значений — сколько раз каждое: `deviceIdleMode true×5 false×5`."""
    parts = []
    for key in records[0].fields:
        counts = Counter(r.fields.get(key) for r in records)
        if 1 < len(counts) <= 3:
            parts.append(f"{key} " + " ".join(f"{v}×{n}" for v, n in counts.items()))
    return f" ({', '.join(parts)})" if parts else ""


def print_recognition(records, own, auto_mode: bool):
    subscription = [r for r in records if r.message == "Activity recognition subscription"]
    broadcasts = [r for r in own if r.message == "Activity recognition broadcast"]
    recognized = [r for r in own if r.message == "Activity recognized"]
    silent = sum(1 for r in own if r.message == "Activity recognition silent")
    # Отписка приходит после стопа; более ранняя в окне — от предыдущей записи.
    removed = [r for r in records if r.message == "Activity recognition updates removed" and r.t > own[0].t]
    if not (subscription or broadcasts or recognized or silent or removed):
        if auto_mode:
            print("Распознавание: записей нет — сборка не писала или подписки не было")
        return
    parts = []
    if subscription:
        parts.append(f"подписка {subscription[0].fields.get('status')}")
    if broadcasts:
        empty = sum(1 for r in broadcasts if r.fields.get("hasResult") == "false")
        parts.append(f"broadcast ×{len(broadcasts)}, без результата {empty}")
    kinds = Counter(r.fields.get("detectedActivity") for r in recognized)
    parts.append(f"вердиктов ×{len(recognized)} ({' '.join(f'{k}×{n}' for k, n in kinds.most_common())})")
    if silent:
        parts.append(f"silent ×{silent}")
    for record in removed:
        parts.append(f"отписка {render(record.fields)}")
    print(f"Распознавание: {'; '.join(parts)}")


def print_samples(own):
    samples = [r for r in own if r.message == "Location sample received"]
    if not samples:
        print("Сэмплы: в логе нет (skeleton или старая сборка)")
        return
    providers = incoming_providers(own)
    rejected = Counter(r.fields.get("rejectedBecause") for r in own
                       if r.message == "Location sample rejected before processing")
    outliers = Counter(r.fields.get("outlierReason") for r in own
                       if r.message == "Track point processed" and r.fields.get("isOutlier") == "true")
    coordinates = sum(1 for r in own if r.message == "Location sample coordinates")
    parts = [f"×{len(samples)}", "провайдер (с отброшенными) " + " ".join(f"{k}×{n}" for k, n in providers.most_common()),
             f"координат {coordinates}"]
    if rejected:
        parts.append("отброшено до конвейера " + " ".join(f"{k}×{n}" for k, n in rejected.items()))
    if outliers:
        parts.append("выбросы " + " ".join(f"{k}×{n}" for k, n in outliers.items()))
    print(f"Сэмплы: {', '.join(parts)}")


def data_verdict(own, stopped, requested) -> str:
    """Правило гайда «На что смотреть при расследовании»: плохие данные — не вопрос к алгоритмам."""
    if stopped is None:
        return "стопа нет — сводки качества нет"
    fields = stopped.fields
    reasons = []
    accuracy = number(fields.get("accuracyMedianMeters"))
    if accuracy is not None and accuracy > 20:
        reasons.append(f"accuracyMedianMeters={short('', fields['accuracyMedianMeters'])} > 20")
    interval, wanted = number(fields.get("sampleIntervalMedianMillis")), number(requested.get("intervalMillis"))
    if interval is not None and wanted and interval > 1.5 * wanted:
        reasons.append(f"sampleIntervalMedianMillis={interval:.0f} при запрошенных {wanted:.0f}")
    share = number(fields.get("unmeasuredTimeShare"))
    if share is not None and share > 0.2:
        reasons.append(f"unmeasuredTimeShare={share:.2f} > 0.2")
    providers = incoming_providers(own)
    if providers and providers["network"] > sum(providers.values()) / 2:
        reasons.append(f"network у {providers['network']} из {sum(providers.values())} сэмплов")
    if not reasons:
        return "в норме по правилу гайда"
    return "плохие — " + "; ".join(reasons) + " → смотреть SYSTEM, а не алгоритмы"


# --- timeline -----------------------------------------------------------------------

# Сообщение → (поля, по которым склеиваются подряд идущие строки; поля, которые показываются).
# Остальные сообщения показываются со всеми полями и склеиваются только полными дублями.
PROJECTIONS = {
    "Activity recognized": (["detectedActivity"], ["confidencePercent"]),
    "Activity recognition broadcast": (["hasResult"], ["resultAgeMillis"]),
    "Automatic recording state changed": (
        ["from", "to"],
        ["currentSpeed", "reportedSpeed", "filterSpeed", "accuracyMeters", "sampleIntervalMillis",
         "provider", "windowPointCount", "dopplerOverridesStop"],
    ),
    "Auto pause held": ([], ["currentSpeed", "reportedSpeed", "accuracyMeters", "sampleIntervalMillis"]),
    "Location sample received": (
        [], ["provider", "accuracyMeters", "reportedSpeed", "sampleIntervalMillis", "ageMillis", "status"]),
    "Location sample coordinates": ([], ["latitude", "longitude"]),
    "Location sample rejected before processing": (["rejectedBecause"], ["provider", "accuracyMeters", "ageMillis"]),
    "Track point processed": ([], ["isOutlier", "outlierReason", "isStop", "currentSpeed", "distanceMeters"]),
    "Recording heartbeat": (
        [], ["locationSamples", "recognitionBroadcasts", "recognitionResults", "stepReadings", "deviceIdleMode"]),
    "Step counter reading": ([], ["stepCounter"]),
    "Tracking point buffer flushed": ([], ["pointCount"]),
    "Segment detector decision": (
        ["decision", "kind", "detectedActivity", "from", "to"], ["confidencePercent", "reason", "manual"]),
    STARTED: ([], ["segmentKind", "activityType", "autoMode", "autoPauseEnabled", "autoPauseSensitivity",
                   "gpsAccuracy"]),
}


@dataclass
class Row:
    key: tuple
    record: Record
    first: float
    last: float
    count: int = 1
    values: dict = field(default_factory=dict)


def timeline_rows(records, full: bool) -> list[Row]:
    rows: list[Row] = []
    for record in records:
        keys, shown = PROJECTIONS.get(record.message, (None, None))
        if full or keys is None:
            keys = shown = list(record.fields)
        key = (record.message, record.level, record.activity is None,
               tuple(record.fields.get(k) for k in keys))
        if not full and rows and rows[-1].key == key and not record.d.get("throwable"):
            row = rows[-1]
            row.last = record.t
            row.count += 1
        else:
            row = Row(key, record, record.t, record.t)
            rows.append(row)
        for name in dict.fromkeys(keys + shown):
            if name in record.fields:
                row.values.setdefault(name, []).append(record.fields[name])
    return rows


def summarize(key: str, values: list) -> str:
    distinct = list(dict.fromkeys(values))
    if len(distinct) == 1:
        return short(key, distinct[0])
    numbers = [number(v) for v in values]
    if None not in numbers:
        return f"{short(key, str(min(numbers)))}–{short(key, str(max(numbers)))}"
    return "/".join(short(key, v) for v in distinct[:3]) + ("/…" if len(distinct) > 3 else "")


def print_timeline(pool, activity, low, high, level, grep, skip, full, limit):
    origin = activity.start
    minimum = LEVELS.index(level)
    pattern = re.compile(grep, re.IGNORECASE) if grep else None
    excluded = re.compile(skip, re.IGNORECASE) if skip else None
    records = [
        r for r in scope(pool, activity)
        if (low is None or r.t - origin >= low)
        and (high is None or r.t - origin <= high)
        and LEVELS.index(r.level if r.level in LEVELS else "INFO") >= minimum
        and (pattern is None or pattern.search(f"{r.message} {render(r.fields)}"))
        and (excluded is None or not excluded.search(r.message))
    ]
    rows = timeline_rows(records, full)
    print(f"{activity.label}, время от старта {utc(origin):%H:%M:%S} UTC; "
          f"[app] — событие приложения вне записи; уровень {level}+")
    for row in rows[:limit]:
        record = row.record
        when = at(row.first, origin) + (f"–{at(row.last, origin)}" if row.count > 1 else "")
        mark = "" if record.level in ("INFO", "DEBUG", "VERBOSE") else f"{record.level} "
        where = "[app] " if record.activity is None else ""
        fields = " ".join(f"{k}={summarize(k, v)}" for k, v in row.values.items())
        repeat = f" ×{row.count}" if row.count > 1 else ""
        print(f"{when:>11}  {where}{mark}{record.message}{repeat}  {fields}".rstrip())
        if throwable := record.d.get("throwable"):
            lines = throwable.splitlines()
            for text in lines if full else lines[:4]:
                print(f"{'':13}{text}")
    if len(rows) > limit:
        print(f"… ещё {len(rows) - limit} строк: сузь --from/--to, --grep или подними --limit")


# --- stats --------------------------------------------------------------------------

SEGMENT = re.compile(r"(\w+)@(\d+:\d{2})-(\d+:\d{2})")
EPOCH_SECONDS = 5  # эпоха декодера вида движения


def percentile(values, share):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * share))]


def stat_windows(activity: Activity, by: str | None, ranges: str | None) -> list[tuple]:
    """Окна (подпись, от, до) в секундах от старта."""
    duration = activity.end - activity.start
    if ranges:
        windows = []
        for part in ranges.split(","):
            spec, _, label = part.strip().partition("=")
            low, _, high = spec.partition("-")
            windows.append((label or spec, parse_offset(low), parse_offset(high)))
        return windows
    if by in ("segments", "live"):
        decoded = activity.first("Activity mode decoded")
        text = decoded.fields.get("decodedSegments" if by == "segments" else "liveSegments", "") if decoded else ""
        windows = [(kind, parse_offset(low), parse_offset(high)) for kind, low, high in SEGMENT.findall(text)]
        if not windows:
            sys.exit(f"klog: сегментов {by} в логе нет (нужна строка Activity mode decoded)")
        return windows
    if by == "marks":
        marks = [r for r in activity.records if r.message == "Ground truth mark"]
        if not marks:
            sys.exit("klog: эталонных меток (Ground truth mark) в логе нет")
        starts = [int(m.fields["markTimestamp"]) / 1000 - activity.start for m in marks]
        ends = starts[1:] + [duration]
        return [(m.fields.get("kind", "?"), low, high) for m, low, high in zip(marks, starts, ends)]
    step = parse_offset(by or "5m")
    if step <= 0:
        sys.exit("klog: шаг --by должен быть больше нуля")
    count = int(duration // step) + 1
    return [(at(activity.start + i * step, activity.start), i * step, (i + 1) * step) for i in range(count)]


def recognition_spans(activity: Activity) -> list[tuple]:
    """Каждый вердикт держится до следующего; (вид, от, до) в секундах от старта."""
    verdicts = [r for r in activity.records if r.message == "Activity recognized"]
    ends = [r.t for r in verdicts[1:]] + [activity.end]
    return [(r.fields.get("detectedActivity"), r.t - activity.start, end - activity.start)
            for r, end in zip(verdicts, ends)]


def window_stats(activity: Activity, low: float, high: float, spans, over) -> dict:
    origin = activity.start
    inside = [r for r in activity.records if low <= r.t - origin < high]

    def values(message, key, scale=1.0):
        return [float(v) * scale for r in inside if r.message == message
                for v in [r.fields.get(key)] if number(v) is not None]

    samples = [r for r in inside if r.message == "Location sample received"]
    speeds = values("Location sample received", "reportedSpeed", 3.6)
    stats = {
        "n": len(samples),
        "v": speeds,
        "track": values("Track point processed", "currentSpeed", 3.6),
        "acc": values("Location sample received", "accuracyMeters"),
        "dt": values("Location sample received", "sampleIntervalMillis"),
        "paused": sum(1 for r in samples if r.fields.get("status") == "AUTO_PAUSE"),
        "incoming": incoming_providers(inside),
        "outliers": sum(1 for r in inside if r.message == "Track point processed" and r.fields.get("isOutlier") == "true"),
    }

    readings = [
        (float(r.fields["readingTimestamp"]) / 1000 if number(r.fields.get("readingTimestamp")) else r.t,
         float(r.fields["stepCounter"]))
        for r in inside if r.message == "Step counter reading" and number(r.fields.get("stepCounter")) is not None
    ]
    if len(readings) >= 2 and readings[-1][0] - readings[0][0] >= 10:
        stats["steps"] = (readings[-1][1] - readings[0][1]) / (readings[-1][0] - readings[0][0]) * 60

    dwell = Counter()
    for kind, start, end in spans:
        overlap = min(end, high) - max(start, low)
        if overlap > 0:
            dwell[kind] += overlap
    stats["recognition"] = dwell

    if over is not None:
        epochs: dict[int, list] = {}
        for r in samples:
            speed = number(r.fields.get("reportedSpeed"))
            if speed is not None:
                epochs.setdefault(int((r.t - origin) // EPOCH_SECONDS), []).append(speed * 3.6)
        medians = [percentile(v, 0.5) for v in epochs.values()]
        stats["epochs"] = (sum(1 for m in medians if m > over), len(medians))
    return stats


def print_stats(activity: Activity, by, ranges, over):
    spans = recognition_spans(activity)
    windows = stat_windows(activity, by, ranges)
    windows.append(("всё", 0, activity.end - activity.start + 1))
    over_title = f"  эп>{over:g}" if over is not None else ""
    print(f"{activity.label}: скорости в км/ч (v — reportedSpeed провайдера, трек — currentSpeed после обработки), "
          f"acc в м, dt в мс; пауза — доля сэмплов, net — доля сетевых среди всех пришедших, с отброшенными; распознавание — доля времени окна под вердиктом")
    print(f"{'окно':<14}{'мин':>5}{'сэмпл':>6}  {'v p50/p90/max':<15}{'трек50':>7}  {'acc 50/90':<10}"
          f"{'dt50':>6}{'пауза':>6}{'net':>5}{'выбр':>5}{'шаг/м':>6}{over_title}  распознавание")
    for label, low, high in windows:
        s = window_stats(activity, low, high, spans, over)
        length = max(min(high, activity.end - activity.start) - low, 1)

        def triple(values):
            if not values:
                return "—"
            return "/".join(f"{percentile(values, p):.0f}" for p in (0.5, 0.9)) + f"/{max(values):.0f}"

        def median(values, fmt="{:.0f}"):
            return fmt.format(percentile(values, 0.5)) if values else "—"

        share = (lambda part, total: f"{part * 100 / total:.0f}%" if total else "—")
        incoming = s["incoming"]
        accuracy = "/".join(f"{percentile(s['acc'], p):.0f}" for p in (0.5, 0.9)) if s["acc"] else "—"
        steps = f"{s['steps']:.0f}" if "steps" in s else "—"
        epochs = ""
        if over is not None:
            hit, total = s["epochs"]
            epochs = f"{(f'{hit * 100 / total:.0f}%' if total else '—'):>7}"
        recognition = " ".join(f"{kind} {seconds * 100 / length:.0f}%"
                               for kind, seconds in s["recognition"].most_common(3))
        name = label if len(label) <= 13 else label[:12] + "…"
        print(f"{name:<14}{length / 60:>5.1f}{s['n']:>6}  {triple(s['v']):<15}{median(s['track']):>7}  "
              f"{accuracy:<10}{median(s['dt']):>6}{share(s['paused'], s['n']):>6}{share(incoming['network'], sum(incoming.values())):>5}"
              f"{s['outliers']:>5}{steps:>6}{epochs}  {recognition or '—'}")


# --- samples ------------------------------------------------------------------------

STATUS_SHORT = {"RECORDING": "rec", "AUTO_PAUSE": "auto", "PAUSED": "paused", "WAITING_FOR_GPS": "wait"}


def sample_rows(activity: Activity) -> list[dict]:
    """Сэмпл со всем, что о нём записано: координаты, вердикт конвейера, решение автопаузы.

    Связь — `sampleTimestamp`; у `Track point processed` его нет, и он пишется сразу за своим
    сэмплом, поэтому достаётся последнему сэмплу без вердикта. Отброшенный до конвейера
    сэмпл `Location sample received` не получает и идёт отдельной строкой.
    """
    rows, by_timestamp, pending = [], {}, None
    for record in activity.records:
        message, fields = record.message, record.fields
        stamp = fields.get("sampleTimestamp")
        if message in ("Location sample received", "Location sample rejected before processing"):
            row = {"t": record.t, "sample": fields, "rejected": fields.get("rejectedBecause")}
            rows.append(row)
            if row["rejected"]:
                # DUPLICATE несёт sampleTimestamp уже принятого сэмпла: координаты и решение — его.
                by_timestamp.setdefault(stamp, row)
                pending = None
            else:
                by_timestamp[stamp] = row
                pending = row
        elif message == "Track point processed" and pending is not None:
            pending["processed"] = fields
            pending = None
        elif message == "Location sample coordinates" and stamp in by_timestamp:
            by_timestamp[stamp]["coordinates"] = fields
        elif message == "Automatic recording state changed" and stamp in by_timestamp:
            by_timestamp[stamp]["decision"] = f"→{fields.get('to')}"
        elif message == "Auto pause held" and stamp in by_timestamp:
            by_timestamp[stamp]["decision"] = "held"
    return rows


def print_samples_table(activity: Activity, low, high, coordinates: bool, limit: int):
    origin = activity.start
    rows = [r for r in sample_rows(activity)
            if (low is None or r["t"] - origin >= low) and (high is None or r["t"] - origin <= high)]
    if not rows:
        print("Сэмплов в окне нет" + ("" if activity.count("Location sample received") else
                                      " — в логе их нет вовсе (skeleton или старая сборка)"))
        return
    print(f"{activity.label}: скорости в м/с (пороги автопаузы тоже в м/с), acc и dist в м, dt и age в мс; "
          f"статус — на момент прихода сэмпла; решение — автопауза по этому сэмплу")
    header = f"{'время':>7} {'dt':>5} {'prov':<7}{'acc':>6}{'age':>6}{'vRep':>6}{'vTrk':>6} {'статус':<7}{'выброс':<9}{'stop':<5}{'dist':>8}  решение"
    print(header + ("  lat lon" if coordinates else ""))
    for row in rows[:limit]:
        sample, processed = row["sample"], row.get("processed", {})

        def cell(fields, key, fmt="{:.1f}"):
            value = number(fields.get(key))
            return fmt.format(value) if value is not None else "—"

        if row["rejected"]:
            verdict, stop, distance = f"REJ:{row['rejected']}", "", ""
        elif processed:
            verdict = processed.get("outlierReason") or ("да" if processed.get("isOutlier") == "true" else "")
            stop = "stop" if processed.get("isStop") == "true" else ""
            distance = cell(processed, "distanceMeters", "{:.0f}")
        else:
            verdict, stop, distance = "нет вердикта", "", ""
        status = STATUS_SHORT.get(sample.get("status"), sample.get("status") or "")
        line = (f"{at(row['t'], origin):>7} {cell(sample, 'sampleIntervalMillis', '{:.0f}'):>5} "
                f"{sample.get('provider', '?'):<7}{cell(sample, 'accuracyMeters'):>6}{cell(sample, 'ageMillis', '{:.0f}'):>6}"
                f"{cell(sample, 'reportedSpeed'):>6}{cell(processed, 'currentSpeed'):>6} {status:<7}{verdict:<9}"
                f"{stop:<5}{distance:>8}  {row.get('decision', '')}")
        if coordinates and "coordinates" in row:
            line += f"  {row['coordinates'].get('latitude')} {row['coordinates'].get('longitude')}"
        print(line.rstrip())
    if len(rows) > limit:
        print(f"… ещё {len(rows) - limit} сэмплов: сузь --from/--to или подними --limit")


# --- jq -----------------------------------------------------------------------------


def typed(key: str, value: str):
    if value == "null":
        return None
    if value in ("true", "false"):
        return value == "true"
    if key not in STRING_FIELDS and NUMBER.match(value):
        return int(value) if re.fullmatch(r"-?\d+", value) else float(value)
    return value


def normalized(record: Record, origin: float | None) -> dict:
    d = dict(record.d)
    d["fields"] = {k: typed(k, v) for k, v in record.fields.items()}
    d["epoch"] = round(record.t, 3)
    d["src"] = sorted(record.kinds)
    if origin is not None:
        d["t"] = round(record.t - origin, 3)
        d["at"] = at(record.t, origin)
    return d


def run_jq(pool, activity, jq_args) -> int:
    records = scope(pool, activity) if activity else pool.records
    origin = activity.start if activity else None
    stream = "\n".join(json.dumps(normalized(r, origin), ensure_ascii=False) for r in records)
    try:
        return subprocess.run(["jq", *jq_args], input=stream, text=True).returncode
    except FileNotFoundError:
        sys.exit("klog: jq не установлен")


# --- CLI ----------------------------------------------------------------------------

JQ_HELP = """\
Записи подаются в jq потоком, по одной, отсортированными по времени и без дублей.
Отличия от сырого лога:
  .fields  — типизированы: числа — числа, "true"/"false" — булевы, "null" — null
  .t, .at  — секунды и м:сс от старта активности (только с -a)
  .epoch   — секунды UTC
  .src     — откуда запись: activity / app / context
С -a в поток попадают и события приложения без activityId в окне −10 мин … +1 мин.

  klog jq -a 45 -- -r 'select(.level == "WARN") | "\\(.at) \\(.message)"'
  klog jq -a 45 -- -s '[.[] | select(.message == "Location sample received") | .fields.accuracyMeters] | add / length'
"""


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    jq_args = []
    if "--" in argv:
        split = argv.index("--")
        argv, jq_args = argv[:split], argv[split + 1:]

    parser = argparse.ArgumentParser(prog="klog", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.epilog = "Без путей читаются korno-*.zip и *.jsonl из текущего каталога и diagnostics/."
    commands = parser.add_subparsers(dest="command", required=True)

    index = commands.add_parser("index", help="что есть в логах: источники, сессии, активности")
    index.add_argument("paths", nargs="*")

    frame = commands.add_parser("frame", help="рамка активности: настройки, окружение, итоги, качество данных")
    frame.add_argument("-a", "--activity", required=True, help="id или id@мм-дд из index")
    frame.add_argument("paths", nargs="*")

    timeline = commands.add_parser(
        "timeline", help="хронология активности: время от старта, повторы склеены, тяжёлые поля урезаны")
    timeline.add_argument("-a", "--activity", required=True, help="id или id@мм-дд из index")
    timeline.add_argument("--from", dest="low", type=parse_offset, help="от: 6m, 6m30s, 6:30; до старта — --from=-2m")
    timeline.add_argument("--to", dest="high", type=parse_offset, help="до, в том же формате")
    timeline.add_argument("--level", choices=LEVELS, type=str.upper,
                          help="минимальный уровень; по умолчанию INFO, с --grep — VERBOSE")
    timeline.add_argument("--grep", help="регулярка по сообщению и полям")
    timeline.add_argument("--skip", help="регулярка по сообщению: такие строки не показывать")
    timeline.add_argument("--full", action="store_true", help="все поля, без склейки повторов")
    timeline.add_argument("--limit", type=int, default=300, help="максимум строк вывода (300)")
    timeline.add_argument("paths", nargs="*")

    stats = commands.add_parser("stats", help="агрегаты по окнам: скорость, точность, шаг опроса, шаги, распознавание")
    stats.add_argument("-a", "--activity", required=True, help="id или id@мм-дд из index")
    stats.add_argument("--by", help="шаг окна (1m, 30s; по умолчанию 5m) или segments / live / marks — "
                                    "по декодированным, живым сегментам или эталонным меткам")
    stats.add_argument("--ranges", help="свои окна: 0-1:50=пешком,1:50-17:55=автобус")
    stats.add_argument("--over", type=float, help="доля 5-секундных эпох с медианой v выше порога, км/ч")
    stats.add_argument("paths", nargs="*")

    samples = commands.add_parser("samples", help="таблица сэмплов: вход, вердикт конвейера, решение автопаузы")
    samples.add_argument("-a", "--activity", required=True, help="id или id@мм-дд из index")
    samples.add_argument("--from", dest="low", type=parse_offset, help="от: 6m, 6m30s, 6:30")
    samples.add_argument("--to", dest="high", type=parse_offset, help="до, в том же формате")
    samples.add_argument("--coords", action="store_true", help="добавить широту и долготу")
    samples.add_argument("--limit", type=int, default=300, help="максимум строк (300)")
    samples.add_argument("paths", nargs="*")

    jq = commands.add_parser("jq", help="свой jq-фильтр по нормализованным записям; аргументы jq — после --",
                             description=JQ_HELP, formatter_class=argparse.RawDescriptionHelpFormatter)
    jq.add_argument("-a", "--activity", help="id или id@мм-дд; без него — весь пул")
    jq.add_argument("paths", nargs="*")

    args = parser.parse_args(argv)
    if jq_args and args.command != "jq":
        parser.error("аргументы после -- принимает только jq")
    paths = args.paths or [p for p in (".", "diagnostics") if Path(p).is_dir()]
    pool = load_pool(paths)

    if args.command == "index":
        print_index(pool)
    elif args.command == "frame":
        print_frame(pool, select_activity(pool, args.activity))
    elif args.command == "timeline":
        level = args.level or ("VERBOSE" if args.grep else "INFO")
        print_timeline(pool, select_activity(pool, args.activity), args.low, args.high,
                       level, args.grep, args.skip, args.full, args.limit)
    elif args.command == "stats":
        print_stats(select_activity(pool, args.activity), args.by, args.ranges, args.over)
    elif args.command == "samples":
        print_samples_table(select_activity(pool, args.activity), args.low, args.high, args.coords, args.limit)
    elif args.command == "jq":
        if not jq_args:
            parser.error("фильтр jq передаётся после --: klog jq -a 45 -- '.message'")
        activity = select_activity(pool, args.activity) if args.activity else None
        sys.exit(run_jq(pool, activity, jq_args))


if __name__ == "__main__":
    main()
