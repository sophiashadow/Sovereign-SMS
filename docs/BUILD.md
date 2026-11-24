# Build

```sh
./gradlew testFossDebugUnitTest assembleFossDebug
```

- Toolchain: Gradle 9.8.2 (wrapper), AGP 9.4.1, Kotlin 2.4.20, compile/target SDK 36, min SDK 26, JDK 17 or newer.
- SDK packages: `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`. Installing them means accepting Google's Android SDK licence.
- Dependencies come from Google Maven, Maven Central and **jitpack.io** (the Fossify libraries). The build can't run offline unless the Gradle cache is already populated.
- The `foss` flavour is the one to use. `gplay` exists upstream only for Fossify's store build.

If Maven Central rate-limits you (HTTP 429), the build environment used here added a machine-local mirror. Put this in `~/.gradle/init.d/` (not the repository):

```groovy
allprojects { repositories { maven { url "https://maven-central.storage-download.googleapis.com/maven2/" } } }
```

Also set `systemProp.org.gradle.internal.repository.max.retries=8` in `~/.gradle/gradle.properties`.

The release build (`assembleFossRelease`) needs `keystore.properties`, which is not in the repository. See `keystore.properties_sample`. Never commit a keystore.

## CI

Upstream's GitHub Actions workflows were removed. They called FossifyOrg's reusable workflows and release secrets, and they ran on schedules that would spend this repository's Actions minutes. The build and tests run locally (see the commands above). Any CI added later should follow the owner's CI budget rule: gated by `CI_ENABLED` and triggered only on `main`.
