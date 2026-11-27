# Releasing

Releases are signed APKs attached to GitHub releases. Pushing a `v*` tag runs
`.github/workflows/release.yml`, which builds the signed APK and drafts the release; you
smoke-test it and publish it by hand. Building one locally is still possible: see the end.

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

**GitHub `release` environment.** The workflow's secrets live here, and only `v*` tags can
use them, after you approve the run.

1. Settings → Environments → **New environment**, named `release`.
   - **Required reviewers:** yourself. Leave "Prevent self-review" off.
   - **Deployment branches and tags:** Selected branches and tags → **Add rule** → type Tag,
     pattern `v*`.
2. Add the secrets to that environment, in PowerShell from the project root. Each
   `gh secret set` without a value asks for it and hides what you paste.

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.jks")) | gh secret set KEYSTORE_BASE64 --env release
   ```

   Then, one at a time, pasting each value from `keystore.properties` and `local.properties`:
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `MAPS_API_KEY`, `COGNITO_DOMAIN`,
   `COGNITO_CLIENT_ID`, `SYNC_API_URL`, each as `gh secret set <NAME> --env release`.
3. Add the certificate fingerprint as a variable (it's public, not a secret): the `SHA-256`
   line under `Variant: release` in `./gradlew :app:signingReport`.

   ```powershell
   gh variable set RELEASE_CERT_SHA256 --env release --body "AB:CD:..."
   ```

   The workflow refuses to release an APK signed with any other certificate.
4. **Tag ruleset:** Settings → Rules → Rulesets → New tag ruleset, target pattern `v*`, with
   **Restrict updates** and **Restrict deletions**, so a published tag can't move.

When the key or a setting changes, set the secret again.

## Each release

1. **Bump the version** in `app/build.gradle.kts`, in its own PR: `versionCode` + 1 (never
   reused, even for a deleted release) and a new `versionName`. Merge it.
2. **Tag `main` and push the tag:**

   ```powershell
   git checkout main
   git pull
   git tag v<versionName>
   git push origin v<versionName>
   ```

   The workflow checks the tag equals `v` + `versionName` and points at a commit on `main`,
   then waits for you.
3. **Approve the run:** Actions → Release → Review deployments → `release` → Approve. It
   builds, checks the signing certificate, and creates a **draft pre-release** with
   `activity-stats-<versionName>.apk`. R8's mapping (to read crash traces from the shrunk
   build) is on the run's page under **Artifacts** as `mapping-<versionName>`, for 10 days
   only: download it and keep it outside the repository. If it's lost, rebuilding the tag
   gives the same file.
4. **Smoke-test the draft's APK on a device.** R8 shrinking can break things the debug build
   doesn't show. The release and debug builds are signed differently, so one can't replace the
   other (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`): sync, then `adb uninstall
   com.danielbrog.activitystats` before installing. Check sign-in and restore (maps and
   charts), recording with the screen locked, background sync after the app is closed, a
   segment, and GPX export and import.
5. **Publish:** edit the draft, trim the notes, keep **pre-release** ticked while testing, and
   publish.

**If a run fails or the APK is broken:** delete the draft (if any) and the tag
(`git push origin :refs/tags/v<versionName>`, then `git tag -d v<versionName>`; the tag
ruleset needs a bypass for this, or delete it there), fix it in a PR, bump the version again if
the broken APK was ever published, and tag again.

## Building a release locally

From a clean `main`, so the tag can always rebuild the APK: `git status` shows nothing apart
from `.idea/`, then `./gradlew assembleRelease`. The APK is
`app/build/outputs/apk/release/app-release.apk`; keep
`app/build/outputs/mapping/release/mapping.txt` alongside it (not in Git).

Anyone with the APK can create an account; the usage limits (`backend/lambda/usage.ts`) cap
what each account can do, not how many sign up.
