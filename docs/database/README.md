# Database docs

Where Activity Stats keeps its data, and how the phone and the cloud stay in step.

| Page | Covers |
|---|---|
| [phone.md](phone.md) | the Room database on the phone, as it is now: every table and column, keys, indexes, migration history |
| [server.md](server.md) | the cloud, as it is now: the DynamoDB `Activities` table, the S3 track files, and which Lambda may touch what |
| [segments.md](segments.md) | the **planned** design for segments: syncing personal segments, review, and verified public segments |

## The big picture

```
            Phone (Room: activity_stats.db)                      Cloud (AWS)
 ┌──────────────────────────────────────────┐        ┌───────────────────────────────┐
 │ activities ──< track_points              │  sync  │ DynamoDB  Activities          │
 │     │   (user data)                      │ ─────> │   one item per ride summary   │
 │     ├──< best_efforts     (derived)      │ <───── │                               │
 │     └──< segment_efforts  (derived)      │        │ S3  Tracks                    │
 │              │                           │        │   one gzipped file per ride   │
 │ segments ────┘   (user data)             │        │ DynamoDB  Segments            │
 │                                          │        │   your segments (and reviews) │
 │ verified_segments (cache, empty for now) │        │ VerifiedSegments, Public-     │
 │ catalog_tiles     (cache, empty for now) │        │   SegmentTiles (empty for now)│
 └──────────────────────────────────────────┘        └───────────────────────────────┘
```

- **The phone's database is what every screen reads.** The app works fully offline; the cloud is
  a copy for backup and other devices.
- **User data** (rides, their GPS points, segments) can't be worked out again, so it's synced.
- **Derived data** (best efforts, segment efforts) is worked out from the points on each phone,
  can always be rebuilt, and is never synced.

## How sync fits the two together

1. **Up:** rides waiting to upload (`syncState = PENDING`) send their track file first (once),
   then their summary. The cloud keeps whichever copy has the newer `updatedAt`.
2. **Down:** the phone asks for rides the server has saved since its last look (by the server's
   own `syncedAt` clock), saves new ones with their tracks, and takes newer cloud copies of ones
   it has.
3. **Then segments, the same way:** your waiting segments go up, and segments and their review
   results changed in the cloud come down, from their own cursor. A downloaded segment is
   matched against every ride; a review result always takes the server's word.
4. Deletions are soft (`deletedAt`), so they travel like any other edit.

Sync runs when the app opens, after a change, and on Sync now, in the background with
WorkManager. While signed out nothing is sent; rides and segments made then are claimed by the
account at the next sign-in.

## Conventions everywhere

- Times are epoch milliseconds, UTC. Units are raw (metres, m/s, ms); conversion is only in the UI.
- Ids are UUIDs made on the phone and are the same on the phone and in the cloud.
- The server never trusts the phone for who the user is: it takes the user from the verified
  sign-in token.

## Keeping these docs right

Each page lists the source files it describes. Update the page in the same change as those
files: a new column, a new migration, a new table or a new route.
