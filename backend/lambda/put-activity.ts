import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { pathIdOf, ApiEvent, ApiResult, error, json, requireEnv, userIdOf } from './shared';
import { parseSummary, toApi } from './summary';
import { claimDaily, claimRideSlot, dailyLimitReached, EDITS, rideLimitReached } from './usage';

const db = DynamoDBDocumentClient.from(new DynamoDBClient({}));

/**
 * PUT /activities/{id}: saves a ride summary for the signed-in user.
 *
 * The newer copy wins: the save only goes through if there is no stored copy, or the
 * stored one's updatedAt is not newer than this one's. Sending the same copy twice stores
 * nothing new, but counts as an edit again (see below). If the stored copy is newer, nothing changes and it comes back with a 409, so
 * the app can take it instead. syncedAt is stamped here, from the server's clock.
 *
 * A summary that makes a live ride (none stored yet, or only a deleted copy) counts towards
 * the week's limit like a track does (see claimRideSlot), so sending summaries alone can't get
 * round it. Every other summary is an edit: a rename or delete of a stored ride, or a ride
 * deleted before it synced. Those count towards the day's [EDITS] instead. Either way it is
 * counted before the save, so one past its limit stores nothing (a 429).
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');
  const activityId = pathIdOf(event);
  if (!activityId) return error(400, 'Invalid activity id');
  const parsed = parseSummary(event.body, event.isBase64Encoded);
  if (!parsed.ok) return error(400, parsed.message);

  const table = requireEnv('TABLE_NAME');
  const stored = (
    await db.send(
      new GetCommand({ TableName: table, Key: { userId, activityId }, ProjectionExpression: 'activityId, deletedAt' }),
    )
  )?.Item;
  const becomesLive = parsed.value.deletedAt === null && (!stored || stored.deletedAt != null);
  const usageTable = requireEnv('USAGE_TABLE_NAME');
  if (becomesLive) {
    // Usually already counted when its track came in; counting again is a no-op.
    const limited = await claimRideSlot(db, usageTable, userId, activityId);
    if (limited) return rideLimitReached(limited.resetsAt);
  } else {
    const limited = await claimDaily(db, usageTable, userId, EDITS);
    if (limited) return dailyLimitReached(EDITS, limited.resetsAt);
  }

  const syncedAt = Date.now();
  try {
    await db.send(
      new PutCommand({
        TableName: table,
        Item: { ...parsed.value, userId, activityId, syncedAt },
        ConditionExpression: 'attribute_not_exists(activityId) OR updatedAt <= :updatedAt',
        ExpressionAttributeValues: { ':updatedAt': parsed.value.updatedAt },
      }),
    );
    return json(200, { applied: true, syncedAt });
  } catch (e) {
    if (!(e instanceof ConditionalCheckFailedException)) throw e;
    // An up-to-date read: the copy that just won must come back, not an older one. There are
    // no hard deletes, so a failed condition always has a stored copy behind it.
    const stored = (await db.send(new GetCommand({ TableName: table, Key: { userId, activityId }, ConsistentRead: true }))).Item;
    if (!stored) throw new Error(`Condition failed but ${activityId} is missing`);
    return json(409, { applied: false, current: toApi(stored) });
  }
};
