# Install and roll back

Until a step here has actually been carried out on your phone, treat it as untested.

## 1. Side-by-side install (safe and reversible)

The debug build is `com.sovereignops.sms.debug`. It installs next to your current messenger and becomes **nothing** by default.

```sh
adb install -r app/build/outputs/apk/foss/debug/sovereign-sms-1-foss-debug.apk
```

Or copy the APK to the phone and open it (you'll need to allow installs from your file manager).

At this point it can't read or receive messages, because only the default SMS app can write messages, and Android shows it limited access. Use this stage to look around the settings and the rule preview.

## 2. Before making it the default SMS app

This is an owner decision. Agents must not do it.

1. **Back up your messages** from your current app. Fossify/Sovereign Settings → Export messages works once it is the default. Also keep your current messenger's own backup.
2. **RCS.** *Inference, to verify on your phone and carrier.* Sovereign SMS has no RCS. While RCS chat stays switched on in Google Messages or Samsung Messages, other RCS users may keep sending to that app and not to Sovereign SMS, and your number stays registered for RCS. If you plan to use Sovereign SMS as your main app, turn off RCS chats in the current app first. Give deregistration a little time before you rely on SMS fallback.
3. **System block list.** Numbers on Android's own block list may be dropped by the system before any app sees them. If you want screened messages preserved, use Sovereign's screening rather than the system block list (to be verified on a device).
4. Keep your current messenger installed as the fallback.

## 3. Switch

Settings → Apps → Default apps → SMS app → Sovereign SMS.

Then test by receiving one message from a person or number you control.

## 4. Roll back

1. Settings → Apps → Default apps → SMS app → choose your previous messenger. Messages live in the system provider, so they stay where they are and your old app sees them.
2. Optional: export your Sovereign rules (Settings → Sovereign SMS → Export pack), then uninstall with `adb uninstall com.sovereignops.sms.debug`. Uninstalling removes only Sovereign settings, rules and per-conversation flags. Conversations Sovereign archived stay archived in the provider. Unarchive them first if you want them back in the inbox of an app that hides archived threads.
3. Turn RCS back on in your previous app if you switched it off.
