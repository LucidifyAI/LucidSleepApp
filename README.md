# LucidSleep™ Android prototype

A lean Android app for at-home sleep recording with the LucidPro patch. A sleeper sets up the kit once, then each evening checks the phone and patch, puts the patch on and starts recording. In the morning the app shows a short summary. Each night uploads to the same org-service the demo app uses.

Spec: *LucidSleep App — Workflow & Requirements v0.1* (Nov 2, 2026). This first build covers setup and the nightly loop. Reports, the doctor portal and server-side series tracking come next.

## What's in it

| Folder | What it does |
| --- | --- |
| `core/` | Copied from the demo app (`com.example.eeglabeler`) with only the package renamed: Bluetooth client, packet parser, contact monitor, encrypted local spool, upload engine, org-service client and Firebase auth interceptor. Includes the Patch 1A fixes (byte counter for the link watchdog; Firebase token kept off signed storage URLs). |
| `recording/` | `NightRecorder` records a night from the patch to the spool and on to org-service. `RecordingService` keeps it running in the foreground. `ResumeReceiver` picks a night back up after a reboot or app update. `NightLog` writes one plain-text log per night. |
| `enroll/` | Google sign-in, the kit (claimed with the kit card's one-time code, or a staff setup card), consent, phone check and the paired patch. |
| `series/` | Night tracking on the phone: 5 valid nights (at least 4 hours each), with a make-up night added for each short one. |
| `ui/` | The screens: Welcome and Google sign-in, Scan your kit card, Consent, Phone check, Find your patch, Home, Ready for tonight, Put on the patch, Recording, Good morning, My nights, Help. |

### How `NightRecorder` differs from the demo app's `AppController`

- Bluetooth bytes are framed, parsed and written on their own thread, with no display pacing and no wait on the main thread.
- Chunk encryption and registration run on a second thread.
- The link watchdog counts raw Bluetooth bytes. The reconnect counter resets only once data flows, so the scan and settle steps actually run for a half-connected patch.
- Any setup failure on the link (services, notifications, descriptor) tears the link down.
- Starting a night closes out any older night left open, so leftovers never slow uploads.
- Chunk files, chunk numbering, sample indexing, outage gaps and the upload protocol are the same as the demo app, so org-service and the viewers read these nights unchanged.

## Before the first build

1. **Gradle wrapper.** Copy it from the demo app. The bundle's commands do this.
2. **Firebase.** In the Firebase console, add an Android app with ID `com.lucidify.lucidsleep` to the same project the demo app uses. Download its `google-services.json` into `LucidSleepApp/app/`. The build fails without it.
3. **Versions.** `gradle/libs.versions.toml` pins plugin and library versions. If Android Studio reports a mismatch with the wrapper, align the versions there with the demo app's.

## Setting up a pilot sleeper

1. Sign in to Lucidify Cloud as an org admin (or the site's admin) and open **LucidSleep kits** (`sleep_kits.html`).
2. Choose the subject (or create one), then enter the kit label, the patch serial (optional: when it's given, the app pairs only with that patch) and the number of nights. Tap **Make kit code**.
3. Open the setup card, print it and put it in the kit with the patch, its charger and the nightly sticker pouches. The code is shown only once. If it's lost, use **New code** on the same kit.
4. The sleeper installs the app, signs in with any Google account and scans the card. That account is now linked to the kit: the kits page shows "Claimed by" with the account, and the code stops working.

You don't need the sleeper's Google account in advance. If you do know it, you can lock the code to it. Codes expire after 14 days unless they're used. A claimed kit can upload for 30 days (`SLEEP_KIT_ACCESS_DAYS` in org-service). **Revoke** stops the kit's uploads at once.

The sleeper's account can only make the app's recording calls, only for that subject, and only on nights it recorded itself. It doesn't join Lucidify Cloud. Its recordings are attributed to the admin who made the code.

**Staff-run kits.** A staff member who already has a Lucidify Cloud account can use a staff setup card instead: `kit_setup.html`, or

```
python tools/make_setup_code.py --org <orgId> --subject <subjectId> --label "Pilot 01" --serial <patch serial> --qr pilot01.png
```

Scan it on the same screen as the kit code, after signing in with the staff account.

## Known limits of this prototype

- **Direct Boot.** If the phone restarts overnight, recording resumes only after the sleeper unlocks it, because the spool's encryption keys aren't available before the first unlock.
- **Patch recording at return.** The patch's own SD recording isn't merged at kit return yet.
- **No heartbeat or diagnostics upload.** The server has no endpoint for either yet. Send diagnostics shares the night logs through the phone's share sheet, with a ticket code.
- **Series tracking is local.** It lives on the phone until org-service has series tables.
- **No reports in the app.** The night summary shows hours recorded and the upload percentage only.
- **Org-service address.** A setup code that names a different org-service address takes effect after the app restarts.
- **Thresholds are provisional.** The 4-hour valid-night threshold and the 50% patch battery minimum both need confirming.