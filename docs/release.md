# Making a release

A release is a signed APK attached to a GitHub release. Nothing builds or publishes it
automatically yet: these steps are done by hand.

## Once: the signing key

Every release must be signed with the **same key**. Android only installs an update signed
like the version already on the phone, so losing the key means nobody can update: they would
have to uninstall, and lose any rides not yet synced.

1. In Android Studio: **Build → Generate Signed App Bundle or APK → APK → Create new**.
   - Save the `.jks` file **outside this project folder** (for example in your documents),
     and back it up somewhere else too.
   - Use **the same password** for the keystore and the key. The file it makes (PKCS12)
     can't have a separate key password: two different ones fail later with "Cannot recover
     key".
   - Untick **Remember passwords**, and once the key file is made, press **Cancel**. Gradle
     does the signing from here, so the wizard doesn't need to build anything.
2. Keep the password in a password manager. It never goes in Git, in chat, or in
   `local.properties` (the Secrets plugin copies everything there into the app's
   `BuildConfig`, so a password there would be inside the APK).
3. Create `keystore.properties` in the project root. Git ignores it.

   ```properties
   storeFile=C:/path/to/your/release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

   - Use forward slashes in the path, and no quotes. A `\` is an escape character in this
     file, so `C:\Users\…` loses its backslashes and the build says the keystore isn't found.
   - `storePassword` and `keyPassword` are the same value (see step 1).
   - A relative `storeFile` is read from the project root.
   - Without this file the release build still works, but comes out unsigned
     (`app-release-unsigned.apk`), which phones won't install.
4. **Let the map work in the release build.** The Maps key is restricted to the app's package
   and the fingerprints of the keys that sign it. Get the release key's SHA-1:

   ```powershell
   .\gradlew :app:signingReport
   ```

   and copy the `SHA1` under `Variant: release`. (Or, without Gradle:
   `keytool -list -v -keystore C:/path/to/your/release.jks -alias <your alias>`.) In the
   Google Cloud console, open the Maps key's **Android apps** restriction and add the package
   `com.danielbrog.activitystats` with that SHA-1. Without it the map is blank.

`scripts/scan_secrets.py` checks for the passwords in every change, as it does the values in
`local.properties`.

## Each release

The APK must be built from a commit that is on `main`, so its tag can always rebuild it.

1. **Version, in its own small PR.** In `app/build.gradle.kts`, raise `versionCode` by one
   and set `versionName`, like `0.2.0`. Phones only take a higher `versionCode` as an update,
   and a `versionCode` is never used twice, even for a release that was deleted or only ever
   installed on your own phone. (The first release, `versionCode = 1` and `versionName =
   "0.1.0"`, came in with the signing setup.) Merge the PR.
2. **Build from a clean `main`.**

   ```powershell
   git checkout main
   git pull
   git status
   ```

   `git status` must show nothing changed or untracked outside `.idea/`: anything else would
   end up in the APK without being in the tag. Then:

   ```powershell
   .\gradlew assembleRelease
   ```

   The APK is `app/build/outputs/apk/release/app-release.apk`. Keep
   `app/build/outputs/mapping/release/mapping.txt` with it (somewhere safe, not in Git): it
   turns the shrunk build's crash stack traces back into readable ones.
3. **Try it on a phone first.** The release build is shrunk (R8), which can break things the
   debug build never shows. It is also signed with a different key from the debug app, so
   Android won't install one over the other (the error is `INSTALL_FAILED_UPDATE_INCOMPATIBLE`):
   1. Open the app and let it sync until nothing is waiting, or rides only on the phone are lost.
   2. Uninstall the debug app: `adb uninstall com.danielbrog.activitystats`.
   3. Install the release APK: `adb install app/build/outputs/apk/release/app-release.apk`.
   4. Check:
      - sign in, and the rides come back with their maps and charts
      - record a short ride with the screen locked, and it syncs
      - in airplane mode, rename a ride and close the app; turn airplane mode off and the
        rename syncs on its own (WorkManager starts the sync by class name)
      - rename and delete a ride, create a segment
      - export a ride as GPX and import it back
      - sign out and in again

   Going back to the debug app works the same way: sync first, then uninstall the release one.
4. **Tag** that commit, and push the tag:

   ```powershell
   git tag v0.1.0
   ```

   ```powershell
   git push origin v0.1.0
   ```

5. **Publish.** On GitHub: **Releases → Draft a new release**, choose the tag, attach the APK
   (renamed to something like `activity-stats-0.1.0.apk`), write a few lines on what changed,
   and tick **Set as a pre-release** while it is for testing.

Before publishing, remember that anyone with the APK can create an account and use the API:
the usage limits (`backend/lambda/usage.ts`) cap what one account can do, not how many sign up.
