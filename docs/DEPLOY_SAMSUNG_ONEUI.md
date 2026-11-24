# Deploy on a Samsung phone (One UI)

Status: **written from documentation and general Android behaviour; not yet carried out on a device.** Menu names move between One UI versions. If a path below doesn't match your phone, use the settings search for the bold term. Pair this file with `INSTALL_AND_ROLLBACK.md`, which covers backup and rollback.

## 0. Mandatory pre-flight: turn off RCS before you switch the default

Sovereign SMS has no RCS. If RCS chats stay on in Google Messages (or Samsung Messages), other RCS users can keep delivering to that app, and your number stays registered for RCS.

1. Google Messages → profile picture → Messages settings → **RCS chats** → turn it off. (Samsung Messages: Settings → **Chat settings** → turn off.)
2. Wait before relying on plain SMS. Deregistration is not instant (*inference; timing varies by carrier*). After the wait, ask someone who uses RCS to send you a message. Switch only once that message arrives as SMS.
3. Keep that app installed. It is your fallback (see `INSTALL_AND_ROLLBACK.md` §4).

Do not switch the default SMS app until this step is done.

## 1. Let a sideloaded APK and USB install through

**Auto Blocker** (One UI 6 and later) blocks app installs from outside the stores, and it can also block commands sent over USB.

- Settings → Security and privacy → **Auto Blocker** → turn it off for the install. Turn it back on afterwards if you want it. While it is on, a debug APK cannot be installed.
- For ADB: Settings → About phone → Software information → tap **Build number** 7 times. Then Settings → **Developer options** → **USB debugging** on.

## 2. Install over ADB

From the repository root, on a computer with platform-tools. There are two builds:

| Build | Package | Use |
|---|---|---|
| Release (R8-minified, not debuggable) | `com.sovereignops.sms` | Daily use, once it is signed with **your own** key |
| Debug | `com.sovereignops.sms.debug` | Looking around side by side; debuggable |

```sh
adb devices                       # phone listed as "device", not "unauthorized" (accept the prompt on the phone)
adb install -r app/build/outputs/apk/foss/release/sovereign-sms-1-foss-release.apk
adb shell pm list packages | grep com.sovereignops.sms
```

The `-r` flag keeps data when you update an existing install. Both builds install next to your current messenger.

**Signing.** Android installs an update only when it is signed with the same key as the installed app. The release APK built in the agent session was signed with a throwaway test key that has already been discarded. Treat that APK as evidence that the release build works, not as the install you keep. Build the one you keep with your own keystore:

```sh
SIGNING_KEY_ALIAS=… SIGNING_KEY_PASSWORD=… SIGNING_STORE_FILE=/path/outside/the/repo.jks SIGNING_STORE_PASSWORD=… \
  ./gradlew assembleFossRelease
/path/to/build-tools/apksigner verify --print-certs app/build/outputs/apk/foss/release/sovereign-sms-1-foss-release.apk
```

Keep that keystore and its passwords outside the repository and back them up. If you lose them, you can never update the app; you can only uninstall it (which deletes Sovereign data) and reinstall. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the keys differ.

**Developer verification** (Android's new installer check) doesn't apply to ADB installs ("Apps installed using ADB won't require verification", developer.android.com/developer-verification). See `ANDROID17_ONEUI_AUDIT.md`.

**Advanced Protection** (Settings → Security and privacy → Advanced Protection, where offered) lists "Blocked app sideloading". The docs don't say whether this also blocks `adb install`. If the install is refused, turn the mode off for the install and back on afterwards (*device test*).

To remove the app, run `adb uninstall com.sovereignops.sms` (or `.debug`). This deletes Sovereign settings, rules and the vault. Make a `.sovbak` backup first (Settings → Sovereign SMS → Encrypted backup (.sovbak)).

## 3. Restricted settings

Android limits some permissions for apps that were sideloaded and not installed from a store. If a permission or a role request is greyed out, open App info for Sovereign SMS → ⋮ (top right) → **Allow restricted settings** and confirm, then retry. The item appears only after Android has blocked something once.

## 4. Keep notifications on time (battery)

Samsung can put apps to sleep, which delays or drops SMS notifications.

- App info → Sovereign SMS → Battery → **Unrestricted**.
- Settings → Battery → **Background usage limits** → make sure Sovereign SMS is not in **Sleeping apps** or **Deep sleeping apps**. You can also add it to **Never sleeping apps**.

## 5. Notification channels

Sovereign SMS uses separate channels: Personal, Priority / Regular, Professional, Unknown senders, One-time codes, Quarantine, Quiet, Held. Sound, vibration and lock-screen display are set per channel in App info → Notifications. Professional and Unknown start with **content hidden on the lock screen**.

## 6. Switch, then test

Only after §0 is done: Settings → Apps → **Default apps** → SMS app → Sovereign SMS. Then check:

1. Send one message from a number you control, and confirm it arrives and notifies.
2. Mark that conversation Professional (⋮ → Category), turn on quiet hours, and confirm a message posts silently.
3. Switch the top bar to Focus, and confirm the inbox shows only priority conversations.

Write down which step you actually did on the phone. Until then, each one is untested.

## 7. Thirty-second rollback

If anything goes wrong (messages missing, no notifications, sending fails):

1. Settings → Apps → **Default apps** → SMS app → choose your previous messenger. This takes effect at once. Messages live in Android's own message store, so the previous app sees everything, including messages that arrived while Sovereign SMS was the default.
2. Turn RCS chats back on in that app if you turned them off in §0.

Nothing else is needed. Sovereign SMS can stay installed and do nothing, or be uninstalled later (§2). Conversations it archived stay archived in the system store; unarchive them in Sovereign first if your other app hides archived threads.
