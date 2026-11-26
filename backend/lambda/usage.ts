import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { ApiResult, json } from './shared';

/**
 * The most new rides one account may upload in a week. A placeholder for testing; raise it
 * for real use (a rider commuting twice a day reaches 10 by Friday).
 *
 * Known limits of counting by upload week, to settle before raising this for real:
 * - A backlog (rides recorded signed out, claimed at sign-in) goes up 10 a week, oldest first,
 *   so the newest ride waits longest. Options: a one-off larger allowance on a first sync, or
 *   newest first once limited.
 * - A ride whose track and summary first arrive in different weeks (a dropped connection
 *   around Sunday midnight) is counted in both; if the second week is full, its summary waits
 *   for that week's reset. Rare, and only costs a place.
 */
export const MAX_RIDES_PER_WEEK = 10;

const DAY_MS = 24 * 60 * 60 * 1000;

/** How long a week's count is kept after the week ends, before DynamoDB deletes it itself. */
const KEEP_AFTER_WEEK_MS = 14 * DAY_MS;

/**
 * The calendar week [now] falls in: Monday 00:00 to Sunday 23:59, UTC. [key] names its row,
 * by ISO week (the week's Thursday decides its year, so 29 December can be week 1).
 */
export function weekOf(now: number): { key: string; startsAt: number; resetsAt: number } {
  const day = new Date(now);
  const daysSinceMonday = (day.getUTCDay() + 6) % 7;
  const startsAt = Date.UTC(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate()) - daysSinceMonday * DAY_MS;
  const thursday = new Date(startsAt + 3 * DAY_MS);
  const year = thursday.getUTCFullYear();
  const week = Math.floor((thursday.getTime() - Date.UTC(year, 0, 1)) / (7 * DAY_MS)) + 1;
  return { key: `RIDES#${year}-W${String(week).padStart(2, '0')}`, startsAt, resetsAt: startsAt + 7 * DAY_MS };
}

/**
 * Counts ride [activityId] towards [userId]'s rides this week. One conditional update: the
 * ride's id is added to the week's set only if the set holds fewer than [MAX_RIDES_PER_WEEK]
 * ids or already holds this one, so a retry never counts twice and two uploads at once can't
 * both take the last place. Null when counted; otherwise the time the count resets.
 */
export async function claimRideSlot(
  db: DynamoDBDocumentClient,
  table: string,
  userId: string,
  activityId: string,
  now = Date.now(),
): Promise<{ resetsAt: number } | null> {
  const week = weekOf(now);
  try {
    await db.send(
      new UpdateCommand({
        TableName: table,
        Key: { userId, period: week.key },
        UpdateExpression: 'ADD rides :ride SET expiresAt = :expiresAt',
        ConditionExpression: 'attribute_not_exists(rides) OR size(rides) < :max OR contains(rides, :id)',
        ExpressionAttributeValues: {
          ':ride': new Set([activityId]),
          ':id': activityId,
          ':max': MAX_RIDES_PER_WEEK,
          // DynamoDB's time-to-live reads seconds, not milliseconds.
          ':expiresAt': Math.floor((week.resetsAt + KEEP_AFTER_WEEK_MS) / 1000),
        },
      }),
    );
    return null;
  } catch (e) {
    if (e instanceof ConditionalCheckFailedException) return { resetsAt: week.resetsAt };
    throw e;
  }
}

/**
 * The reply when the week's rides are used up. A 429 like API Gateway's own rate limit, but
 * with [limit] in the body, so the app can tell the two apart: this one waits for [resetsAt].
 * [kind] says which limit, since a ride summary can reach either this or [EDITS].
 */
export const rideLimitReached = (resetsAt: number): ApiResult =>
  json(429, { error: 'Weekly ride limit reached', kind: 'rides', limit: MAX_RIDES_PER_WEEK, resetsAt });

/**
 * The most track downloads one account may make in a UTC day. A restore onto a new phone
 * downloads every ride once, so this is also the most rides a restore brings down in a day;
 * the rest follow the next day. A placeholder for testing, like [MAX_RIDES_PER_WEEK].
 */
export const MAX_DOWNLOADS_PER_DAY = 500;

/**
 * The most edits one account may save in a UTC day: summaries of rides that aren't new (renames,
 * deletes, a ride deleted before it synced) and every segment save, creating one included.
 * New rides and restores count towards [MAX_RIDES_PER_WEEK] instead. A placeholder for testing.
 */
export const MAX_EDITS_PER_DAY = 200;

/** Something counted per account per UTC day. [kind] names its row, its attribute and its limit reply. */
export interface DailyCount {
  readonly kind: 'downloads' | 'edits';
  readonly max: number;
  readonly error: string;
}

export const DOWNLOADS: DailyCount = { kind: 'downloads', max: MAX_DOWNLOADS_PER_DAY, error: 'Daily download limit reached' };
export const EDITS: DailyCount = { kind: 'edits', max: MAX_EDITS_PER_DAY, error: 'Daily edit limit reached' };

/** How long a day's count is kept after the day ends, before DynamoDB deletes it itself. */
const KEEP_AFTER_DAY_MS = 14 * DAY_MS;

/** The UTC day [now] falls in. [date] is how its rows are named (`DOWNLOADS#2026-11-26`). */
export function dayOf(now: number): { date: string; startsAt: number; resetsAt: number } {
  const startsAt = Math.floor(now / DAY_MS) * DAY_MS;
  return { date: new Date(startsAt).toISOString().slice(0, 10), startsAt, resetsAt: startsAt + DAY_MS };
}

/**
 * Adds one to [userId]'s [count] today. One conditional update, so two requests at once can't
 * both take the last place. Unlike rides, a repeat counts again: each request costs the same.
 * Null when counted; otherwise the time the count resets (the next 00:00 UTC).
 */
export async function claimDaily(
  db: DynamoDBDocumentClient,
  table: string,
  userId: string,
  count: DailyCount,
  now = Date.now(),
): Promise<{ resetsAt: number } | null> {
  const day = dayOf(now);
  try {
    await db.send(
      new UpdateCommand({
        TableName: table,
        Key: { userId, period: `${count.kind.toUpperCase()}#${day.date}` },
        UpdateExpression: 'ADD #count :one SET expiresAt = :expiresAt',
        ConditionExpression: 'attribute_not_exists(#count) OR #count < :max',
        // A name placeholder, so the attribute can never clash with a DynamoDB reserved word.
        ExpressionAttributeNames: { '#count': count.kind },
        ExpressionAttributeValues: {
          ':one': 1,
          ':max': count.max,
          ':expiresAt': Math.floor((day.resetsAt + KEEP_AFTER_DAY_MS) / 1000),
        },
      }),
    );
    return null;
  } catch (e) {
    if (e instanceof ConditionalCheckFailedException) return { resetsAt: day.resetsAt };
    throw e;
  }
}

/** The reply when today's [count] is used up; like [rideLimitReached], with its own [kind] and [limit]. */
export const dailyLimitReached = (count: DailyCount, resetsAt: number): ApiResult =>
  json(429, { error: count.error, kind: count.kind, limit: count.max, resetsAt });
