# Cloud storage (AWS)

What the backend stores, and how phones read and write it. Everything is defined in CDK in
`backend/lib/activity-stats-stack.ts`, in region us-east-2. The phone's own database is in
[phone.md](phone.md); the full segments design, including the routes still to come, is in
[segments.md](segments.md#server-dynamodb).

Source of truth for this page: the stack file above and the Lambdas in `backend/lambda/`
(`summary.ts` and `segment.ts` for the stored fields, `shared.ts` for limits and the track key). Update this page
in the same change as either.

## At a glance

| Store | Holds | Written by | Read by |
|---|---|---|---|
| DynamoDB `Activities` | one item per ride: its summary | `PutActivity` | `ListActivities` |
| S3 `Tracks` bucket | one file per ride: every GPS fix | the phone, through a short-lived upload link | the phone, through a short-lived download link |
| DynamoDB `Segments` | personal segments, and (later) their reviews | `PutSegment` | `ListSegments` |
| DynamoDB `VerifiedSegments` | verified public segments | nothing yet (admin routes later) | nothing yet |
| DynamoDB `PublicSegmentTiles` | the catalogue, by map tile | nothing yet (admin routes later) | nothing yet |

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

## DynamoDB `Segments`

On-demand, point-in-time recovery, deletion protection, kept if the stack is removed.

**Key:** `userId` (partition) + `sk` (sort). Each segment has a **segment item**,
`sk = SEG#<id>`, written only by `PutSegment`; later its **review item**, `sk = SEG#<id>#REVIEW`,
is written only by the review routes. The two are split by who may write them, so syncing a
rename can never overwrite a review decision.

Segment item:

| Attribute | Type | Rules (`segment.ts`) |
|---|---|---|
| `userId`, `sk` | S | from the token and the path; never the body |
| `name` | S | 1–60 characters, trimmed |
| `path` | S | encoded polyline (characters `?`–`~` only), at most 50,000 characters |
| `distanceMeters` | N | 100 m – 100 km |
| `sport` | S | an activity type, the same list as rides: `ride` / `run` / `walk` |
| `createdAt`, `updatedAt` | N | phone time, at most a day ahead of the server's; `updatedAt` decides which copy is newer |
| `deletedAt` | N | null unless deleted |
| `syncedAt` | N | server time, stamped on every save |

Indexes:

| Index | Key | Used by |
|---|---|---|
| `bySyncedAt` | `userId` + `syncedAt` | `ListSegments`: "what changed since" (both item types) |
| `reviewQueue` | `queue` + `submittedAt` | the admin queue, later; sparse, so empty for now |

### Rules

- **Saving (`PUT /segments/{id}`):** written only if there's no stored copy, or the stored one's
  `updatedAt` isn't newer **and** its `path` and `sport` are the same: a segment's line and sport
  never change. A newer stored copy comes back with a 409; a change of line or sport is refused
  with a 400. Both carry the stored copy (labelled, as below, and read up to date), so the phone
  can take it. A newer copy with no `deletedAt` brings a deleted segment back: that's how Undo
  syncs, as for rides.
- **Times more than a day ahead are refused,** so a phone with a wildly wrong clock can't stamp
  a far-future `updatedAt` that would make every later edit look older.
- **Only the rider's fields are stored:** `parseSegment` drops everything else, including any
  `status`, `verifiedSegmentId` or `queue`, so a phone can never set its own review state.
- **Listing (`GET /segments?since=…`):** a query on `bySyncedAt` for `syncedAt > since`, in pages
  of 100 with `nextCursor`, deleted ones included. Each item is labelled:
  `{ "kind": "segment", "id": …, …fields }` or `{ "kind": "review", "segmentId": …, …fields }`.
  Only listed fields are sent (an allow-list in `segment.ts`), so `userId`, `sk`, `queue` and
  admin-only fields like `decidedBy` never are; an item with any other key shape is skipped.

## DynamoDB `VerifiedSegments` and `PublicSegmentTiles`

Created empty, with the same backups and protection, for the review and catalogue slices:
`VerifiedSegments` is keyed by `segmentId`; `PublicSegmentTiles` by `tile` + `segmentId`. Their
fields are in [segments.md](segments.md#server-dynamodb). **No function can read or write them
yet**; only the admin routes will be able to write them.

## Who may touch what

| Lambda | Route | `Activities` | `Tracks` | `Segments` |
|---|---|---|---|---|
| `PutActivity` | `PUT /activities/{id}` | read, write | — | — |
| `ListActivities` | `GET /activities` | read | — | — |
| `TrackUploadUrl` | `POST /activities/{id}/track-upload` | — | put, under `tracks/*` | — |
| `TrackDownloadUrl` | `GET /activities/{id}/track` | — | read, under `tracks/*` | — |
| `PutSegment` | `PUT /segments/{id}` | — | — | put and get an item only |
| `ListSegments` | `GET /segments` | — | — | query the `bySyncedAt` index only |

Each Lambda gets only these permissions in CDK, and checks the user from the token itself.
The segment functions are narrower than the ride ones (no delete, update or scan, and no access
to the cross-rider `reviewQueue` index), since `Segments` will also hold server-owned review
items. None has any permission on `VerifiedSegments` or `PublicSegmentTiles`; stack tests check
all of this.

## Not stored in the cloud

- `best_efforts` and `segment_efforts`: derived, rebuilt on each phone from the points.
- The phone doesn't sync segments yet: the backend routes above exist, and the phone side
  comes next.
- Settings (units, speed or pace, auto-pause): stay on the phone.
