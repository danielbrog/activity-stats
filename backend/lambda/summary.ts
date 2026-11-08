import { ACTIVITY_TYPES } from './shared';

/**
 * A ride summary as the app sends it, matching the app's ActivityEntity minus the fields the
 * server owns (userId, syncedAt) and the local-only syncState. Raw units: metres, m/s, epoch ms.
 */
export interface ActivitySummary {
  type: (typeof ACTIVITY_TYPES)[number];
  startedAt: number;
  endedAt: number | null;
  distanceMeters: number;
  movingTimeMs: number;
  elapsedTimeMs: number;
  avgSpeedMps: number;
  maxSpeedMps: number;
  elevationGainMeters: number | null;
  title: string;
  updatedAt: number;
  deletedAt: number | null;
}

export const MAX_TITLE_LENGTH = 200;

type Result = { ok: true; value: ActivitySummary } | { ok: false; message: string };

const isTime = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0;
const isAmount = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v) && v >= 0;

/**
 * Checks a request body field by field and keeps only the known fields, so nothing extra
 * (such as a userId) can be slipped into the stored item.
 */
export function parseSummary(body: string | undefined, isBase64Encoded = false): Result {
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
  const { type, startedAt, endedAt, distanceMeters, movingTimeMs, elapsedTimeMs } = data;
  const { avgSpeedMps, maxSpeedMps, elevationGainMeters, title, updatedAt, deletedAt } = data;

  if (typeof type !== 'string' || !(ACTIVITY_TYPES as readonly string[]).includes(type)) return bad('type');
  if (!isTime(startedAt)) return bad('startedAt');
  if (endedAt !== null && !isTime(endedAt)) return bad('endedAt');
  if (!isAmount(distanceMeters)) return bad('distanceMeters');
  if (!isTime(movingTimeMs)) return bad('movingTimeMs');
  if (!isTime(elapsedTimeMs)) return bad('elapsedTimeMs');
  if (!isAmount(avgSpeedMps)) return bad('avgSpeedMps');
  if (!isAmount(maxSpeedMps)) return bad('maxSpeedMps');
  if (elevationGainMeters !== null && !isAmount(elevationGainMeters)) return bad('elevationGainMeters');
  if (typeof title !== 'string' || title.trim().length === 0 || title.length > MAX_TITLE_LENGTH) return bad('title');
  if (!isTime(updatedAt) || updatedAt === 0) return bad('updatedAt');
  if (deletedAt !== null && !isTime(deletedAt)) return bad('deletedAt');

  return {
    ok: true,
    value: {
      type: type as ActivitySummary['type'],
      startedAt,
      endedAt,
      distanceMeters,
      movingTimeMs,
      elapsedTimeMs,
      avgSpeedMps,
      maxSpeedMps,
      elevationGainMeters,
      title,
      updatedAt,
      deletedAt,
    },
  };
}

/** A stored item as the app receives it: `id` in place of the table's keys. */
export function toApi(item: Record<string, unknown>): Record<string, unknown> {
  const { userId: _owner, activityId, ...rest } = item;
  return { id: activityId, ...rest };
}
