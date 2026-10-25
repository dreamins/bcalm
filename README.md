# bcalm

A low-friction anxiety and trigger logger for spotting patterns in your day.

## Install

Download the latest build: **[bcalm-release.apk](https://github.com/dreamins/bcalm/releases/download/latest/bcalm-release.apk)** (signed; future builds install over it as updates and keep your data).

A debug build is also available: [bcalm.apk](https://github.com/dreamins/bcalm/releases/download/latest/bcalm.apk).

Open it on your Android phone. You will need to allow "install unknown apps" for your browser (Android will prompt you). Android 8.0+.

## Features

- **Tap to log.** Tap a number on the Log screen and the entry is saved at once, with everything carried over from last time. Any other change on the Log screen (caffeine, food, partner, causes, Today fields, joy) also logs an entry right away, with an Undo bar. If no anxiety number was tapped, the entry is saved without an anxiety level (it never copies the previous one).
- **Edit window.** For about two minutes after a log, further changes update that same entry instead of creating a new one. Only the open entry's values are shown as selected.
- **Today card.** Wake time, sleep quality, exercise, night sweats, alcohol and cannabis, with plain labels. The card resets at midnight.
- **Night sweats** are answered in the morning. **Alcohol** and **cannabis** are for the day you log them. The Chart tab compares night sweats with the evening before.
- **Medication dose** is picked on the Log screen (under Today) and carries over until you change it; it is recorded with every entry. Name, unit and usual time are set once in Settings.
- **Joy (optional, 1-5).** A second scale for how much a positive moment landed, from "Barely" to "Really landed". Log it only after something good. It does not carry over.
- **Joy on the Chart.** Shows the joy average and a table per medication dose of average anxiety and joy, to tell "calm" (anxiety down, joy holds) from "flat" (anxiety down, joy drifts low).
- **Causes** (Work, Deadline, News/phone, No clear reason, ...) that you can add to or remove in Settings.
- **Top causes on the Log screen.** The top 6, ranked by recent use (recent uses count more). Joy entries rank causes separately. "More causes (N)" opens the full ranked list with use counts.
- **Calm pop-up.** The moment you log 6 or more (for 30 minutes, with no extra taps) a bottom sheet "A calmer moment" opens: tap the causes it is about (the same top causes as the Log screen, updating the entry) and your own calm thought and one small step appear right below. "Breathe" and "Ground" open a short guided exercise, and "How is it now?" re-rates, shows e.g. "6 → 4" and is saved with the entry (`after`, plus which tools you used). Swipe it away and a quiet "A calmer moment ›" line under the Anxiety scale reopens it; "Not now" dismisses it.
- **Calm cards and support.** In Settings, tap a cause to write its calm thought and step, and optionally mark it to show support options. A Support section holds a crisis number (prefilled, editable), a second contact and a free-text step; they appear in the pop-up at 8 or above or for marked causes, and the numbers open the dialer (no call permission).
- **Reminders** every few hours (daytime only) that auto-dismiss after 30 minutes, with a "Same as last" action to log without opening the app.
- **Home-screen widget.** A row of 1-10 buttons logs an entry with carried-over values, without opening the app.
- **Insights chart.** Hourly average bars with single entries as dots, a "Since waking" view (hours after your wake time) with a matching note, a plain-language note on your hardest time of day (entries keep the local time where they were logged, even after travelling), and patterns by cause and factor (caffeine, food, exercise, partner, sleep) as differences from your average.
- **History** grouped by day, with notable details only and swipe-to-delete (with Undo).
- **Check for updates** in Settings, a manual button that compares your installed version with the latest release and links to the download. Nothing is checked unless you tap it.
- **Light and dark theme** follow the system setting, in a classic, neutral look.
- **CSV export and import** (restore history after reinstalling), and **auto-sync to Google Drive** via a file you pick once.

## Screenshots

| Log | Chart | History | Settings |
|---|---|---|---|
| <img src="docs/screenshots/log.png" width="220"> | <img src="docs/screenshots/chart.png" width="220"> | <img src="docs/screenshots/history.png" width="220"> | <img src="docs/screenshots/settings.png" width="220"> |

The screenshots use generated demo data.

## CSV columns

`timestamp,date,time,anxiety,caffeine,food_3h,exercise_today,partner,wake_time,sleep_quality,med_dose,med_time,causes,med_unit,med_name,night_sweats,alcohol,cannabis,utc_offset,joy,after,tools`

`med_dose` is a number (e.g. `7.5`) with its unit in `med_unit` and the medication name (if set) in `med_name`. Booleans are `1`/`0` (empty if unset), `partner` is good/neutral/tense, and multiple causes are separated by `;`. `utc_offset` is the UTC offset in minutes where the entry was logged (empty in older files). `joy` is 1-5 (empty if not logged). `after` is 1-10, the anxiety re-rated on the calm card (empty if not used), and `tools` lists what was used, `;`-separated: `card`, `breathe`, `ground`. `timestamp`, `date` and `time` are in that local time, so entries keep the time of day where they were logged after you travel.

Calm cards and support contacts are included as `#`-prefixed rows after the entries (`#card,<cause>,<thought>,<todo>,<heavy>` and `#support,<key>,<value>`, text percent-encoded). Import restores them where the same item is not already set on the device; older versions of the app skip these rows.

## Privacy

All data stays on your device unless you export it or turn on sync to a file you chose (the Drive app does that upload, not this app). The only network call the app makes is the manual "Check for updates" in Settings.

## Build

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. GitHub Actions ("Build APK") builds every push and publishes the `latest` release from `main`.

To refresh the screenshots, run the **Screenshots** workflow from the Actions tab. It boots an emulator, seeds demo data, captures each tab and commits the images to `docs/screenshots/`.
