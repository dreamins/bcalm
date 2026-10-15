# bcalm

A low-friction anxiety and trigger logger for spotting patterns in your day.

## Install

Download the latest debug build: [bcalm.apk](https://github.com/dreamins/bcalm/releases/download/latest/bcalm.apk)

Open it on your Android phone. You will need to allow "install unknown apps" for your browser (Android will prompt you). Android 8.0+.

## Install via Google Play (private)

1. Open Google AI Studio, go to Build, and choose Import from GitHub.
2. Enter `dreamins/bcalm` and import the repository.
3. Send it to Google Play, or install it on your device from there.

Availability at launch is limited to US users aged 18+. The Play version is a separate app (id `app.bcalm`) from the sideloaded APK, so it starts empty. To move your data, use Settings -> Export CSV in the old app, import that CSV in the new one, then uninstall the old one.

Each build on `main` also publishes `bcalm.aab` (signed bundle) and `bcalm-release.apk` to the release, for manual upload in the Play Console.

## Features

- **Tap to log.** Tap a number and the entry is saved at once with everything carried over from last time. An Undo bar appears, and for two minutes further changes on the Log screen update that entry instead of creating a new one.
- **Today summary.** Wake time, sleep quality and exercise sit in one collapsible card and reset at midnight.
- **Medication dose** is a number picked in the Today card (recent doses are one tap). It carries over day to day until you change it and is recorded with every entry. Name, unit and usual time are set once in Settings.
- **Causes** (Work, Deadline, News/phone, No clear reason, ...) that you can add to or remove.
- **Reminders** every few hours (daytime only) that auto-dismiss after 30 minutes, with a "Same as last" action to log without opening the app.
- **Home-screen widget.** A row of 1-10 buttons logs an entry with carried-over values, without opening the app.
- **Insights chart.** Hourly average bars with single entries as dots, a plain-language note on your hardest time of day, and patterns by cause and factor (caffeine, food, exercise, partner, sleep) as differences from your average.
- **History** grouped by day, with notable details only and swipe-to-delete (with Undo).
- **CSV export and import** (restore history after reinstalling), and **auto-sync to Google Drive** via a file you pick once.

## Screenshots

| Log | Chart | History | Settings |
|---|---|---|---|
| <img src="docs/screenshots/log.png" width="220"> | <img src="docs/screenshots/chart.png" width="220"> | <img src="docs/screenshots/history.png" width="220"> | <img src="docs/screenshots/settings.png" width="220"> |

The screenshots use generated demo data.

## CSV columns

`timestamp, date, time, anxiety, caffeine, food_3h, exercise_today, partner, wake_time, sleep_quality, med_dose, med_time, causes, med_unit, med_name`

`med_dose` is a number (e.g. `7.5`) with its unit in `med_unit` and the medication name (if set) in `med_name`. Booleans are `1`/`0` (empty if unset), `partner` is good/neutral/tense, and multiple causes are separated by `;`.

## Privacy

All data stays on your device unless you export it or turn on sync to a file you chose.

## Build

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. GitHub Actions ("Build APK") builds every push and publishes the `latest` release from `main`.

To refresh the screenshots, run the **Screenshots** workflow from the Actions tab. It boots an emulator, seeds demo data, captures each tab and commits the images to `docs/screenshots/`.
