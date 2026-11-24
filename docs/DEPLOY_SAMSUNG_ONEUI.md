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

From the repository root, on a computer with platform-tools:

```sh
adb devices                       # phone listed as "device", not "unauthorized" (accept the prompt on the phone)
adb install -r app/build/outputs/apk/foss/debug/sovereign-sms-1-foss-debug.apk
adb shell pm list packages | grep com.sovereignops.sms.debug
```

The `-r` flag keeps data when you update an existing install. The debug build is `com.sovereignops.sms.debug`, and it installs next to your current messenger.

To remove it, run `adb uninstall com.sovereignops.sms.debug`. This deletes Sovereign settings and rules, so export a pack first.

## 3. Restricted settings

Android limits some permissions for apps that were sideloaded and not installed from a store. If a permission or a role request is greyed out, open App info for Sovereign SMS → ⋮ (top right) → **Allow restricted settings** and confirm, then retry. The item appears only after Android has blocked something once.

## 4. Keep notifications on time (battery)

Samsung can put apps to sleep, which delays or drops SMS notifications.

- App info → Sovereign SMS → Battery → **Unrestricted**.
- Settings → Battery → **Background usage limits** → make sure Sovereign SMS is not in **Sleeping apps** or **Deep sleeping apps**. You can also add it to **Never sleeping apps**.

## 5. Notification channels

Sovereign SMS uses separate channels: Personal, Professional, Unknown senders, Priority, One-time codes, Quiet, Held. Sound, vibration and lock-screen display are set per channel in App info → Notifications. Professional and Unknown start with **content hidden on the lock screen**.

## 6. Switch, then test

Only after §0 is done: Settings → Apps → **Default apps** → SMS app → Sovereign SMS. Then check:

1. Send one message from a number you control, and confirm it arrives and notifies.
2. Mark that conversation Professional (⋮ → Category), turn on quiet hours, and confirm a message posts silently.
3. Switch the top bar to Focus, and confirm the inbox shows only priority conversations.

Write down which step you actually did on the phone. Until then, each one is untested.
