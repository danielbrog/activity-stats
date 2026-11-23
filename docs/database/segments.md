# Segments database

How segments are stored on the phone and on the server: personal segments that sync to the
cloud, and verified public segments that only an admin can publish or change. This is the
**planned** design; slices build it piece by piece without further schema changes. What exists
today is in [phone.md](phone.md) and [server.md](server.md).

## The idea

- A rider's **personal segments** belong to them. They sync to the cloud and can be renamed or
  deleted at any time.
- A rider can **submit** a personal segment to go public. An admin reviews it.
- On approval the server makes a **verified copy** with a new id, in its own table. From then on
  the two are independent: the rider's edits never touch the verified one, and only admins can
  change it (its name only; its line never changes).
- Phones download verified segments **by map tile** near their rides and match them with the
  same on-phone matcher as personal segments.

## Phone (Room, version 8)

### `segments`: your own segments only

| Column | Type | Null? | Default | Meaning |
|---|---|---|---|---|
| `id` | TEXT PK | no | | UUID made on the phone |
| `userId` | TEXT | yes | | owner, or null if made while signed out |
| `name` | TEXT | no | | editable |
| `path` | TEXT | no | | encoded line (Google polyline) |
| `distanceMeters` | REAL | no | | as ridden on the creating ride |
| `sport` | TEXT | no | `'ride'` | an activity type, from the ride it was made from: `ride` for now, `run` / `walk` when they're added; fixed once created |
| `status` | TEXT | no | `'PRIVATE'` | `PRIVATE` / `SUBMITTED` / `PUBLIC` / `REJECTED` / `UNPUBLISHED` |
| `submittedAt` | INTEGER | yes | | |
| `decidedAt` | INTEGER | yes | | when approved, rejected or unpublished |
| `reviewNote` | TEXT | yes | | the reason when rejected or unpublished |
| `verifiedSegmentId` | TEXT | yes | | the verified copy it became |
| `createdAt`, `updatedAt` | INTEGER | no | | |
| `deletedAt` | INTEGER | yes | | soft delete |
| `syncState` | TEXT | no | `'LOCAL_ONLY'` | as for rides |

`status`, `submittedAt`, `decidedAt`, `reviewNote` and `verifiedSegmentId` are a read-only copy
of what the server decided; on the phone only Submit and Withdraw change them.

### `verified_segments`: downloaded public segments

| Column | Type | Null? | Meaning |
|---|---|---|---|
| `id` | TEXT PK | no | the server's verified id |
| `name` | TEXT | no | |
| `path` | TEXT | no | never changes for this id |
| `distanceMeters` | REAL | no | |
| `sport` | TEXT | no | |
| `minLat`, `maxLat`, `minLon`, `maxLon` | REAL | no | its box |
| `version` | INTEGER | no | goes up on each name change |
| `verifiedAt` | INTEGER | no | |
| `updatedAt` | INTEGER | no | |
| `cachedAt` | INTEGER | no | when last downloaded |

Its DAO has no edit queries: it only saves what the server sent and removes what the server no
longer lists (clearing out old rows comes with the catalogue). It is never uploaded.

### `segment_efforts`: unchanged

Key `segmentId` + `activityId` + `occurrence`. `segmentId` points at either table (ids are UUIDs,
so they never clash). Efforts are worked out on the phone and never synced.

### `catalog_tiles`

| Column | Type | Null? | Meaning |
|---|---|---|---|
| `tile` | TEXT PK | no | length-4 geohash (about 39 × 20 km) |
| `fetchedAt` | INTEGER | no | when its verified segments were last downloaded |

- A tile is downloaded again when it is more than a day old and a ride or the map needs it.
- After a download, verified rows whose box falls in that tile but weren't in the reply are
  removed (the phone works out a row's tiles from its box, so no link table is needed).
- Tiles not fetched for 30 days are dropped, with verified rows not refreshed for 30 days.

### Rules

- Matching a ride uses segments of the ride's `sport` from both tables, not deleted.
- A personal segment whose `verifiedSegmentId` is present in `verified_segments` is hidden from
  lists and matching: the verified one replaces it on the creator's phone.
- Lists show both tables together, with a Verified badge on verified ones. Only your own
  segments have the rename, delete and submit menu.

## Server (DynamoDB)

All tables: on-demand, point-in-time recovery, deletion protection, retained if the stack is
removed (as `Activities`).

### `Segments`: personal segments and their reviews

Key: `userId` (partition) + `sk` (sort). Two item types per segment, split by who may write them.

**Segment item**, `sk = SEG#<id>`, written only by the rider's upload route:

| Attribute | Type | Rules |
|---|---|---|
| name | S | 1–60 characters, cleaned |
| path | S | at most 50 KB |
| distanceMeters | N | 100 m – 100 km |
| sport | S | an activity type: the same list as rides (`ACTIVITY_TYPES` in `backend/lambda/shared.ts`); fixed once created |
| createdAt, updatedAt | N | phone time; `updatedAt` decides which copy is newer |
| deletedAt | N? | soft delete |
| syncedAt | N | server time, stamped on every write |

**Review item**, `sk = SEG#<id>#REVIEW`, exists once submitted; written only by the submit,
withdraw and admin routes:

| Attribute | Type | Notes |
|---|---|---|
| status | S | `SUBMITTED` / `PUBLIC` / `REJECTED` / `PRIVATE` / `UNPUBLISHED` |
| submittedAt | N | |
| decidedAt, decidedBy | N?, S? | |
| reviewNote | S? | |
| verifiedSegmentId | S? | set on approval |
| possibleDuplicateOf | S? | a verified id it looks like, from the submit check |
| queue | S? | `"SUBMITTED"` only while waiting |
| syncedAt | N | |

Indexes:

| Index | Key | Used by |
|---|---|---|
| `bySyncedAt` | `userId` + `syncedAt` | the phone's download cursor; returns both item types |
| `reviewQueue` | `queue` + `submittedAt` | the admin queue, oldest first (sparse) |

### `VerifiedSegments`

Key: `segmentId`, a new UUID made on approval.

| Attribute | Notes |
|---|---|
| name | the only field admins can change |
| path, distanceMeters, sport | frozen at approval |
| minLat, maxLat, minLon, maxLon; tiles | box, and the tiles it covers |
| version | goes up on each name change |
| verifiedAt, verifiedBy, updatedAt | |
| unpublishedAt | set when taken down; the row is kept |
| replacedBy | set when a corrected line is published as a new verified segment |
| sourceUserId, sourceSegmentId | never sent to phones |

### `PublicSegmentTiles`: the search index

Key: `tile` (length-4 geohash) + `segmentId`. Each row carries name, path, distanceMeters, sport,
box, version and updatedAt. Written only from `VerifiedSegments` (approve, rename, replace,
unpublish); one row per tile the segment's box touches. No user ids.

## Status changes

| From | To | Who | Route |
|---|---|---|---|
| none / `PRIVATE` / `REJECTED` / `UNPUBLISHED` | `SUBMITTED` | rider | submit |
| `SUBMITTED` | `PRIVATE` | rider | withdraw |
| `SUBMITTED` | `PUBLIC` | admin | approve |
| `SUBMITTED` | `REJECTED` | admin | reject |
| `PUBLIC` | `UNPUBLISHED` | admin | unpublish |

Resubmitting after `UNPUBLISHED` makes a new verified segment if approved.

## Routes

| Route | Who | Work |
|---|---|---|
| `PUT /segments/{id}` | rider | Put `SEG#id` if new or not older than stored; else 409 with the stored copy |
| `GET /segments?since=T` | rider | Query `bySyncedAt`, `syncedAt > T − 5 min` |
| `POST /segments/{id}/submit` | rider | Transaction: check `SEG#id` exists and isn't deleted; review → `SUBMITTED` with `queue`; run the duplicate check |
| `POST /segments/{id}/withdraw` | rider | Review `SUBMITTED` → `PRIVATE`, remove `queue` |
| `GET /admin/reviews` | admin | Query `reviewQueue`, then fetch the `SEG#` items |
| `POST /admin/reviews/{userId}/{id}/approve` | admin | Transaction: Put `VerifiedSegments` (new id, version 1); Put tile rows; review → `PUBLIC` with `verifiedSegmentId`, remove `queue`; only if still `SUBMITTED` |
| `POST /admin/reviews/{userId}/{id}/reject` | admin | Review → `REJECTED` with `reviewNote`, remove `queue` |
| `PATCH /admin/verified/{id}` | admin | Name only: bump `version`, rewrite tile rows |
| `POST /admin/verified/{id}/replace` | admin | Publish a corrected line as a new verified segment; set `replacedBy`; unpublish the old |
| `POST /admin/verified/{id}/unpublish` | admin | Set `unpublishedAt`, delete tile rows, source review → `UNPUBLISHED` with a note |
| `GET /verified?tiles=a,b,c` | rider | Query `PublicSegmentTiles` per tile, at most 9 tiles |

Duplicate check on submit: for each verified segment in the submission's tiles, test that the
starts are within 25 m, the ends within 25 m, and at least 90% of the submission's points lie
within 25 m of its line. The first match is stored as `possibleDuplicateOf`; it is a hint for
the admin, not a block.

Admin routes check the token's `cognito:groups` for `admins` and refuse with 403 otherwise.

## Permissions (CDK)

| Lambda | `Segments` | `VerifiedSegments` | `PublicSegmentTiles` |
|---|---|---|---|
| put / list segments, submit, withdraw | read and write | — | — |
| list verified (tiles) | — | — | read |
| admin routes | read and write | read and write | read and write |

No rider-facing Lambda can write a verified segment: AWS refuses the write.

## Shared rules

- Ids are UUIDs everywhere; a verified segment never reuses a personal segment's id.
- Geohash: Kotlin on the phone, TypeScript on the server, checked against one shared list of
  test points and cells.
- Admin: the Cognito group `admins`.

## Left out on purpose

Review history, S3 tile files and leaderboard tables. Each would be a new table or file later,
with nothing above changing.
