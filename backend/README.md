# Activity Stats backend

The AWS side of Activity Stats: accounts, ride and segment sync, and track files. It's an AWS
CDK project in TypeScript, separate from the Android app: Gradle never builds it and none of it
goes into the APK. The full data model and rules are in
[`docs/database/server.md`](../docs/database/server.md).

## What it creates

| Part | Service | Notes |
|---|---|---|
| Accounts | Cognito user pool | Email and password, on Cognito's hosted sign-in page |
| Rides | DynamoDB `userId` + `activityId` | Index `bySyncedAt` for "changed since my last sync" |
| Segments | DynamoDB `userId` + `sk` | Each rider's own; two more tables are reserved for verified public segments |
| Usage counts | DynamoDB `userId` + `period` | For the limits below; rows delete themselves after two weeks |
| Tracks | Private S3 bucket | One gzipped file per ride, `tracks/{userId}/{activityId}.json.gz` |
| API | API Gateway HTTP API | Every route needs a Cognito token; throttled to 20 requests/s |
| Logic | 6 Lambda functions | Node 22 on ARM |

## API

| Route | What it does |
|---|---|
| `PUT /activities/{id}` | Saves a ride summary if it isn't older than the stored one (`409` with the stored copy otherwise) |
| `GET /activities?since=&cursor=` | Rides saved after `since`, 100 per page, deleted ones included |
| `PUT /activities/{id}/track` | Checks the gzipped track file (≤ 1 MB, unzips within 8 MB, sane values) and stores a clean copy |
| `GET /activities/{id}/track` | The track file itself; `404` if not uploaded yet |
| `PUT /segments/{id}` | Saves a segment, newer copy wins; its line and sport never change (`400` if they would) |
| `GET /segments?since=&cursor=` | Segments saved after `since`, 100 per page |

**Two clocks.** `updatedAt` comes from the phone and decides which copy is newer. `syncedAt` is
stamped by the server on every save, and is what "changed since" compares, so a phone with a
wrong clock can't make another device miss a change.

**Limits per account**, for testing, in [`lambda/usage.ts`](lambda/usage.ts):

| Limit | Counts |
|---|---|
| 10 new rides a week (Monday UTC) | A ride's first track or live summary; retries don't count twice |
| 500 track downloads a day (UTC) | Every download that finds a file |
| 200 edits a day (UTC) | Ride renames and deletes, and every segment save |

Past a limit the reply is `429` with `kind`, `limit` and `resetsAt`, which the app tells apart
from API Gateway's own rate limit, and nothing is stored.

## Security and alerts

- The user id comes only from the verified token (`sub`), never from the URL or body.
- The bucket blocks public access, is encrypted and refuses plain HTTP; the app only reaches it
  through the API.
- Each function's role allows only what it needs (stack tests check this).
- The user pool, rides table and bucket are kept if the stack is deleted, and the pool and
  table have deletion protection, so `cdk destroy` can't lose accounts or rides.
- Each function has an error alarm, the API has a 5xx alarm, and a $5 monthly budget covers the
  whole account. All of them email through the `ActivityStatsAlarms` SNS topic, once its
  confirmation link has been clicked.

## Commands

From this folder:

```bash
npm install          # once
npm test             # Lambda and stack tests, no AWS account needed
npm run synth        # build the CloudFormation template into cdk.out/
```

## Deploying

The stack lives in **us-east-2** (Ohio), fixed in `bin/app.ts`.

1. Sign the AWS CLI in: `aws login` (AWS CLI 2.32+), and check with `aws sts get-caller-identity`.
2. Once per account and region: `npx cdk bootstrap aws://<account>/us-east-2`.
3. Set where alerts go, in the git-ignored `cdk.context.json` next to this file:
   `{ "alarmEmail": "you@example.com" }` (or pass `-c alarmEmail=…` each time).
4. `npx cdk diff`, then `npx cdk deploy`, reviewing the security changes it lists.

The outputs are `ApiUrl`, `UserPoolId`, `AppClientId`, `SignInUrl` and `Region`; the app needs
three of them (see the main [README](../README.md#running-it-yourself)). None is a secret.

Cost for personal use is close to nothing: everything bills per request, within the free tiers
and Cognito's Lite plan.
