# Releasing

Releases are signed APKs attached to GitHub releases, built and published by hand.

## One-time setup

**Signing key.** Every release must be signed with the same key: Android only installs an
update signed like the installed version, so a lost key means users can't update without
uninstalling (and losing rides not yet synced).

- Create it with Android Studio (Build → Generate Signed App Bundle or APK → Create new), or
  `keytool -genkeypair`. Keep the `.jks` outside the repository, with a backup elsewhere.
- Use the same password for the keystore and the key: the PKCS12 format doesn't support a
  separate key password ("Cannot recover key" otherwise). Don't let the IDE remember it.

**`keystore.properties`** at the project root (ignored by Git):

```properties
storeFile=C:/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

- Forward slashes, no quotes: `\` is an escape character in `.properties` files.
- Not in `local.properties`: the Secrets plugin copies every value there into `BuildConfig`,
  which would put the passwords inside the APK.
- Without this file the release build is unsigned; with a key missing, the build fails and
  names it. `scripts/scan_secrets.py` checks every change for the passwords.

**Maps key.** The release key's SHA-1 must be allowed on the Maps API key, or the map is
blank in release builds. Get it with `./gradlew :app:signingReport` (`Variant: release`), and
add it with the package `com.danielbrog.activitystats` under the key's Android app
restrictions in the Google Cloud console.

## Each release

1. **Bump the version** in `app/build.gradle.kts`, in its own PR: `versionCode` + 1 (never
   reused, even for a deleted release) and a new `versionName`. Merge it.
2. **Build from a clean `main`**, so the tag can always rebuild the APK:

   ```powershell
   git checkout main
   git pull
   git status   # nothing changed or untracked, apart from .idea/
   ./gradlew assembleRelease
   ```

   The APK is `app/build/outputs/apk/release/app-release.apk`. Keep
   `app/build/outputs/mapping/release/mapping.txt` alongside it (not in Git) to read crash
   traces from the shrunk build.
3. **Smoke-test it on a device.** R8 shrinking can break things the debug build doesn't show.
   The release and debug builds are signed differently, so one can't replace the other
   (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`): sync, then `adb uninstall
   com.danielbrog.activitystats` before installing. Check sign-in and restore (maps and
   charts), recording with the screen locked, background sync after the app is closed, a
   segment, and GPX export and import.
4. **Tag and push:** `git tag v<versionName>`, then `git push origin v<versionName>`.
5. **Publish:** a new GitHub release on that tag, with the APK attached
   (`activity-stats-<versionName>.apk`), short notes, and **pre-release** ticked while testing.

Anyone with the APK can create an account; the usage limits (`backend/lambda/usage.ts`) cap
what each account can do, not how many sign up.
