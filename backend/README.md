# Activity Stats backend

The AWS side of Activity Stats: accounts, ride summaries, track files and the sync API. It is
a separate project from the Android app. Gradle never builds this folder and none of it goes
into the APK. The app only needs the five values printed after a deploy (see below).

## What it creates

| Part | AWS service | Notes |
|---|---|---|
| Accounts | Cognito user pool | Email and password, on Cognito's hosted sign-in page |
| Ride summaries | DynamoDB table `userId` + `activityId` | Index `bySyncedAt` for "what changed since my last sync" |
| Track points | Private S3 bucket | One gzipped file per ride at `tracks/{userId}/{activityId}.json.gz` |
| API | API Gateway HTTP API | Every route needs a Cognito token; throttled to 20 requests/s |
| Logic | 4 Lambda functions | Node 22 on ARM, TypeScript |

### API

| Route | What it does |
|---|---|
| `PUT /activities/{id}` | Saves a ride summary. Only replaces the stored copy if this one's `updatedAt` isn't older; otherwise returns `409` with the stored copy. A ride not stored yet counts towards the weekly ride limit (`429` past it) |
| `GET /activities?since=<ms>&cursor=<c>` | Rides saved after `since` (the largest `syncedAt` from the last pull), 100 per page, deleted ones included |
| `PUT /activities/{id}/track` | Saves the gzipped track file sent as the body. Checked first: at most 1 MB (`413` over), it must unzip within 8 MB and be the app's track format with sane values (`400` otherwise); a clean copy is stored. A new ride counts towards the weekly ride limit: 10 per account per week for now (`429` past it) |
| `GET /activities/{id}/track` | The gzipped track file itself; `404` if not uploaded yet. Counts towards the daily download limit: 500 per account per UTC day for now (`429` past it) |

### Security

- The user id comes only from the verified Cognito token (`sub`), never from the URL or body.
- The bucket blocks all public access, is encrypted, and refuses plain HTTP. The app reaches
  files only through the API (uploads are checked, downloads are counted), always inside its
  own user's folder.
- Each function's role allows only what it needs. For example, the upload function can only
  write to `tracks/*`.
- The user pool, table and bucket are **kept if the stack is deleted**, and the pool and
  table have deletion protection, so `cdk destroy` can't lose accounts or rides.

### Alerts

Everything below emails the alarm address through the `ActivityStatsAlarms` SNS topic. AWS
first sends a confirmation email; nothing arrives until its link is clicked.

| Alarm | Fires when |
|---|---|
| `ActivityStats-PutActivityErrors`, `-ListActivitiesErrors`, `-PutTrackErrors`, `-GetTrackErrors` | That function fails at least once in 5 minutes |
| `ActivityStats-ApiServerErrors` | The API returns 5 or more server errors (5xx) in 5 minutes |
| Budget `ActivityStats-monthly` | The whole account's spend this month passes $5, or is forecast to |

Alarms also email when they clear. They use only built-in metrics, so they stay within
CloudWatch's free alarms, and AWS Budgets without actions are free.

### Two clocks

`updatedAt` is set by the phone and decides which copy of a ride is newer. `syncedAt` is set
by the server on every save, and is what the app asks "changed since" against. That way a
phone with a wrong clock can't make another device miss a change.

## Commands

From this folder:

```bash
npm install          # once
npm test             # Lambda and stack tests, no AWS account needed
npm run synth        # build the CloudFormation template and bundle the code into cdk.out/
```

## Deploying

The backend lives in **us-east-2** (Ohio). The region is fixed in `bin/app.ts`, so it doesn't
matter which region the CLI defaults to.

1. Sign the AWS CLI in with your console login: `aws login` (needs AWS CLI 2.32 or newer).
   Check it with `aws sts get-caller-identity`. The session lasts up to 12 hours.
2. Once per account and region, prepare it for the CDK: `npx cdk bootstrap aws://<account>/us-east-2`
3. Say where alerts go, in `cdk.context.json` next to this file. It is git-ignored, so the
   address stays on your PC:
   ```json
   { "alarmEmail": "you@example.com" }
   ```
   Or pass it each time: `npx cdk deploy -c alarmEmail=you@example.com`.
4. Deploy: `npx cdk deploy`. Review the security changes it lists, then confirm.

After a deploy, the outputs are what the app needs: `ApiUrl`, `UserPoolId`, `AppClientId`,
`SignInUrl` and `Region`. None of them is a secret.

Cost for personal use is close to nothing: everything bills per request, and the Cognito
Lite plan and the other services' free tiers cover a handful of users.
