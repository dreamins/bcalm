# bcalm

A low-friction anxiety and trigger logger for spotting patterns in your day.

## Install

Download the latest debug build: [bcalm.apk](https://github.com/dreamins/bcalm/releases/download/latest/bcalm.apk)

Open it on your Android phone. You will need to allow "install unknown apps" for your browser (Android will prompt you). Android 8.0+.

## Features

- **One-tap save.** Every field carries over from your last entry, so logging is usually a single tap.
- **Daily fields reset at midnight:** wake time, sleep quality and exercise.
- **Medication is set once in Settings** (optional name, current dose, usual time) and recorded automatically with every entry.
- **Cause chips** (Work, Deadline, News/phone, No clear reason, ...) that you can add to or remove.
- **Reminders** every few hours (daytime only) that auto-dismiss after 30 minutes, with a "Same as last" action to log without opening the app.
- **Chart** of anxiety versus time of day, plus per-cause statistics.
- **History** with swipe-to-delete.
- **CSV export**, and **auto-sync to Google Drive** via a file you pick once.

## Screenshots

| Log | Chart | History | Settings |
|---|---|---|---|
| <img src="docs/screenshots/log.png" width="220"> | <img src="docs/screenshots/chart.png" width="220"> | <img src="docs/screenshots/history.png" width="220"> | <img src="docs/screenshots/settings.png" width="220"> |

The screenshots use generated demo data.

## CSV columns

`timestamp, date, time, anxiety, caffeine, food_3h, exercise_today, partner, wake_time, sleep_quality, med_dose, med_time, causes`

Booleans are `1`/`0` (empty if unset), `partner` is good/neutral/tense, and multiple causes are separated by `;`.

## Privacy

All data stays on your device unless you export it or turn on sync to a file you chose.

## Build

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. GitHub Actions ("Build APK") builds every push and publishes the `latest` release from `main`.

To refresh the screenshots, run the **Screenshots** workflow from the Actions tab. It boots an emulator, seeds demo data, captures each tab and commits the images to `docs/screenshots/`.
