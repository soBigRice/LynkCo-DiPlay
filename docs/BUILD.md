# Building DiPlay

Requirements: JDK 25, Android SDK 37, NDK 28.2.13676358 and the included Gradle wrapper.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Local release packaging

Provide an external asset directory using `DIPLAY_AUTH_ASSETS_DIR`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is set and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. The release APK deliberately contains the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit environment inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.

## Lynk public release

Use `:mobile:assembleLynkStandaloneRelease` with the same explicit authentication and
Android signing environment inputs above. Output:
`mobile/build/outputs/apk/lynkRelease/mobile-lynkRelease.apk`.

`lynkRelease` inherits the release build, disables debugging, keeps package
`com.shihab.diplay.lynk`, and shares the exact Lynk manifest/resources with `lynkDebug`.
Dependencies use their release variants. R1 is `0.2.12-lynk-osn2-r1`, versionCode 43,
minSdk 28. Optimization remains disabled, matching the existing release policy.
The standalone task rejects missing authentication or signing inputs. Without these
environment inputs, `assembleLynkRelease` is an unsigned, identity-free CI/source build
and must never be offered as the complete installer.

R1 deliberately retains the existing test25 signing certificate to allow an in-place
update that preserves settings. This certificate originated with the local Android
debug keystore; the APK itself is not debuggable. Do not generate a new keystore or
replace this signing identity for later updates. A future signing-key migration needs
a separate compatibility decision. Never upload the Android keystore or its passwords.

Validate the shared libraries and both Lynk variants before publication:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:testLynkDebugUnitTest :mobile:testLynkReleaseUnitTest \
  :mobile:lintLynkRelease :mobile:assembleLynkStandaloneRelease
```

Check the final APK's signature, package, code, minSdk, `debuggable=false`, Lynk resource
profile, runtime authentication files and SHA-256. Publish the APK and `SHA256SUMS.txt`
as GitHub Release assets, with the corresponding tagged source. Update the shared
website/app manifest only after the public asset has been downloaded and verified.
Release packaging does not establish new vehicle or wireless acceptance.

Build-type/source-set configuration follows the [Android build variant documentation](https://developer.android.com/build/build-variants)
(checked 2026-10-09 against AGP 9.3.0); distribution uses the existing
[GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
and Pages setup.

AGP 9.3.0 uses built-in Kotlin: shared `.kt` test directories must be registered on
`AndroidSourceSet.kotlin.directories`, even when their folder is named `java`.
Registering them only on `java.directories` produced `NO-SOURCE` rather than running
the R1 tests. CI requires nonzero JUnit results for `testLynkReleaseUnitTest` to detect
this failure. See the [official migration guidance](https://developer.android.com/build/migrate-to-built-in-kotlin#kotlin-source-sets)
(verified 2026-10-09).

## Standalone car-test APK

Use `:mobile:assembleStandaloneDebug` for a test APK that must connect to an iPhone:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

This task refuses missing or empty runtime inputs. `assembleDebug` remains an identity-free
source/CI build when the explicit asset input is absent; do not install that output as a
standalone car-test package. Before delivery, verify both `assets/offline-mfi/identity.pk8`
and `assets/offline-mfi/certificate.p7b` in the APK against the selected local inputs.
Update the existing test app without uninstalling it to preserve its settings.
