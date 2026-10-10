# Labour Tracker

Offline-first Android app (Kotlin, Jetpack Compose, Material 3, Room) for daily labour tracking with KoboToolbox sync.

## Before you build (required, once)

Kobo token and admin PIN are no longer in the source code. Add them to `local.properties`
(project root; Android Studio already created it with `sdk.dir`; it is git-ignored):

```
KOBO_TOKEN=your-NEW-kobo-api-token
ADMIN_PIN=your-new-admin-pin
```

Without `KOBO_TOKEN` the app builds, but login and sync show "Kobo token is not set". Without `ADMIN_PIN`, admin login is off.
For GitHub Actions add repository secrets `KOBO_TOKEN` and `ADMIN_PIN`.

> The old token and PIN were visible in the public GitHub repo. Regenerate the token in KoboToolbox
> (Account settings > Security) and choose a new PIN. Keeping a token out of the repo does not hide it inside an APK.

## Build

Android Studio: open this folder, let Gradle sync, **Build > Build APK(s)**.
Command line: `./gradlew assembleDebug` then `./gradlew testDebugUnitTest`.
APK: `app/build/outputs/apk/debug/app-debug.apk`. To update an installed app keep the same signing key; otherwise uninstall first (this deletes local data, so send/sync first).

## Project status
Stored in the existing `projects.status` column (no database change, version stays 2).
- **Pending**: active project without today's update. **Ongoing**: today's update saved. **Completed**: set by the user.
- Complete via Project details > *Mark project as completed*, or the Daily Update completion switch / 100% on the newest update. *Reopen project* undoes it.
- Completed is never changed automatically. Back-dated and edited past days never change the status.
- On first start after upgrading, projects whose newest update was saved through the completion step stay Completed (one time).
- Status is local only; it is never sent to Kobo.

## Daily reminder
- 9:30 AM Asia/Dhaka, Sunday to Thursday. Friday and Saturday: no check, no notification.
- `AlarmManager` exact alarm (`setExactAndAllowWhileIdle`) when "Alarms & reminders" is allowed, otherwise `setAndAllowWhileIdle` (may be minutes late).
- Each run first schedules the next alarm, marks active projects without today's update Pending, and posts one notification (sound, lists DRR-Codes, once per day).
- Re-scheduled on app start, reboot, app update, clock/time-zone change and exact-alarm permission change.
- Home > Daily reminder shows notification / exact-alarm / battery state and has a test button.
- Only the logged-in block member's phone is checked; admin phones are not.
