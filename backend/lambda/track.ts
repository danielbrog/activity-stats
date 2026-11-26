import { gunzipSync, gzipSync } from 'node:zlib';
import { MAX_CLOCK_AHEAD_MS } from './segment';

/**
 * The largest track file accepted, gzipped, in bytes. A fix is about 38 bytes gzipped and the
 * phone saves about one a second, so 1 MB is roughly 7 hours of recording: any 60 km ride,
 * even with long stops. A placeholder for testing; raise it when real rides need more.
 */
export const MAX_TRACK_BYTES = 1_000_000;

/**
 * The most a track may unzip to. Checked while unzipping, so a tiny file that expands to
 * gigabytes (a "zip bomb") is refused without ever being expanded in full.
 */
export const MAX_UNZIPPED_BYTES = 8_000_000;

/** The most fixes one track may hold: about 11 hours at one a second, well past MAX_TRACK_BYTES. */
export const MAX_FIXES = 40_000;

/** The track file versions this backend reads; the app's TrackFile.VERSION. */
export const TRACK_VERSIONS = [2] as const;

/** A GPS fix's status, as the app's FixStatus enum names it. */
export const FIX_STATUSES = ['Kept', 'DroppedAccuracy', 'DroppedStationary'] as const;

/** One fix as stored: only these fields, so nothing else can be smuggled into the bucket. */
interface CleanFix {
  seq: number;
  t: number;
  lat: number;
  lon: number;
  alt?: number;
  acc?: number;
  spd?: number;
  status: (typeof FIX_STATUSES)[number];
  stretch: number;
}

type Result = { ok: true; gzipped: Buffer; fixes: number } | { ok: false; status: number; message: string };

const isInt = (v: unknown, min: number, max: number): v is number =>
  Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max;
const isIn = (v: unknown, min: number, max: number): v is number =>
  typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
/** Optional numbers: missing or null, or a finite number in range. */
const isOptionalIn = (v: unknown, min: number, max: number) => v === undefined || v === null || isIn(v, min, max);

/**
 * Checks an uploaded track file and makes the copy to store. Plain code, unit-tested.
 *
 * In order: the size (over [MAX_TRACK_BYTES] is a 413), that it unzips within
 * [MAX_UNZIPPED_BYTES] and holds no more objects than [MAX_FIXES] could, that it is the app's
 * track JSON, and that every fix has sane values. The stored copy is rebuilt from the checked fields alone and zipped
 * afresh, so a file that passes can't carry anything else into the bucket.
 */
export function parseTrack(file: Buffer, now = Date.now()): Result {
  if (file.length > MAX_TRACK_BYTES) return { ok: false, status: 413, message: `Track larger than ${MAX_TRACK_BYTES} bytes` };
  if (file.length === 0) return { ok: false, status: 400, message: 'Empty track' };
  const bad = (message: string) => ({ ok: false as const, status: 400, message });

  let text: string;
  try {
    text = gunzipSync(file, { maxOutputLength: MAX_UNZIPPED_BYTES }).toString('utf8');
  } catch {
    return bad('Not a track file');
  }
  // Reading JSON builds every object in memory before the fix count below can be checked, and
  // millions of tiny ones ("{},{},…") fit in a small file. A real track has one array and one
  // object per fix, and nothing in it can hold a "{" or "[" inside a string, so count them first.
  let containers = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c === 123 || c === 91) containers++;
  }
  if (containers > MAX_FIXES + 2) return bad('Invalid fixes');

  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    return bad('Not a track file');
  }

  if (typeof data !== 'object' || data === null || Array.isArray(data)) return bad('Not a track file');
  const { version, fixes } = data as Record<string, unknown>;
  if (!(TRACK_VERSIONS as readonly unknown[]).includes(version)) return bad('Unknown track version');
  // No fixes is a real ride: Start, then Stop before the first fix arrived.
  if (!Array.isArray(fixes) || fixes.length > MAX_FIXES) return bad('Invalid fixes');

  const latestTime = now + MAX_CLOCK_AHEAD_MS;
  const clean: CleanFix[] = [];
  for (let i = 0; i < fixes.length; i++) {
    const f: unknown = fixes[i];
    if (typeof f !== 'object' || f === null || Array.isArray(f)) return bad(`Invalid fix ${i}`);
    const { seq, t, lat, lon, alt, acc, spd, status, stretch } = f as Record<string, unknown>;
    if (!isInt(seq, 0, Number.MAX_SAFE_INTEGER)) return bad(`Invalid seq at fix ${i}`);
    if (!isInt(t, 0, latestTime)) return bad(`Invalid time at fix ${i}`);
    if (!isIn(lat, -90, 90) || !isIn(lon, -180, 180)) return bad(`Invalid position at fix ${i}`);
    // Wide on purpose: an imported file can hold "no value" markers (-32768) or feet saved as
    // metres, and a dropped fix can be very inaccurate. One odd value mustn't refuse a whole
    // ride; these are rewritten as plain numbers, so being finite is what protects the bucket.
    if (!isOptionalIn(alt, -100_000, 100_000)) return bad(`Invalid altitude at fix ${i}`);
    if (!isOptionalIn(acc, 0, 1_000_000)) return bad(`Invalid accuracy at fix ${i}`);
    if (!isOptionalIn(spd, 0, 100_000)) return bad(`Invalid speed at fix ${i}`);
    if (!(FIX_STATUSES as readonly unknown[]).includes(status)) return bad(`Invalid status at fix ${i}`);
    if (stretch !== undefined && !isInt(stretch, 0, 100_000)) return bad(`Invalid stretch at fix ${i}`);

    const fix: CleanFix = { seq, t, lat, lon, status: status as CleanFix['status'], stretch: (stretch as number | undefined) ?? 0 };
    if (typeof alt === 'number') fix.alt = alt;
    if (typeof acc === 'number') fix.acc = acc;
    if (typeof spd === 'number') fix.spd = spd;
    clean.push(fix);
  }

  const gzipped = gzipSync(Buffer.from(JSON.stringify({ version, fixes: clean }), 'utf8'));
  return { ok: true, gzipped, fixes: clean.length };
}
