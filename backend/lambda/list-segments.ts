import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { segmentItemToApi } from './segment';
import { ApiEvent, ApiResult, decodeCursor, encodeCursor, error, json, requireEnv, userIdOf } from './shared';

const db = DynamoDBDocumentClient.from(new DynamoDBClient({}));

/** How many items one page returns; the app follows nextCursor for the rest. */
export const PAGE_SIZE = 100;
const INDEX = 'bySyncedAt';

/**
 * GET /segments?since=<ms>&cursor=<cursor>: the signed-in user's segment items saved after
 * `since` (server time, from an earlier pull's syncedAt), oldest first, deleted ones included
 * so deletions reach every device. Both item types come back, labelled: a segment
 * (`kind: 'segment'`) and its review (`kind: 'review'`). Pages of up to PAGE_SIZE, with
 * nextCursor while more remain.
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');

  const since = Number(event.queryStringParameters?.since ?? '0');
  if (!Number.isSafeInteger(since) || since < 0) return error(400, 'Invalid since');

  let startKey: Record<string, unknown> | undefined;
  const cursor = event.queryStringParameters?.cursor;
  if (cursor) {
    startKey = decodeCursor(cursor);
    // A cursor only ever continues the caller's own listing.
    if (!startKey || startKey.userId !== userId) return error(400, 'Invalid cursor');
  }

  const result = await db.send(
    new QueryCommand({
      TableName: requireEnv('SEGMENTS_TABLE_NAME'),
      IndexName: INDEX,
      KeyConditionExpression: 'userId = :userId AND syncedAt > :since',
      ExpressionAttributeValues: { ':userId': userId, ':since': since },
      Limit: PAGE_SIZE,
      ExclusiveStartKey: startKey,
    }),
  );

  return json(200, {
    items: (result.Items ?? []).map(segmentItemToApi).filter((item) => item !== null),
    nextCursor: result.LastEvaluatedKey ? encodeCursor(result.LastEvaluatedKey) : null,
  });
};
