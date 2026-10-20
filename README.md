# bcalm

A low-friction anxiety and trigger logger for spotting patterns in your day.

## Install

Download the latest build: **[bcalm-release.apk](https://github.com/dreamins/bcalm/releases/download/latest/bcalm-release.apk)** (signed; future builds install over it as updates and keep your data).

A debug build is also available: [bcalm.apk](https://github.com/dreamins/bcalm/releases/download/latest/bcalm.apk).

Open it on your Android phone. You will need to allow "install unknown apps" for your browser (Android will prompt you). Android 8.0+.

## Features

- **Tap to log.** Tap a number and the entry is saved at once with everything carried over from last time. An Undo bar appears, and for two minutes further changes on the Log screen update that entry instead of creating a new one.
- **Today summary.** Wake time, sleep quality and exercise sit in one collapsible card and reset at midnight.
- **Medication dose** is a number picked in the Today card (recent doses are one tap). It carries over day to day until you change it and is recorded with every entry. Name, unit and usual time are set once in Settings.
- **Night sweats, alcohol and cannabis** are yes/no answers in the Today card ("Night sweats", "Alcohol", "Cannabis"). Night sweats are answered in the morning; alcohol and cannabis are logged any time during the day and reset at midnight. The Chart tab then shows a small plain-count summary of night sweats against the previous evening's alcohol and cannabis, the previous day's anxiety and medication dose.
- **Joy (optional, 1-5).** A second scale for how much a positive moment landed (1 = barely registered, 5 = really landed), logged only after something good, usually with a cause. It lives on the open entry only (no carry-over) and is cleared when the edit window closes. The Chart tab compares anxiety and joy by medication dose, to tell "calm" (anxiety down, joy holds) from "flat" (anxiety down, joy drifts to 1-2).
- **Causes** (Work, Deadline, News/phone, No clear reason, ...) that you can add to or remove.
- **Reminders** every few hours (daytime only) that auto-dismiss after 30 minutes, with a "Same as last" action to log without opening the app.
- **Home-screen widget.** A row of 1-10 buttons logs an entry with carried-over values, without opening the app.
- **Insights chart.** Hourly average bars with single entries as dots, a "Since waking" view (hours after your wake time) with a matching note, a plain-language note on your hardest time of day (entries keep the local time where they were logged, even after travelling), and patterns by cause and factor (caffeine, food, exercise, partner, sleep) as differences from your average.
- **History** grouped by day, with notable details only and swipe-to-delete (with Undo).
- **Check for updates** in Settings compares your installed version with the latest release and links to the download. It only runs when you tap it.
- **CSV export and import** (restore history after reinstalling), and **auto-sync to Google Drive** via a file you pick once.

## Screenshots

| Log | Chart | History | Settings |
|---|---|---|---|
| <img src="docs/screenshots/log.png" width="220"> | <img src="docs/screenshots/chart.png" width="220"> | <img src="docs/screenshots/history.png" width="220"> | <img src="docs/screenshots/settings.png" width="220"> |

The screenshots use generated demo data.

## CSV columns

`timestamp, date, time, anxiety, caffeine, food_3h, exercise_today, partner, wake_time, sleep_quality, med_dose, med_time, causes, med_unit, med_name, night_sweats, alcohol, cannabis, utc_offset, joy`

`med_dose` is a number (e.g. `7.5`) with its unit in `med_unit` and the medication name (if set) in `med_name`. Booleans are `1`/`0` (empty if unset), `partner` is good/neutral/tense, and multiple causes are separated by `;`. `utc_offset` is the UTC offset in minutes where the entry was logged (empty in older files). `joy` is 1-5 (empty if not logged). `timestamp`, `date` and `time` are in that local time, so entries keep the time of day where they were logged after you travel.

## Privacy

All data stays on your device unless you export it or turn on sync to a file you chose (the Drive app does that upload, not this app). The only network call the app makes is the manual "Check for updates" in Settings.

## Build

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. GitHub Actions ("Build APK") builds every push and publishes the `latest` release from `main`.

To refresh the screenshots, run the **Screenshots** workflow from the Actions tab. It boots an emulator, seeds demo data, captures each tab and commits the images to `docs/screenshots/`.
