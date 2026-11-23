# Cloud storage (AWS)

What the backend stores, and how phones read and write it. Everything is defined in CDK in
`backend/lib/activity-stats-stack.ts`, in region us-east-2. The phone's own database is in
[phone.md](phone.md); planned segment tables are in [segments.md](segments.md#server-dynamodb).

Source of truth for this page: the stack file above and the Lambdas in `backend/lambda/`
(`summary.ts` for the stored fields, `shared.ts` for limits and the track key). Update this page
in the same change as either.

## At a glance

| Store | Holds | Written by | Read by |
|---|---|---|---|
| DynamoDB `Activities` | one item per ride: its summary | `PutActivity` | `ListActivities` |
| S3 `Tracks` bucket | one file per ride: every GPS fix | the phone, through a short-lived upload link | the phone, through a short-lived download link |

All requests go through one HTTP API with a Cognito sign-in check. Each Lambda takes the user
from the verified token, never from the request, so a phone can only ever touch its own
account's data. The API is throttled (20 requests a second, bursts of 40) so a bug can't run up
a bill.

## DynamoDB `Activities`

On-demand billing, point-in-time recovery, deletion protection, and kept if the stack is ever
removed.

**Key:** `userId` (partition, string: the Cognito user id) + `activityId` (sort, string: the
ride's UUID from the phone).

| Attribute | Type | Null? | Meaning |
|---|---|---|---|
| `userId` | S | no | owner; from the token, never from the body |
| `activityId` | S | no | the phone's ride id |
| `type` | S | no | `ride` / `run` / `walk` |
| `startedAt` | N | no | epoch ms |
| `endedAt` | N | yes | |
| `distanceMeters` | N | no | |
| `movingTimeMs` | N | no | |
| `elapsedTimeMs` | N | no | |
| `avgSpeedMps` | N | no | |
| `maxSpeedMps` | N | no | |
| `elevationGainMeters` | N | yes | |
| `title` | S | no | 1–200 characters (the app itself caps at 60) |
| `updatedAt` | N | no | the phone's edit time; decides which copy is newer |
| `deletedAt` | N | yes | soft delete, so deletions reach every device |
| `syncedAt` | N | no | the server's time, stamped on every save |

Not stored: `syncState` and `trackUploaded` (phone-only bookkeeping) and the box (worked out on
each phone).

**Index `bySyncedAt`:** `userId` + `syncedAt`, all attributes projected. This is how a phone asks
"what changed since I last looked?": `syncedAt` comes from the server's clock, so it doesn't
depend on any phone's clock being right.

### Rules

- **Saving (`PUT /activities/{id}`):** the item is written only if there's no stored copy, or the
  stored copy's `updatedAt` isn't newer. Otherwise nothing changes and the reply is 409 with the
  stored copy, which the phone then takes. Sending the same copy twice is harmless.
- **Only known fields are stored:** `parseSummary` checks each field and drops anything else, so
  nothing like another user's id can be slipped in.
- **Listing (`GET /activities?since=…`):** a query on `bySyncedAt` for `syncedAt > since`, in pages
  of 100 with a `nextCursor` while more remain. Deleted rides are included, so deletions spread.
  Phones read back over the last 5 minutes before their saved cursor, in case a save landed just
  as they listed.

## S3 `Tracks` bucket

Private (all public access blocked), encrypted at rest, HTTPS only, kept if the stack is removed.
Unfinished multipart uploads are cleaned up after a day.

**Key:** `tracks/<userId>/<activityId>.json.gz`, one object per ride.

**Format:** gzipped JSON, written by `TrackFile` on the phone. Uploaded with content type
`application/gzip`.

```json
{
  "version": 2,
  "fixes": [
    { "seq": 1, "t": 1793000000000, "lat": 43.65, "lon": -79.38,
      "alt": 112.0, "acc": 4.5, "spd": 7.2, "status": "Kept", "stretch": 0 }
  ]
}
```

| Field | Meaning (matches `track_points`) |
|---|---|
| `version` | format version: 2 now. 1 used the key `segment` for `stretch`; it still reads, without pauses. |
| `seq`, `t`, `lat`, `lon` | order, time (epoch ms), position |
| `alt`, `acc`, `spd` | altitude, accuracy, speed; left out when unknown |
| `status` | the noise filter's decision; every fix is kept, dropped ones included |
| `stretch` | the part between manual pauses |

Names are short because a long ride has tens of thousands of fixes. GPX stays an export format
only: it carries just the kept points, without accuracy or status.

**Links:** the phone asks `POST /activities/{id}/track-upload` for an upload link and
`GET /activities/{id}/track` for a download link. Both are presigned S3 links valid for
5 minutes, for that one object. A ride's track is uploaded once, before its summary, since a
finished ride's points never change; a phone that downloads a ride without its track yet skips
it and tries again later.

## Who may touch what

| Lambda | Route | `Activities` | `Tracks` |
|---|---|---|---|
| `PutActivity` | `PUT /activities/{id}` | read, write | — |
| `ListActivities` | `GET /activities` | read | — |
| `TrackUploadUrl` | `POST /activities/{id}/track-upload` | — | put, under `tracks/*` |
| `TrackDownloadUrl` | `GET /activities/{id}/track` | — | read, under `tracks/*` |

Each Lambda gets only these permissions in CDK, and checks the user from the token itself.

## Not stored in the cloud

- `best_efforts` and `segment_efforts`: derived, rebuilt on each phone from the points.
- `segments`: not synced yet; planned in [segments.md](segments.md).
- Settings (units, speed or pace, auto-pause): stay on the phone.
