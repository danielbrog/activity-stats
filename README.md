# Activity Stats

I built this Android app to track my bike rides. It records a ride with the phone's GPS, keeps it on the phone, and syncs it to a small AWS backend I set up, so my rides are backed up and come back if I change phones.

| Record | Ride detail | Records | Profile | Settings |
|:---:|:---:|:---:|:---:|:---:|
| <img src="docs/images/record.png" width="160" alt="Record screen before starting a ride"> | <img src="docs/images/ride-detail.png" width="160" alt="A ride's map and stats"> | <img src="docs/images/records.png" width="160" alt="Personal records and best efforts"> | <img src="docs/images/profile.png" width="160" alt="Profile with the list of rides"> | <img src="docs/images/settings.png" width="160" alt="Settings: units, speed or pace, auto-pause"> |

## What it does

**Recording a ride**
- Shows time, distance, current and average speed (or pace), and max speed while I ride, with the route drawn on a map.
- Turns the GPS on as soon as the Record screen is open, and shows how accurate it is, so the start of the ride isn't spoiled by a bad first fix.
- Auto-pause stops the timer when I stop. I can also pause by hand, which turns the GPS off until I resume.
- When I press Finish, it pauses and asks for a name before saving.
- Keeps recording with the screen off. The notification has Pause/Resume and Finish buttons.

**Looking back at rides**
- Each ride has a map, km or mile splits, speed and elevation charts, and total climbing.
- Records shows my longest ride, longest moving time and biggest climb, plus my fastest 5, 10, 20 and 40 km (or 5, 10 and 25 mi) from any ride.
- Rides can be exported as GPX files, which Strava, Komoot and Garmin Connect can open. GPX files can be imported too.
- Rides can be renamed or deleted. A delete can be undone.

**Sync** (optional; everything else works without an account)
- I sign in through Amazon Cognito. Rides upload when I finish, rename or delete them, and the app syncs whenever I open it.
- On a new phone, signing in brings my rides back, maps included.
- Each ride shows whether it's synced. The Account screen shows when the last sync happened, and has a Sync now button.

**Settings**
- km or miles, speed or pace, and auto-pause on or off. Every screen uses these, including the notification and the charts.

## What it's built with

- **App:** Kotlin and Jetpack Compose. Room stores rides, DataStore stores settings, and WorkManager runs sync in the background. Google's fused location provider supplies GPS. Maps use Maps Compose, and sign-in uses AppAuth.
- **Backend:** written with the AWS CDK in TypeScript. API Gateway passes requests to four small Lambda functions. Ride summaries go in DynamoDB and the GPS tracks go in S3. Cognito handles sign-in, using OAuth 2.0 with PKCE, and its JWTs are checked by API Gateway before any function runs. A few CloudWatch alarms and a $5 monthly budget email me if something goes wrong.
- **Tests:** JUnit for the app logic, Room tests that run on the phone, and Jest for the backend.

## How it fits together

```mermaid
flowchart LR
    subgraph Phone
        UI["Screens"] --> VM["ViewModels"]
        VM --> Repo["ActivityRepository"]
        Svc["Recording service"] --> Repo
        Repo --> DB[("Room")]
        Worker["Sync worker"] --> Repo
    end
    subgraph AWS
        Cognito["Cognito"]
        API["API Gateway"] --> Lambda["Lambda"]
        Lambda --> Dynamo[("DynamoDB")]
        Lambda -. upload/download links .-> S3[("S3")]
    end
    VM -. sign in .-> Cognito
    Worker -- "ride summaries" --> API
    Worker -- "GPS tracks" --> S3
```

| Folder | What's in it |
|---|---|
| `location/` | The recording service, the GPS filter, and the GPS warm-up |
| `stats/` | Distance, speed, splits, best efforts and climbing, and all unit formatting |
| `data/` | The Room database and the repository |
| `sync/` | Uploading, downloading and merging rides |
| `account/` | Sign-in |
| `live/`, `history/`, `profile/`, `records/`, `settings/` | The screens |
| `export/` | GPX reading and writing |

There's more about the backend in [`backend/README.md`](backend/README.md).

## Problems I had to solve

**GPS noise.** Phone GPS jumps around, especially when you're standing still, and that adds fake distance. [`GpsFilter`](app/src/main/java/com/danielbrog/activitystats/location/GpsFilter.kt) ignores fixes that are less accurate than ±20 m. It also ignores fixes that have moved less than their own accuracy from the last good fix, while going slower than 0.5 m/s. The ignored fixes are still saved, so I can change the filter later and re-run it on old rides.

**Pausing.** If I pause and then move the bike, by walking it or putting it in a car, that distance shouldn't count. Each time I resume, the ride starts a new segment. Distance, time, splits, best efforts and charts never measure across the gap between segments. GPX export keeps them as separate track segments.

**Sync without losing anything.** The phone has to work offline and catch up later:
- Each ride's GPS track uploads before its summary, so a ride in the cloud always has its track.
- If the same ride was changed in two places, the most recently edited copy wins.
- If I rename a ride while it's uploading, the rename isn't lost. A ride is only marked as synced if it hasn't changed since it was sent.
- When downloading, the app re-reads the last 5 minutes before where it left off. DynamoDB's index can show a new ride slightly late, and this stops one from being skipped.

The upload, download and merge logic is plain Kotlin, tested against a fake server ([`Uploader`](app/src/main/java/com/danielbrog/activitystats/sync/Uploader.kt), [`Downloader`](app/src/main/java/com/danielbrog/activitystats/sync/Downloader.kt), [`Merge`](app/src/main/java/com/danielbrog/activitystats/sync/Merge.kt)).

**Not losing a ride if the app dies.** GPS points are saved every 10 points or 10 seconds while riding. If the app is killed or the battery runs out, the next time it opens it finishes the ride from the points that were saved.

**Units.** All conversions between km, miles, metres and feet happen in one place, [`Format`](app/src/main/java/com/danielbrog/activitystats/stats/Format.kt), so the screens, charts and notification always agree.

## Tests

- 230+ unit tests in `app/src/test`. They cover the stats, the GPS filter, splits, best efforts, GPX, and sync. They run on the computer, no phone needed.
- Tests in `app/src/androidTest` that run against the real database on a phone.
- Tests in `backend/test` that check the AWS setup, for example that every API route needs sign-in, plus the Lambda functions.

```bash
./gradlew testDebugUnitTest            # unit tests
./gradlew connectedDebugAndroidTest    # on-device tests (phone or emulator connected)
cd backend && npm test                 # backend tests
```

## Running it yourself

1. Open the project in Android Studio.
2. Add a Google Maps API key to `local.properties`. That file isn't in git; [`secrets.defaults.properties`](secrets.defaults.properties) lists the keys it can hold.
   ```properties
   MAPS_API_KEY=your-google-maps-key
   ```
3. Run it. Recording, history and records all work without a backend. The Account screen just says sign-in isn't set up.
4. To use sync, deploy the backend (see [`backend/README.md`](backend/README.md)) and add its outputs to `local.properties`:
   ```properties
   COGNITO_DOMAIN=https://<prefix>.auth.<region>.amazoncognito.com
   COGNITO_CLIENT_ID=<AppClientId>
   SYNC_API_URL=<ApiUrl>
   ```

My own account details only live in files git ignores. [`scripts/scan_secrets.py`](scripts/scan_secrets.py) checks each change for them before I push.

## License

[MIT](LICENSE)
