# Korno

## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues (`Yansubaev/Korno`) via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Инструментальные тесты

Запускать только через управляемый Gradle эмулятор:

```
./gradlew kornoEmulatorDebugAndroidTest          # все модули
./gradlew :core:database:kornoEmulatorDebugAndroidTest
```

**`connectedAndroidTest` не запускать.** Она ставит и следом удаляет APK на каждом
устройстве из `adb devices`, а удаление приложения уносит `korno.db` вместе с записями
пользователя. К машине бывает подключён рабочий телефон — в том числе по Wi-Fi ADB, где
его легко не заметить. Однажды так и потеряли всю историю активностей.

Эмулятор описан в `build-logic/.../KornoBuildConfig.kt` (`KORNO_MANAGED_DEVICE`): Gradle
поднимает его сам и на подключённое железо не смотрит вовсе.
