import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { activityIdOf, ApiEvent, ApiResult, error, json, requireEnv, userIdOf } from './shared';
import { parseSummary, toApi } from './summary';

const db = DynamoDBDocumentClient.from(new DynamoDBClient({}));

/**
 * PUT /activities/{id}: saves a ride summary for the signed-in user.
 *
 * The newer copy wins: the save only goes through if there is no stored copy, or the
 * stored one's updatedAt is not newer than this one's. Sending the same copy twice is
 * harmless. If the stored copy is newer, nothing changes and it comes back with a 409, so
 * the app can take it instead. syncedAt is stamped here, from the server's clock.
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');
  const activityId = activityIdOf(event);
  if (!activityId) return error(400, 'Invalid activity id');
  const parsed = parseSummary(event.body, event.isBase64Encoded);
  if (!parsed.ok) return error(400, parsed.message);

  const table = requireEnv('TABLE_NAME');
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
    const stored = await db.send(new GetCommand({ TableName: table, Key: { userId, activityId } }));
    return json(409, { applied: false, current: stored.Item ? toApi(stored.Item) : null });
  }
};
