# Phone database (Room)

The app's own database on the phone: a single SQLite file, `activity_stats.db`, managed by Room.
Every screen reads from it; the cloud is a copy kept in step by sync (see [server.md](server.md)).

**Current version: 8.** Version 8 laid the tables for synced and public segments; what uses
them, slice by slice, is in [segments.md](segments.md).

Source of truth for this page:
`app/src/main/java/com/danielbrog/activitystats/data/Entities.kt` (tables), `SyncState.kt` and
`SegmentStatus.kt` (enums, stored as their names),
`AppDatabase.kt` (version and migrations), the DAOs next to them, and the exported schemas in
`app/schemas/com.danielbrog.activitystats.data.AppDatabase/<version>.json`. Update this page in
the same change as any of those.

## Conventions

- **Times** are epoch milliseconds, UTC. Time zones only appear in the UI.
- **Units** are raw: metres, metres per second, milliseconds. Converting to km/mi happens only in
  `Format`.
- **Ids** are UUIDs made on the phone, so two devices never create the same one.
- **Soft delete:** user data is never removed straight away. It gets `deletedAt`, every screen
  hides it, and the deletion syncs to other devices. Undo clears it.
- **User data vs derived data:** user data (rides, points, segments) syncs. Derived data
  (best efforts, segment efforts) is worked out from the points on each phone, can always be
  rebuilt, and is never synced.

## Tables at a glance

```
activities 1 ──< track_points        (a ride's GPS fixes)
activities 1 ──< best_efforts        (derived: fastest 5 km, 10 km, …)
activities 1 ──< segment_efforts >── 1 segments            (derived: times on your segments)
                         └────────── 1 verified_segments   (… and on public ones: planned)
catalog_tiles                        (which map tiles of public segments are downloaded)
```

There are no foreign keys: rides are only soft-deleted, so rows pointing at them stay valid,
and queries hide what belongs to deleted rides or segments.

## `activities`: one row per recorded ride

User data. Synced (summary to DynamoDB, points to S3 as a track file).

| Column | Type | Null? | Default | Meaning |
|---|---|---|---|---|
| `id` | TEXT PK | no | | UUID made on the phone |
| `userId` | TEXT | yes | | owning account (Cognito id); null if recorded signed out. Signing in claims rides with none. |
| `type` | TEXT | no | `'ride'` | `ride` / `run` / `walk` |
| `startedAt` | INTEGER | no | | |
| `endedAt` | INTEGER | yes | | null while recording, or after a crash until recovery finishes it |
| `distanceMeters` | REAL | no | `0` | kept fixes only, never across a manual pause |
| `movingTimeMs` | INTEGER | no | `0` | time moving: stops (auto-pause) and manual pauses left out |
| `elapsedTimeMs` | INTEGER | no | `0` | start to finish, stops and pauses included |
| `avgSpeedMps` | REAL | no | `0` | distance ÷ moving time |
| `maxSpeedMps` | REAL | no | `0` | |
| `elevationGainMeters` | REAL | yes | | total climbing; null until worked out |
| `title` | TEXT | no | | 1–60 characters (`RideTitle`) |
| `updatedAt` | INTEGER | no | | bumped on every edit; sync's "newest wins" compares it |
| `deletedAt` | INTEGER | yes | | soft delete |
| `syncState` | TEXT | no | `'LOCAL_ONLY'` | `LOCAL_ONLY` (no account) · `PENDING` (waiting to upload) · `SYNCED` · `FAILED` (server refused it) |
| `trackUploaded` | INTEGER (bool) | no | `0` | the track file is in the cloud; uploaded once, since a finished ride's points never change |
| `minLat`, `maxLat`, `minLon`, `maxLon` | REAL | yes | | box around the kept fixes, for segment matching; null while recording, with no kept fixes, or saved before version 7 |

Indexes: `endedAt`, `startedAt`.

Edits (`rename`, soft delete, restore) bump `updatedAt` and move `SYNCED` or `FAILED` back to
`PENDING`, so the change is uploaded.

## `track_points`: every GPS fix of every ride

User data. Synced as the ride's track file, not row by row.

| Column | Type | Null? | Default | Meaning |
|---|---|---|---|---|
| `activityId` | TEXT | no | | the ride |
| `seq` | INTEGER | no | | order within the ride, from 1 |
| `timestamp` | INTEGER | no | | |
| `latitude`, `longitude` | REAL | no | | |
| `altitude` | REAL | yes | | GPS height (ellipsoid), metres |
| `accuracyMeters` | REAL | yes | | |
| `speedMps` | REAL | yes | | as reported by the phone |
| `status` | TEXT | no | | the noise filter's decision: `Kept` · `DroppedAccuracy` · `DroppedStationary` |
| `stretch` | INTEGER | no | `0` | which part between manual pauses; goes up by one on each Resume |

Primary key: `activityId` + `seq`.

Every fix is kept, dropped ones included, so the noise filter can be re-tuned and re-run on old
rides. Everything else (distance, the map, charts, matching) uses only `Kept` fixes, and never
joins distance across a change of `stretch`.

## `best_efforts`: fastest time over set distances

Derived data, never synced.

| Column | Type | Null? | Meaning |
|---|---|---|---|
| `activityId` | TEXT | no | the ride |
| `effort` | TEXT | no | `5km`, `10km`, `20km`, `40km`, `5mi`, `10mi`, `25mi` |
| `elapsedMs` | INTEGER | yes | null when the ride is shorter than the distance |

Primary key: `activityId` + `effort`. Index: `effort`.

Every ride gets a row for every distance, so a ride with no rows at all is one still to be
worked out (the app fills those in at start-up).

## `segments`: road segments the rider made

User data: your own segments only. Not synced yet (planned: see [segments.md](segments.md)).

| Column | Type | Null? | Default | Meaning |
|---|---|---|---|---|
| `id` | TEXT PK | no | | UUID made on the phone |
| `userId` | TEXT | yes | | owner, or null if made signed out |
| `name` | TEXT | no | | 1–60 characters (`SegmentName`) |
| `path` | TEXT | no | | the line, start to end, as a Google encoded polyline, simplified to 5 m |
| `distanceMeters` | REAL | no | | as ridden on the ride it was made from, before simplifying; the distance shown |
| `createdAt`, `updatedAt` | INTEGER | no | | |
| `deletedAt` | INTEGER | yes | | soft delete; Undo restores it with its efforts |
| `syncState` | TEXT | no | `'LOCAL_ONLY'` | as for rides |
| `sport` | TEXT | no | `'ride'` | the type of the ride it was made from; only rides of this type are matched |
| `status` | TEXT | no | `'PRIVATE'` | `PRIVATE` · `SUBMITTED` · `PUBLIC` · `REJECTED` · `UNPUBLISHED` |
| `submittedAt` | INTEGER | yes | | when it was submitted to go public |
| `decidedAt` | INTEGER | yes | | when an admin approved, rejected or unpublished it |
| `reviewNote` | TEXT | yes | | the admin's reason, when rejected or unpublished |
| `verifiedSegmentId` | TEXT | yes | | the verified copy it became, once approved |

Index: `deletedAt`.

Its ends and its box are worked out from `path` when needed, not stored. The review fields
(`status` to `verifiedSegmentId`) are a copy of what the server decided; until review is built
every segment stays `PRIVATE`.

## `verified_segments`: downloaded public segments

Cache of the server's catalogue, never uploaded. Empty until the public catalogue is built.
`VerifiedSegmentDao` has no edit queries: it only saves what the server sent and removes what
it no longer lists.

| Column | Type | Null? | Meaning |
|---|---|---|---|
| `id` | TEXT PK | no | the server's verified id; never a personal segment's id |
| `name` | TEXT | no | can change (an admin rename bumps `version`) |
| `path` | TEXT | no | never changes for this id |
| `distanceMeters` | REAL | no | |
| `sport` | TEXT | no | as for `segments` |
| `minLat`, `maxLat`, `minLon`, `maxLon` | REAL | no | its box |
| `version` | INTEGER | no | |
| `verifiedAt`, `updatedAt` | INTEGER | no | |
| `cachedAt` | INTEGER | no | when last downloaded, for clearing out old ones |

## `catalog_tiles`: downloaded catalogue tiles

| Column | Type | Null? | Meaning |
|---|---|---|---|
| `tile` | TEXT PK | no | a 4-character geohash (about 39 × 20 km) |
| `fetchedAt` | INTEGER | no | when its public segments were last downloaded |

Empty until the public catalogue is built.

## `segment_efforts`: each time a ride covered a segment

Derived data, never synced. Worked out in the background when a segment is made and when a
ride is finished, imported, downloaded or restored, only between rides and segments of the same
sport.

| Column | Type | Null? | Meaning |
|---|---|---|---|
| `segmentId` | TEXT | no | the segment: in `segments` (and, once the catalogue is built, `verified_segments`; ids never clash) |
| `activityId` | TEXT | no | the ride |
| `occurrence` | INTEGER | no | 0, 1, 2… for each time this ride covered it (laps) |
| `startedAt` | INTEGER | no | when the ride left the segment's start |
| `elapsedMs` | INTEGER | no | time to the end; always one continuous effort (no pause or stop inside) |

Primary key: `segmentId` + `activityId` + `occurrence`. Index: `activityId`.

Re-matching a ride replaces only its efforts on segments that aren't deleted, so a deleted
segment comes back whole with Undo. (Planned for the catalogue: that clean-up must also cover
efforts on verified segments, keeping only those on deleted personal segments.)

## Migration history

All migrations are automatic (Room works them out from the saved schemas). `MigrationTest`
runs 6 → 7, 7 → 8 and the whole chain 1 → 8 against real saved schemas.

| Version | Change |
|---|---|
| 1 | `activities` and `track_points` |
| 2 | adds `best_efforts` |
| 3 | adds `activities.elevationGainMeters` (nullable) |
| 4 | adds `track_points.segment` for manual pauses (existing points get 0) |
| 5 | adds `activities.trackUploaded` for cloud sync (existing rides get false) |
| 6 | renames `track_points.segment` to `stretch` (values kept), freeing "segment" for road segments |
| 7 | adds `segments` and `segment_efforts`, and each ride's box (`activities.minLat` and so on) |
| 8 | `segments` gains `sport` (existing ones become ride segments), `status` (`PRIVATE`) and review fields; adds `verified_segments` and `catalog_tiles` |

### Making a change

1. Change the entity, add it to `@Database(entities = …)` if new, and bump `version`.
2. Add an `AutoMigration(from, to)` (with a spec for renames or deletes).
3. Build once so Room writes the new `<version>.json`; commit it.
4. Extend `MigrationTest` for the new step.
5. Update this page, and [server.md](server.md) if synced fields changed.
