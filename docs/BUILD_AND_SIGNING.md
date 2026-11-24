# Build and signing

This covers the toolchain, how to build, the owner's production signing workflow, reproducibility checks and how to verify an update before installing it. **Agents never generate, read or handle the production keystore.** Agent sessions sign only with a throwaway key, and a throwaway-signed APK is never a release.

## 1. Toolchain

- Gradle 9.8.2 (wrapper), AGP 9.4.1, Kotlin 2.4.20, compile and target SDK 36, min SDK 26, JDK 17 or newer.
- SDK packages: `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`. Installing them means accepting Google's Android SDK licence.
- Dependencies come from Google Maven, Maven Central and **jitpack.io** (the Fossify libraries). The build can't run offline unless the Gradle cache is already populated.
- Use the `foss` flavour. `gplay` exists upstream only for Fossify's store build.
- `local.properties` holds `sdk.dir=…`. It is not tracked.

If Maven Central rate-limits you (HTTP 429), add a machine-local mirror in `~/.gradle/init.d/` (not the repository):

```groovy
allprojects { repositories { maven { url "https://maven-central.storage-download.googleapis.com/maven2/" } } }
```

and set `systemProp.org.gradle.internal.repository.max.retries=8` in `~/.gradle/gradle.properties`.

## 2. Build and test

```sh
./gradlew testFossDebugUnitTest lintFossDebug assembleFossDebug
# debug APK:   app/build/outputs/apk/foss/debug/sovereign-sms-1-foss-debug.apk   (package com.sovereignops.sms.debug)
./gradlew assembleFossRelease
# release APK: app/build/outputs/apk/foss/release/sovereign-sms-1-foss-release.apk (package com.sovereignops.sms)
```

Without signing variables the release APK is **unsigned** and Gradle prints "No signing config found". An unsigned APK can't be installed.

The version is in `gradle.properties` (`VERSION_NAME`, `VERSION_CODE`). Raise `VERSION_CODE` for every build you will install over an earlier one: Android refuses an update with the same or a lower version code.

## 3. Production signing (owner only)

### 3.1 The identity

One permanent key signs every release of `com.sovereignops.sms`, forever. Android installs an update only if it is signed by the same certificate as the installed app. If the key is lost, the app can never be updated: it can only be uninstalled, which deletes its vault, and reinstalled. Restore your data from a `.sovbak` afterwards (`BACKUP_RESTORE.md`).

The certificate's subject name is readable by anyone who has the APK (`apksigner verify --print-certs`). Use a neutral name such as `CN=Sovereign SMS`. Don't put a legal name, address, email or organisation in it.

### 3.2 Generate the keystore

Do this on your own computer, in a terminal you control. Don't do it in an AI session, a cloud container, or anywhere inside the repository folder.

```sh
mkdir -p ~/sovereign-signing && chmod 700 ~/sovereign-signing
keytool -genkeypair -v \
  -keystore ~/sovereign-signing/sovereign-sms-release.jks -storetype PKCS12 \
  -alias sovereign-sms -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Sovereign SMS"
chmod 600 ~/sovereign-signing/sovereign-sms-release.jks
```

`keytool` asks for the password interactively, so it never reaches the command line or shell history. Use a long random password and keep it in your password manager. A PKCS12 keystore uses the same password for the store and the key.

### 3.3 Record and check the fingerprint

```sh
keytool -list -v -keystore ~/sovereign-signing/sovereign-sms-release.jks -alias sovereign-sms | grep 'SHA256:'
```

Write the SHA-256 fingerprint (64 hex digits) into `docs/release/SIGNING_CERT_SHA256`, then commit it. The fingerprint is public: it's in every APK you sign. It is not secret, and it is what `scripts/verify-apk.sh` pins every release against.

### 3.4 Back it up independently, encrypted

Make at least two encrypted copies on separate media, for example an offline USB drive and a second location you trust. Use a standard tool, for example:

```sh
gpg --symmetric --cipher-algo AES256 --output sovereign-sms-release.jks.gpg ~/sovereign-signing/sovereign-sms-release.jks
# or: age -p -o sovereign-sms-release.jks.age ~/sovereign-signing/sovereign-sms-release.jks
```

- Store the backup passphrase separately from the backup itself (password manager, plus a written copy kept somewhere safe).
- **Test a restore** once: decrypt a copy to a temporary folder, run the `keytool -list -v` command from §3.3 on it, check the fingerprint matches, then delete the temporary copy.
- Never put the keystore, its password or its backups in Git, cloud notes, chat, email, issue trackers, screenshots or AI context.

### 3.5 Build a signed release

Pass the secrets through the environment for one command, read without echo, so they don't reach shell history or files:

```sh
read -rsp 'Keystore password: ' SIGNING_STORE_PASSWORD; echo
export SIGNING_STORE_PASSWORD SIGNING_KEY_PASSWORD="$SIGNING_STORE_PASSWORD" \
       SIGNING_KEY_ALIAS=sovereign-sms SIGNING_STORE_FILE="$HOME/sovereign-signing/sovereign-sms-release.jks"
./gradlew --no-daemon clean assembleFossRelease
unset SIGNING_STORE_PASSWORD SIGNING_KEY_PASSWORD SIGNING_KEY_ALIAS SIGNING_STORE_FILE
```

`--no-daemon` stops a background Gradle process from keeping the variables after the build. A gitignored `keystore.properties` also works (see `keystore.properties_sample`), but it leaves the password in a plain file, so prefer the environment. `.gitignore` refuses `*.jks`, `*.keystore`, `*.p12`, `*.pk8`, `*.pem`, `keystore.properties` and `signing.env`.

The release is signed with APK Signature Scheme v2 (v1 isn't needed at min SDK 26). Key rotation via v3 lineage is possible later with `apksigner rotate`, but nothing here relies on it.

### 3.6 Verify before installing

```sh
scripts/verify-apk.sh app/build/outputs/apk/foss/release/sovereign-sms-1-foss-release.apk
```

It checks, and prints PASS or FAIL for each:

- the signature (`apksigner verify`);
- exactly one signer;
- the certificate against `docs/release/SIGNING_CERT_SHA256`. It prints NOT_RUN while that file has no fingerprint, and you can pass `--cert` instead;
- the package id is `com.sovereignops.sms`;
- the APK isn't debuggable;
- there's no INTERNET permission;
- `zipalign -P 16`;
- every native library's ELF LOAD alignment is 16 KB.

It exits non-zero on any FAIL. It reads only the APK.

**Upgrade compatibility.** Before installing over an existing copy, pull the installed APK and compare:

```sh
adb shell pm path com.sovereignops.sms           # prints package:/data/app/…/base.apk
adb pull /data/app/…/base.apk installed.apk
scripts/verify-apk.sh new.apk --previous installed.apk
```

This checks the package and signing certificate are the same and the version code increases. If the certificates differ, Android refuses the update with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Don't uninstall to get round it until you've made a `.sovbak`.

## 4. Release artifacts and checksums

From a clean, committed tree:

```sh
scripts/release-artifacts.sh path/to/signed.apk          # commit defaults to HEAD
```

This writes `dist/<version>/`, which is ignored by Git:

- `sovereign-sms-<version>.apk`;
- `sovereign-sms-<version>-source.tar.gz`: `git archive` of the commit, the GPL-3.0 corresponding source, tracked files only;
- `NOTICES.txt` and `licenses/`: the notices that ship in the APK;
- `RELEASE_NOTES.md` and `LICENSE`;
- `SHA256SUMS`, headed with the version and full commit SHA.

The script doesn't sign, tag, push or publish. The checksums are versioned by the folder name and the header line. Publish them alongside the APK so others can check it.

## 5. Reproducibility

- Versions are pinned:
  - the Gradle wrapper;
  - AGP and Kotlin;
  - every dependency, in `gradle/libs.versions.toml`;
  - build-tools 36.0.0.
- `dependenciesInfo.includeInApk = false`, so there's no Google-encrypted dependency blob.
- AGP writes `META-INF/version-control-info.textproto` into the APK, holding the git commit SHA of `HEAD`. Each APK therefore names its commit, and its hash changes with every commit even when no code changed. The file has no field for uncommitted changes (*inference:* a dirty-tree build would still name `HEAD`), so **build releases only from a clean, committed tree** (`git status --porcelain` empty). `release-artifacts.sh` refuses a dirty tree, but the build itself doesn't.
- The source archive is `git archive` piped through `gzip -n`, with no timestamps, so it's stable for a given commit.

**Check that a release was built from a given commit.** Two methods:

- **Byte-identical rebuild.** Rebuild the commit with the same JDK and build-tools, sign it with the same key, and compare the SHA-256 of the two APKs. RSA v2 signatures are deterministic, so identical inputs give identical files.
- **Content comparison, without the key.** Unzip both APKs and compare every entry: `diff <(cd a && find . -type f -exec sha256sum {} + | sort -k2) <(cd b && find . -type f -exec sha256sum {} + | sort -k2)`. The signature lives in the APK signing block, not in an entry. `version-control-info.textproto` should name the commit you rebuilt.

What was checked in this repository and how is recorded in the release notes, under Verification.

## 6. CI

Upstream's GitHub Actions workflows were removed. They called FossifyOrg's reusable workflows and release secrets, and ran on schedules that would spend this repository's Actions minutes. Build and tests run locally (§2). Any CI added later follows the owner's CI budget rule: gated by `CI_ENABLED` and triggered only on `main`.
