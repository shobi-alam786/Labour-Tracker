# Labour Tracker

Offline-first Android app (Kotlin, Jetpack Compose, Material 3, Room) for daily labour tracking with KoboToolbox sync.

## Three Kobo forms (kobo-forms/ folder)

| Form | File | Holds |
|---|---|---|
| Register Project | `1-Register-Project.xlsx` | DRR-code, block, activity, sub-block, start date, end date |
| Daily Updates | `2-Daily-Updates.xlsx` | every update **plus the project details** (activity, sub-block, start/end date), so this one table reads on its own |
| Account Access | `3-Account-Access.xlsx` | block, email, password |

In KoboToolbox: *New > Upload an XLSForm* for each file, then **Deploy**. The UID of each form is in its URL
(`.../#/forms/<UID>/summary`).

## First run: Kobo setup, then login

No secrets are in the source code. On first start the app opens **Kobo setup**:
Server URL, the three form UIDs, API token and an Admin PIN (4-8 digits). Use **Test connection**, then **Save**.
Then log in. Block members (email + 6/8-digit password) are created by the Admin: *Admin login > Block accounts*, then sent to Kobo.
When a form UID changes, everything on the phone is marked "not sent yet" and goes once to the new forms.
Settings can be changed later from *Admin login > Kobo setup*. Each phone needs the setup once; values stay on that phone.

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

## Updating phones without Google Play (GitHub)
1. Upload the project files to the GitHub repo (replace the old ones).
2. Repo > **Releases > Draft a new release**, tag `v1.2` (new tag), **Publish**. The *Build APK* workflow runs and attaches `LabourTracker-v1.2.apk` to the release. (Or *Actions > Build APK > Run workflow* and download the `apk` artifact.)
3. On the phone: open the release page, download the APK, tap it, allow "install unknown apps". It installs over the old app **only if signed with the same key**.

Same key on every build (one time): run `keytool -genkeypair -v -keystore labour.jks -alias labour -keyalg RSA -keysize 2048 -validity 10000`, convert to text
(Linux/Mac `base64 -w0 labour.jks`, Windows PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("labour.jks"))`) and add repo secrets
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (labour), `KEY_PASSWORD`. Keep `labour.jks` safe; never commit it.

## In-app update (no Google Play)
The app checks the newest GitHub Release of `shobi-alam786/Labour-Tracker` (repo must be public) at start (at most every 12 hours) and from
*Kobo sync > App update*. If a newer version exists the Home screen shows "Update x.y available"; tapping it downloads the APK and opens
Android's installer (the user allows "Install unknown apps" once and taps Install).
To publish an update: create a Release with a **higher tag** than the last one (`v1.2`, `v1.3`, ...). The workflow builds it, names the version
from the tag and attaches the APK. Use the same signing key every time (see above), otherwise Android refuses to install over the old app.

## QR code for the API key
First-run screen shows a live camera ("Scan configuration QR code") with **Enter API key manually** below it (like ODK Collect). The QR may contain just the API key, or JSON:
`{"token":"...","server":"https://kf.kobotoolbox.org","project_uid":"..","update_uid":"..","account_uid":".."}` (server and UIDs optional).
Create the QR **offline** (the key is a secret; do not use online QR websites). Example: `pip install qrcode[pil]`, then `python -c "import qrcode; qrcode.make('YOUR_KEY').save('key.png')"`.
Uses CameraX for the picture and ML Kit for reading (the reading model comes from Google Play services; the first scan may need internet once). Needs the camera permission.
