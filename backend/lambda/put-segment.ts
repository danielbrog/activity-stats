import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { parseSegment, segmentItemToApi, segmentKey } from './segment';
import { pathIdOf, ApiEvent, ApiResult, error, json, requireEnv, userIdOf } from './shared';
import { claimDaily, dailyLimitReached, EDITS } from './usage';

const db = DynamoDBDocumentClient.from(new DynamoDBClient({}));

/**
 * PUT /segments/{id}: saves a personal segment for the signed-in user (its `SEG#<id>` item
 * only; its review item is never touched here).
 *
 * The newer copy wins, as for rides: the save only goes through if there is no stored copy,
 * or the stored one's updatedAt is not newer. So a newer copy with no deletedAt brings a
 * deleted segment back (that's how Undo syncs). A segment's line and sport are fixed once
 * created: a copy that tries to change them is refused with a 400. If the stored copy is
 * newer, nothing changes and it comes back with a 409. Both refusals carry the stored copy,
 * so the phone can take it. syncedAt is stamped here, from the server's clock.
 *
 * Every save, creating a segment included, counts towards the day's [EDITS], before anything
 * is written: past the limit the reply is a 429 and nothing is stored.
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');
  const segmentId = pathIdOf(event);
  if (!segmentId) return error(400, 'Invalid segment id');
  const parsed = parseSegment(event.body, event.isBase64Encoded);
  if (!parsed.ok) return error(400, parsed.message);

  const limited = await claimDaily(db, requireEnv('USAGE_TABLE_NAME'), userId, EDITS);
  if (limited) return dailyLimitReached(EDITS, limited.resetsAt);

  const table = requireEnv('SEGMENTS_TABLE_NAME');
  const sk = segmentKey(segmentId);
  const segment = parsed.value;
  const syncedAt = Date.now();
  try {
    await db.send(
      new PutCommand({
        TableName: table,
        Item: { ...segment, userId, sk, syncedAt },
        ConditionExpression:
          'attribute_not_exists(sk) OR (updatedAt <= :updatedAt AND #path = :path AND sport = :sport)',
        ExpressionAttributeNames: { '#path': 'path' },
        ExpressionAttributeValues: { ':updatedAt': segment.updatedAt, ':path': segment.path, ':sport': segment.sport },
      }),
    );
    return json(200, { applied: true, syncedAt });
  } catch (e) {
    if (!(e instanceof ConditionalCheckFailedException)) throw e;
    // An up-to-date read: the copy that just won must come back, not an older one. There are
    // no hard deletes, so a failed condition always has a stored copy behind it.
    const stored = (await db.send(new GetCommand({ TableName: table, Key: { userId, sk }, ConsistentRead: true }))).Item;
    if (!stored) throw new Error(`Condition failed but ${sk} is missing`);
    const current = segmentItemToApi(stored);
    if (stored.path !== segment.path || stored.sport !== segment.sport) {
      return json(400, { error: "A segment's line and sport can't change", applied: false, current });
    }
    return json(409, { applied: false, current });
  }
};
