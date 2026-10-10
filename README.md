# Labour Tracker

Offline-first Android app (Kotlin, Jetpack Compose, Material 3, Room) for daily labour tracking with KoboToolbox sync.

## First run: Kobo setup, then login

No secrets are in the source code. On first start the app opens **Kobo setup**:
Server URL, Project / Asset UID, API token and an Admin PIN (4-8 digits). Use **Test connection**, then **Save**.
Then log in. Block members (email + 6/8-digit password) are created by the Admin: *Admin login > Block accounts*.
Kobo settings can be changed later from *Admin login > Kobo setup*. Each phone needs the setup once; values stay on that phone.

> The old token and PIN were visible in the public GitHub repo. Regenerate the token in KoboToolbox
> (Account settings > Security) and choose a new PIN. A token typed on a phone is stored in the app's private storage, not encrypted.

Optional build-time fallback: `KOBO_TOKEN=` and `ADMIN_PIN=` in `local.properties` (or GitHub secrets) pre-fill the setup.

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
