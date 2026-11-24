# Provenance and licences

## Upstream

| | |
|---|---|
| Project | Fossify Messages, https://github.com/FossifyOrg/Messages |
| Pinned revision | tag `1.10.3`, commit `60f3557be0cb5d8dfe472f2434a224236eb6620a` |
| Licence | GPL-3.0 (`LICENSE`, read at the pinned commit) |
| Import | Merged with its full history (git remote `upstream`), so later upstream releases can be merged in. |

**Obligation.** Any APK given to another person is a GPL-3.0 distribution. It must come with the licence and with an offer of the corresponding source, meaning this repository at the commit that built it. Sovereign SMS is therefore GPL-3.0 as a whole. The owner's private Sovereign-Ops code must not be copied into it unless it is meant to become GPL.

## Sovereign SMS changes

New code lives in `app/src/main/kotlin/com/sovereignops/sms/`. Edits to upstream files are marked `Sovereign SMS` in comments. To list every change against upstream:

```sh
git diff 60f3557be0cb5d8dfe472f2434a224236eb6620a --stat
```

## Dependencies

Versions are pinned in `gradle/libs.versions.toml`.

| Component | Version | Licence | Verified from | Scope |
|---|---|---|---|---|
| org.fossify:commons | 7.1.3 | GPL-3.0 | LICENSE in the FossifyOrg/Commons clone | runtime |
| org.fossify:mmslib | 1.0.1 | Apache-2.0 (file headers); SMIL DOM interfaces under the W3C Software Licence | source headers in the FossifyOrg/mmslib clone (no top-level LICENSE file) | runtime |
| org.fossify:IndicatorFastScroll | 4524cd0b61 | Apache-2.0 (upstream reddit/IndicatorFastScroll) | not re-read here, so **verify** | runtime |
| org.greenrobot:eventbus | 3.3.1 | Apache-2.0 | POM | runtime |
| com.googlecode.ez-vcard:ez-vcard | 0.12.2 | BSD-2-Clause ("FreeBSD License") | POM | runtime |
| AndroidX (Room 2.8.5, ConstraintLayout, SwipeRefreshLayout, Lifecycle, DocumentFile) | see toml | Apache-2.0 | AndroidX standard licence | runtime |
| kotlinx-serialization-json | 1.11.0 | Apache-2.0 | JetBrains | runtime |
| net.zetetic:sqlcipher-android | 4.20.0 | BSD-3-Clause (Zetetic LLC); bundles SQLite (public domain) and OpenSSL libcrypto (Apache-2.0, OpenSSL 3.x) | upstream `LICENSE.txt` in sqlcipher/sqlcipher-android; OpenSSL licence per its 3.x release | runtime (encrypted vault) |
| androidx.sqlite:sqlite | 2.7.0 | Apache-2.0 | AndroidX standard licence | runtime |
| androidx.biometric:biometric | 1.4.0-alpha02 | Apache-2.0 | AndroidX standard licence; same version Fossify Commons already resolved transitively, declared directly for Quick Cover re-authentication | runtime |
| junit:junit | 4.13.2 | EPL-1.0 | — | **test only** (not shipped) |

All runtime licences are compatible with GPL-3.0 distribution. BSD-3-Clause requires the SQLCipher copyright notice and licence to ship with binaries; the release notice is part of Slice F. Neither the test dependency nor the build tooling is distributed.

## Assets

The launcher icon (`res/drawable/ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml`) is an original vector made for Sovereign SMS. Other drawables come from upstream under GPL-3.0. Upstream store graphics and fastlane metadata in `graphics/` and `fastlane/` are Fossify branding. They must not be used for any Sovereign SMS listing.

## Evaluated and rejected

- **quik-sms/quik** (GPL-3.0): a heavier stack (Realm, Dagger, RxJava, multi-module, Gradle 8.2), and the repository ships an encrypted secrets bundle.
- **moezbhatti/qksms**: no commits since 2021.
