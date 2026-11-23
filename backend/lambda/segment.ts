import { ACTIVITY_TYPES } from './shared';

/**
 * A personal segment as the app sends it: the rider-owned fields only (see
 * docs/database/segments.md). Review fields (status and so on) are never accepted from the
 * phone; only the server's review routes write them. Raw units: metres, epoch ms.
 */
export interface SegmentBody {
  name: string;
  path: string;
  distanceMeters: number;
  sport: (typeof ACTIVITY_TYPES)[number];
  createdAt: number;
  updatedAt: number;
  deletedAt: number | null;
}

export const MAX_NAME_LENGTH = 60;
/** An encoded line this long is thousands of points: far more than any real segment. */
export const MAX_PATH_LENGTH = 50_000;
export const MIN_DISTANCE_METERS = 100;
export const MAX_DISTANCE_METERS = 100_000;

/** Google's encoded polyline uses only the characters from '?' (63) to '~' (126). */
const POLYLINE = /^[\x3F-\x7E]+$/;

/** The sort keys of a segment's item and of its review item, in the Segments table. */
const SEG_PREFIX = 'SEG#';
export const REVIEW_SUFFIX = '#REVIEW';
export const segmentKey = (id: string) => `${SEG_PREFIX}${id}`;
export const reviewKey = (id: string) => `${segmentKey(id)}${REVIEW_SUFFIX}`;
/** Exactly `SEG#<id>` or `SEG#<id>#REVIEW`, with an id as `pathIdOf` allows. */
const SORT_KEY = /^SEG#([A-Za-z0-9-]{1,64})(#REVIEW)?$/;

/** What a phone gets of each item type: an allow-list, so nothing server-only leaks. */
const SEGMENT_FIELDS = ['name', 'path', 'distanceMeters', 'sport', 'createdAt', 'updatedAt', 'deletedAt', 'syncedAt'] as const;
const REVIEW_FIELDS = ['status', 'submittedAt', 'decidedAt', 'reviewNote', 'verifiedSegmentId', 'syncedAt'] as const;

/** A phone's clock can be off, but not by more than this: a time further ahead is refused. */
export const MAX_CLOCK_AHEAD_MS = 24 * 60 * 60 * 1000;

type Result = { ok: true; value: SegmentBody } | { ok: false; message: string };

const isTime = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0;
/**
 * A time no further ahead than [MAX_CLOCK_AHEAD_MS]: a phone with a wildly wrong clock could
 * otherwise stamp a year far ahead, and every honest edit after it would be "older" forever.
 */
const isRecentTime = (v: unknown, now: number): v is number => isTime(v) && v <= now + MAX_CLOCK_AHEAD_MS;

/**
 * Checks a request body field by field and keeps only the rider's fields, so nothing extra
 * (a userId, a status, a verified id) can be slipped into the stored item.
 */
export function parseSegment(body: string | undefined, isBase64Encoded = false, now = Date.now()): Result {
  if (!body) return { ok: false, message: 'Missing body' };
  let data: Record<string, unknown>;
  try {
    const text = isBase64Encoded ? Buffer.from(body, 'base64').toString('utf8') : body;
    const parsed: unknown = JSON.parse(text);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return { ok: false, message: 'Body must be a JSON object' };
    }
    data = parsed as Record<string, unknown>;
  } catch {
    return { ok: false, message: 'Body is not valid JSON' };
  }

  const bad = (field: string) => ({ ok: false as const, message: `Invalid ${field}` });
  const { name, path, distanceMeters, sport, createdAt, updatedAt, deletedAt } = data;

  const cleanName = typeof name === 'string' ? name.trim() : '';
  if (cleanName.length === 0 || cleanName.length > MAX_NAME_LENGTH) return bad('name');
  if (typeof path !== 'string' || path.length > MAX_PATH_LENGTH || !POLYLINE.test(path)) return bad('path');
  if (
    typeof distanceMeters !== 'number' ||
    !Number.isFinite(distanceMeters) ||
    distanceMeters < MIN_DISTANCE_METERS ||
    distanceMeters > MAX_DISTANCE_METERS
  ) {
    return bad('distanceMeters');
  }
  if (typeof sport !== 'string' || !(ACTIVITY_TYPES as readonly string[]).includes(sport)) return bad('sport');
  if (!isRecentTime(createdAt, now)) return bad('createdAt');
  if (!isRecentTime(updatedAt, now) || updatedAt === 0) return bad('updatedAt');
  if (deletedAt !== null && deletedAt !== undefined && !isTime(deletedAt)) return bad('deletedAt');

  return {
    ok: true,
    value: {
      name: cleanName,
      path,
      distanceMeters,
      sport: sport as SegmentBody['sport'],
      createdAt,
      updatedAt,
      deletedAt: (deletedAt as number | null | undefined) ?? null,
    },
  };
}

/**
 * A Segments item as the app sees it, labelled by kind: `{ kind: 'segment', id, …fields }` or
 * `{ kind: 'review', segmentId, …fields }`. Only the listed fields are sent, so the table keys,
 * the review queue marker and server-only fields (like which admin decided) never are. Null for
 * a sort key of any other shape.
 */
export function segmentItemToApi(item: Record<string, unknown>): Record<string, unknown> | null {
  const match = typeof item.sk === 'string' ? SORT_KEY.exec(item.sk) : null;
  if (!match) return null;
  const pick = (fields: readonly string[]) =>
    Object.fromEntries(fields.filter((f) => f in item).map((f) => [f, item[f]]));
  return match[2]
    ? { kind: 'review', segmentId: match[1], ...pick(REVIEW_FIELDS) }
    : { kind: 'segment', id: match[1], ...pick(SEGMENT_FIELDS) };
}
