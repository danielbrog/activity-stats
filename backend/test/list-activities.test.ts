import { body, event, RIDE_ID, status, USER } from './helpers';
import { DynamoDBDocumentClient, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { mockClient } from 'aws-sdk-client-mock';
import { encodeCursor, handler, PAGE_SIZE } from '../lambda/list-activities';

const db = mockClient(DynamoDBDocumentClient);
beforeEach(() => db.reset());

const list = (query: Record<string, string> = {}) => handler(event({ queryStringParameters: query }));

describe('GET /activities', () => {
  test("queries only the caller's rides saved after since, and returns them without table keys", async () => {
    db.on(QueryCommand).resolves({
      Items: [{ userId: USER, activityId: RIDE_ID, title: 'Morning ride', syncedAt: 200 }],
    });
    const result = await list({ since: '100' });

    expect(status(result)).toBe(200);
    expect(body(result)).toEqual({ items: [{ id: RIDE_ID, title: 'Morning ride', syncedAt: 200 }], nextCursor: null });
    const input = db.commandCalls(QueryCommand)[0].args[0].input;
    expect(input.IndexName).toBe('bySyncedAt');
    expect(input.KeyConditionExpression).toBe('userId = :userId AND syncedAt > :since');
    expect(input.ExpressionAttributeValues).toEqual({ ':userId': USER, ':since': 100 });
    expect(input.Limit).toBe(PAGE_SIZE);
  });

  test('pages with a cursor that continues where the last page stopped', async () => {
    const lastKey = { userId: USER, activityId: RIDE_ID, syncedAt: 300 };
    db.on(QueryCommand).resolvesOnce({ Items: [], LastEvaluatedKey: lastKey }).resolves({ Items: [] });

    const first = body(await list());
    expect(first.nextCursor).toBe(encodeCursor(lastKey));
    await list({ cursor: first.nextCursor });
    expect(db.commandCalls(QueryCommand)[1].args[0].input.ExclusiveStartKey).toEqual(lastKey);
  });

  test("refuses a cursor from someone else's listing, or one that is garbage", async () => {
    const other = encodeCursor({ userId: 'someone-else', activityId: 'x', syncedAt: 1 });
    expect(status(await list({ cursor: other }))).toBe(400);
    expect(status(await list({ cursor: '%%%' }))).toBe(400);
    expect(db.commandCalls(QueryCommand)).toHaveLength(0);
  });

  test('rejects a bad since and a missing user', async () => {
    expect(status(await list({ since: '-5' }))).toBe(400);
    expect(status(await list({ since: 'soon' }))).toBe(400);
    expect(status(await handler(event({}, null)))).toBe(401);
  });
});
