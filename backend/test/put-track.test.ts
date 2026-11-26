import { event, RIDE_ID, status, USER } from './helpers';
import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { DynamoDBDocumentClient, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { mockClient } from 'aws-sdk-client-mock';
import { gunzipSync, gzipSync } from 'node:zlib';
import { handler } from '../lambda/put-track';
import { MAX_FIXES, MAX_TRACK_BYTES, parseTrack } from '../lambda/track';

const s3 = mockClient(S3Client);
const db = mockClient(DynamoDBDocumentClient);
beforeEach(() => {
  s3.reset();
  db.reset();
  db.on(UpdateCommand).resolves({}); // the week has room, unless a test says otherwise
});

// The handler checks times against the real clock, so the fixes are an hour ago from now.
const NOW = Date.now();

/** A fix as the app writes it (TrackFile.FixJson), at second [i] of a ride. */
const fix = (i: number, extra: Record<string, unknown> = {}) => ({
  seq: i + 1,
  t: NOW - 3_600_000 + i * 1000,
  lat: 43.6532267 + i * 1e-5,
  lon: -79.3831846,
  alt: 87.5,
  acc: 4.2,
  spd: 6.9,
  status: 'Kept',
  stretch: 0,
  ...extra,
});

const track = (fixes: unknown[], version: unknown = 2) => ({ version, fixes });
const zip = (value: unknown) => gzipSync(Buffer.from(JSON.stringify(value), 'utf8'));
const unzip = (bytes: Uint8Array) => JSON.parse(gunzipSync(Buffer.from(bytes)).toString('utf8'));

const upload = (bytes: Buffer, sub: string | null = USER, id = RIDE_ID) =>
  handler(event({ pathParameters: { id }, body: bytes.toString('base64'), isBase64Encoded: true }, sub));

describe('checking a track file', () => {
  test('a track as the app writes it passes, and the stored copy holds the same fixes', () => {
    const fixes = [fix(0), fix(1, { alt: null, acc: null, spd: null }), fix(2, { status: 'DroppedAccuracy', stretch: 1 })];
    const result = parseTrack(zip(track(fixes)), NOW);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.fixes).toBe(3);
    const stored = unzip(result.gzipped);
    expect(stored.version).toBe(2);
    expect(stored.fixes[0]).toEqual(fix(0));
    // Missing values are left out, as the app reads them back as null.
    expect(stored.fixes[1]).toEqual({ seq: 2, t: fix(1).t, lat: fix(1).lat, lon: fix(1).lon, status: 'Kept', stretch: 0 });
    expect(stored.fixes[2]).toMatchObject({ status: 'DroppedAccuracy', stretch: 1 });
  });

  test('a track exactly as the app writes it passes: defaults and missing values left out', () => {
    // TrackFile.encode leaves out null alt/acc/spd and a stretch of 0 (kotlinx's encodeDefaults is off).
    const t = NOW - 60_000;
    const text = `{"version":2,"fixes":[{"seq":1,"t":${t},"lat":43.6532267,"lon":-79.3831846,"status":"DroppedAccuracy"},` +
      `{"seq":2,"t":${t + 1000},"lat":43.6532901,"lon":-79.3831822,"alt":87.0,"acc":4.2,"spd":6.9,"status":"Kept"}]}`;
    const result = parseTrack(gzipSync(Buffer.from(text, 'utf8')), NOW);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    const stored = unzip(result.gzipped);
    expect(stored.fixes[0]).toEqual({ seq: 1, t, lat: 43.6532267, lon: -79.3831846, status: 'DroppedAccuracy', stretch: 0 });
    expect(stored.fixes[0]).not.toHaveProperty('alt');
    expect(stored.fixes[1]).toMatchObject({ alt: 87, acc: 4.2, spd: 6.9 });
  });

  test('a ride with no fixes passes: Start, then Stop before the first fix', () => {
    expect(parseTrack(zip(track([])), NOW)).toMatchObject({ ok: true, fixes: 0 });
  });

  test('odd but real values from an import pass', () => {
    // A "no value" elevation marker, feet saved as metres, and a wildly inaccurate dropped fix.
    const odd = [fix(0, { alt: -32_768 }), fix(1, { alt: 29_000 }), fix(2, { acc: 15_000, status: 'DroppedAccuracy' })];
    expect(parseTrack(zip(track(odd)), NOW)).toMatchObject({ ok: true, fixes: 3 });
  });

  test('millions of tiny objects are refused before they are read', () => {
    // About 8 MB of "{}," zips to a few KB: under both size limits, but reading it as JSON
    // would build millions of objects and could run the function out of memory.
    const many = '{"version":2,"fixes":[' + '{},'.repeat(2_600_000) + '{}]}';
    const file = gzipSync(Buffer.from(many, 'utf8'));
    expect(file.length).toBeLessThan(MAX_TRACK_BYTES);
    expect(parseTrack(file, NOW)).toMatchObject({ ok: false, status: 400 });
  });

  test('anything else in the file is dropped from the stored copy', () => {
    const result = parseTrack(zip({ version: 2, note: 'hello', fixes: [fix(0, { photo: 'x'.repeat(100) })] }), NOW);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    const stored = unzip(result.gzipped);
    expect(Object.keys(stored)).toEqual(['version', 'fixes']);
    expect(stored.fixes[0]).not.toHaveProperty('photo');
  });

  test('a file over the size limit is refused before it is unzipped', () => {
    expect(parseTrack(Buffer.alloc(MAX_TRACK_BYTES + 1), NOW)).toMatchObject({ ok: false, status: 413 });
  });

  test('a zip bomb is refused without being expanded in full', () => {
    // 50 MB of zeros zips to about 50 KB: well under the size limit, far over what it may unzip to.
    const bomb = gzipSync(Buffer.alloc(50_000_000));
    expect(bomb.length).toBeLessThan(MAX_TRACK_BYTES);
    expect(parseTrack(bomb, NOW)).toMatchObject({ ok: false, status: 400 });
  });

  test('files that are not tracks are refused', () => {
    const notTracks: Buffer[] = [
      Buffer.alloc(0),
      Buffer.from('not gzip at all'),
      gzipSync(Buffer.from('not json')),
      zip([1, 2, 3]),
      zip({ version: 2 }),
      zip(track([fix(0)], 1)),
      zip(track([fix(0)], 99)),
      zip(track(['a fix'])),
    ];
    for (const file of notTracks) expect(parseTrack(file, NOW)).toMatchObject({ ok: false, status: 400 });
  });

  test('a fix the app could not have recorded is refused', () => {
    const badFixes: Record<string, unknown>[] = [
      { lat: 91 },
      { lon: -181 },
      { lat: 'north' },
      { t: -1 },
      { t: NOW + 2 * 24 * 3_600_000 }, // more than a day ahead
      { seq: 1.5 },
      { alt: Number.MAX_VALUE },
      { acc: -1 },
      { spd: -1 },
      { status: 'Teleported' },
      { stretch: -1 },
    ];
    for (const change of badFixes) {
      expect(parseTrack(zip(track([fix(0), fix(1, change)])), NOW)).toMatchObject({ ok: false, status: 400 });
    }
  });

  test('more fixes than any real ride is refused', () => {
    const many = Array.from({ length: MAX_FIXES + 1 }, (_, i) => ({ seq: i, t: NOW - 1_000_000, lat: 0, lon: 0, status: 'Kept' }));
    expect(parseTrack(zip(track(many)), NOW)).toMatchObject({ ok: false, status: 400 });
  });
});

describe('PUT /activities/{id}/track', () => {
  test("a good track is stored, cleaned, in the caller's own folder", async () => {
    s3.on(PutObjectCommand).resolves({});
    const result = await upload(zip(track([fix(0), fix(1)])));
    expect(status(result)).toBe(200);
    const input = s3.commandCalls(PutObjectCommand)[0].args[0].input;
    expect(input.Bucket).toBe('test-bucket');
    expect(input.Key).toBe(`tracks/${USER}/${RIDE_ID}.json.gz`);
    expect(input.ContentType).toBe('application/gzip');
    expect(unzip(input.Body as Uint8Array).fixes).toHaveLength(2);
  });

  test('a new ride is counted towards the week before its track is stored', async () => {
    s3.on(PutObjectCommand).resolves({});
    await upload(zip(track([fix(0)])));
    const count = db.commandCalls(UpdateCommand)[0].args[0].input;
    expect(count.TableName).toBe('test-usage');
    expect(count.Key).toMatchObject({ userId: USER });
    expect(count.ExpressionAttributeValues![':id']).toBe(RIDE_ID);
  });

  test('past the weekly limit the track is refused with a 429 and not stored', async () => {
    db.on(UpdateCommand).rejects(new ConditionalCheckFailedException({ message: 'full', $metadata: {} }));
    const result = await upload(zip(track([fix(0)])));
    expect(status(result)).toBe(429);
    expect(JSON.parse((result as { body: string }).body)).toMatchObject({ limit: 10 });
    expect(s3.commandCalls(PutObjectCommand)).toHaveLength(0);
  });

  test('a file that fails the checks uses up none of the week', async () => {
    await upload(Buffer.from('just some text'));
    expect(db.commandCalls(UpdateCommand)).toHaveLength(0);
  });

  test('a bad or oversized file is refused and nothing is stored', async () => {
    expect(status(await upload(Buffer.from('just some text')))).toBe(400);
    expect(status(await upload(Buffer.alloc(MAX_TRACK_BYTES + 10)))).toBe(413);
    expect(s3.commandCalls(PutObjectCommand)).toHaveLength(0);
  });

  test('a body that is not binary is refused', async () => {
    const result = await handler(event({ pathParameters: { id: RIDE_ID }, body: '{"version":2}', isBase64Encoded: false }));
    expect(status(result)).toBe(400);
    expect(s3.commandCalls(PutObjectCommand)).toHaveLength(0);
  });

  test('bad ids and missing users are refused before anything is read', async () => {
    const good = zip(track([fix(0)]));
    expect(status(await upload(good, USER, '../../etc'))).toBe(400);
    expect(status(await upload(good, null))).toBe(401);
    expect(s3.commandCalls(PutObjectCommand)).toHaveLength(0);
  });
});
