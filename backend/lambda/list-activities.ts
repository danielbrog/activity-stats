import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { ApiEvent, ApiResult, error, json, requireEnv, userIdOf } from './shared';
import { toApi } from './summary';

const db = DynamoDBDocumentClient.from(new DynamoDBClient({}));

/** How many rides one page returns; the app follows nextCursor for the rest. */
export const PAGE_SIZE = 100;
const INDEX = 'bySyncedAt';

/**
 * GET /activities?since=<ms>&cursor=<cursor>: the signed-in user's rides saved after `since`
 * (server time, from an earlier pull's syncedAt), oldest first, deleted ones included so
 * deletions reach every device. Pages of up to PAGE_SIZE, with nextCursor while more remain.
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');

  const sinceText = event.queryStringParameters?.since ?? '0';
  const since = Number(sinceText);
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
      TableName: requireEnv('TABLE_NAME'),
      IndexName: INDEX,
      KeyConditionExpression: 'userId = :userId AND syncedAt > :since',
      ExpressionAttributeValues: { ':userId': userId, ':since': since },
      Limit: PAGE_SIZE,
      ExclusiveStartKey: startKey,
    }),
  );

  return json(200, {
    items: (result.Items ?? []).map(toApi),
    nextCursor: result.LastEvaluatedKey ? encodeCursor(result.LastEvaluatedKey) : null,
  });
};

export const encodeCursor = (key: Record<string, unknown>) =>
  Buffer.from(JSON.stringify(key), 'utf8').toString('base64url');

export function decodeCursor(cursor: string): Record<string, unknown> | undefined {
  try {
    const value: unknown = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8'));
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}
