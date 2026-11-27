# Activity Stats

## What this project is

Activity Stats is a GPS ride tracker for cyclists who want their own stats without a social
network. The core idea is **segments**: compare yourself on the same climb or fun section over
time and see the improvement. Solo developer.

**Principles**
- **Local first.** Everything works with no account and no network. Sign-in and sync are an
  optional backup, never required for a feature.
- **Private and non-social.** No feeds, followers, likes or sharing by default.
- **Cheap to run.** Keep AWS near $0; per-account limits protect it.
- **Cycling first.** Speed over pace, bike-friendly defaults. Running and other sports come
  later, so don't hard-code "bike" where a sport field would do.

**Heading towards 1.0**
- A public segment list (shared, verified segments). No leaderboards: comparing against
  yourself is the point.
- Heart rate and other sensors (Bluetooth LE heart-rate straps, later power and cadence).
- Google Play release alongside GitHub, signed with the same key so either build can update
  the other. Free with ads (provider and tracking undecided) plus a cheap subscription (about
  $1-5 a month) that removes them.
- More activity types once cycling is solid.

**Not now:** iOS, a web app, leaderboards or other social features.

**Devices:** Pixel 7 (primary), Galaxy S20 (occasional second device). Android 8.0+ (minSdk 26).

## Layout

- `app/`: the Android app. Kotlin, Jetpack Compose, Room, WorkManager. Code is in
  `app/src/main/java/com/danielbrog/activitystats/`, by feature: `live/`, `history/`,
  `segments/`, `records/`, `profile/`, `settings/` (screens), `data/` (Room and repositories),
  `sync/` (upload, download, merge), `account/` (Cognito sign-in), `location/`, `export/` (GPX),
  `navigation/`, `ui/` (shared pieces).
- `backend/`: AWS CDK in TypeScript (us-east-2). API Gateway, six Lambdas, DynamoDB, S3,
  Cognito. Never built by Gradle, never in the APK. See `backend/README.md`.
- `docs/database/`: the data model and sync rules, phone and server. Read these before
  changing sync or the schema.
- `docs/release.md`: making a signed release.

## Commands

- Kotlin style fix: `$env:JAVA_HOME="E:\Android\Android Studio\jbr"; .\gradlew.bat ktlintFormat --no-daemon --max-workers=1 -q`
- Backend, from `backend/`: `npm run build` (type check), `npm test` (jest, runs in band).
- Build and unit tests: **don't run them yourself.** The `release-checker` agent runs them one
  at a time (the PC is low on memory). The on-device test compile, ktlint check and lint are
  left to CI.
- CI: `.github/workflows/pr-checks.yml` runs all the checks (`release-checker`'s plus those
  three) on every PR and on `main`, picking jobs from the changed paths. It must always cover
  everything `release-checker` runs, except the local-values part of the secret scan. Pushing a `v*` tag runs
  `.github/workflows/release.yml` (signed APK, draft release); see `docs/release.md`.
- Never run `connectedDebugAndroidTest`: it wipes the app on the phone. The owner runs it.

## How to work here

- **Plan first.** Show a short plan and wait for a yes before changing files, committing or
  pushing, even for small changes. Reading is fine.
- **Small slices.** One screen or one behaviour per PR; list the extras as later slices.
- The full workflow, from plan to cleanup after merge, is the `/slice` skill.
- **Explaining:** short answers by default. The owner comes from Java, so add a brief Java
  comparison when something is new or Kotlin-specific (coroutines, sealed classes, Flow,
  Compose state).
- **Commits:** titles describe the change, with no milestone numbers like "(M4)".
- **Deploy order:** when the app and backend both change, say in the PR which goes first, and
  keep old app versions working against the new backend.

## Secrets and safety

- Never open, print or search `local.properties`, `keystore.properties` or
  `backend/cdk.context.json`. `python -I scripts/scan_secrets.py` reads them for you and
  reports only `file:line  kind`.
- `.githooks/pre-commit` runs that scan on every commit (turn it on per clone with
  `git config core.hooksPath .githooks`). Never skip it with `--no-verify`.
- Everything in `local.properties` is copied into BuildConfig and ships in the APK, so it may
  only hold values that are public by design. Release signing lives in `keystore.properties`.
- Never stage `.idea/`. Stage files by name.
- The owner deploys the backend (`cdk diff`, then `cdk deploy`); this shell has no AWS
  credentials. Never create Cognito accounts or paste AWS keys.
- Keep the AWS account id, the owner's email and home location out of commits, PRs and
  screenshots.
