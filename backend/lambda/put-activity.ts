import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { pathIdOf, ApiEvent, ApiResult, error, json, requireEnv, userIdOf } from './shared';
import { parseSummary, toApi } from './summary';
import { claimRideSlot, rideLimitReached } from './usage';

const db = DynamoDBDocumentClient.from(new DynamoDBClient({}));

/**
 * PUT /activities/{id}: saves a ride summary for the signed-in user.
 *
 * The newer copy wins: the save only goes through if there is no stored copy, or the
 * stored one's updatedAt is not newer than this one's. Sending the same copy twice is
 * harmless. If the stored copy is newer, nothing changes and it comes back with a 409, so
 * the app can take it instead. syncedAt is stamped here, from the server's clock.
 *
 * A summary that makes a live ride (none stored yet, or only a deleted copy) counts towards
 * the week's limit like a track does (see claimRideSlot), so sending summaries alone can't get
 * round it. Edits to a stored ride aren't counted, and neither is a ride deleted before it
 * synced: it is only a record that the ride is gone.
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
  if (becomesLive) {
    // Usually already counted when its track came in; counting again is a no-op.
    const limited = await claimRideSlot(db, requireEnv('USAGE_TABLE_NAME'), userId, activityId);
    if (limited) return rideLimitReached(limited.resetsAt);
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
