import { body, event, SEGMENT_ID, status, USER } from './helpers';
import { DynamoDBDocumentClient, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { mockClient } from 'aws-sdk-client-mock';
import { encodeCursor } from '../lambda/shared';
import { handler, PAGE_SIZE } from '../lambda/list-segments';

const db = mockClient(DynamoDBDocumentClient);
beforeEach(() => db.reset());

const list = (query: Record<string, string> = {}, sub: string | null = USER) =>
  handler(event({ queryStringParameters: query }, sub));

describe('GET /segments', () => {
  test("queries only the caller's items saved after since, labelled by kind, without table keys", async () => {
    db.on(QueryCommand).resolves({
      Items: [
        { userId: USER, sk: `SEG#${SEGMENT_ID}`, name: 'Bayview climb', syncedAt: 10 },
        { userId: USER, sk: `SEG#${SEGMENT_ID}#REVIEW`, status: 'SUBMITTED', queue: 'SUBMITTED', syncedAt: 11 },
      ],
    });
    const result = await list({ since: '5' });

    expect(status(result)).toBe(200);
    const input = db.commandCalls(QueryCommand)[0].args[0].input;
    expect(input).toMatchObject({
      TableName: 'test-segments',
      IndexName: 'bySyncedAt',
      KeyConditionExpression: 'userId = :userId AND syncedAt > :since',
      ExpressionAttributeValues: { ':userId': USER, ':since': 5 },
      Limit: PAGE_SIZE,
    });
    expect(body(result)).toEqual({
      items: [
        { kind: 'segment', id: SEGMENT_ID, name: 'Bayview climb', syncedAt: 10 },
        // The review queue marker is the server's own bookkeeping: never sent.
        { kind: 'review', segmentId: SEGMENT_ID, status: 'SUBMITTED', syncedAt: 11 },
      ],
      nextCursor: null,
    });
  });

  test('only the listed fields go out: odd keys are dropped and admin-only fields stay on the server', async () => {
    db.on(QueryCommand).resolves({
      Items: [
        { userId: USER, sk: 'OTHER#x', name: 'not a segment', syncedAt: 1 },
        { userId: USER, sk: `SEG#${SEGMENT_ID}#EFFORT`, name: 'not a segment either', syncedAt: 2 },
        {
          userId: USER, sk: `SEG#${SEGMENT_ID}#REVIEW`, status: 'PUBLIC', decidedBy: 'admin-id',
          possibleDuplicateOf: 'v-2', verifiedSegmentId: 'v-1', syncedAt: 3,
        },
        { userId: USER, sk: `SEG#${SEGMENT_ID}`, name: 'Bayview climb', secret: 'x', syncedAt: 4 },
      ],
    });
    expect(body(await list()).items).toEqual([
      { kind: 'review', segmentId: SEGMENT_ID, status: 'PUBLIC', verifiedSegmentId: 'v-1', syncedAt: 3 },
      { kind: 'segment', id: SEGMENT_ID, name: 'Bayview climb', syncedAt: 4 },
    ]);
  });

  test('pages with a cursor that continues where the last page stopped', async () => {
    const lastKey = { userId: USER, sk: `SEG#${SEGMENT_ID}`, syncedAt: 10 };
    db.on(QueryCommand).resolves({ Items: [], LastEvaluatedKey: lastKey });
    const first = body(await list());
    expect(first.nextCursor).toBe(encodeCursor(lastKey));

    await list({ cursor: first.nextCursor });
    expect(db.commandCalls(QueryCommand)[1].args[0].input.ExclusiveStartKey).toEqual(lastKey);
  });

  test("refuses a cursor from someone else's listing, or one that is garbage", async () => {
    const other = encodeCursor({ userId: 'someone-else', sk: 'SEG#x', syncedAt: 1 });
    expect(status(await list({ cursor: other }))).toBe(400);
    expect(status(await list({ cursor: 'not-a-cursor' }))).toBe(400);
    expect(db.commandCalls(QueryCommand)).toHaveLength(0);
  });

  test('rejects a bad since and a missing user', async () => {
    expect(status(await list({ since: '-1' }))).toBe(400);
    expect(status(await list({ since: 'soon' }))).toBe(400);
    expect(status(await list({}, null))).toBe(401);
  });
});
