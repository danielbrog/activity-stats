import { body, event, RIDE_ID, status, summary, USER } from './helpers';
import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { mockClient } from 'aws-sdk-client-mock';
import { handler } from '../lambda/put-activity';

const db = mockClient(DynamoDBDocumentClient);
beforeEach(() => db.reset());

const put = (data: unknown, id = RIDE_ID) =>
  handler(event({ pathParameters: { id }, body: JSON.stringify(data) }));

describe('PUT /activities/{id}', () => {
  test('saves the summary under the user from the token, with a server syncedAt', async () => {
    db.on(PutCommand).resolves({});
    const before = Date.now();
    const result = await put(summary());

    expect(status(result)).toBe(200);
    expect(body(result).applied).toBe(true);
    const input = db.commandCalls(PutCommand)[0].args[0].input;
    expect(input.Item).toMatchObject({ userId: USER, activityId: RIDE_ID, title: 'Morning ride' });
    expect(input.Item!.syncedAt).toBeGreaterThanOrEqual(before);
    expect(input.ConditionExpression).toBe('attribute_not_exists(activityId) OR updatedAt <= :updatedAt');
  });

  test('ignores a userId or other unknown fields in the body', async () => {
    db.on(PutCommand).resolves({});
    await put(summary({ userId: 'someone-else', syncState: 'SYNCED', admin: true }));
    const item = db.commandCalls(PutCommand)[0].args[0].input.Item!;
    expect(item.userId).toBe(USER);
    expect(item).not.toHaveProperty('admin');
    expect(item).not.toHaveProperty('syncState');
  });

  test('returns the stored copy with a 409 when it is newer', async () => {
    db.on(PutCommand).rejects(new ConditionalCheckFailedException({ message: 'stale', $metadata: {} }));
    db.on(GetCommand).resolves({ Item: { userId: USER, activityId: RIDE_ID, title: 'Renamed', updatedAt: 9 } });
    const result = await put(summary({ updatedAt: 5 }));

    expect(status(result)).toBe(409);
    expect(body(result)).toEqual({ applied: false, current: { id: RIDE_ID, title: 'Renamed', updatedAt: 9 } });
    expect(db.commandCalls(GetCommand)[0].args[0].input.ConsistentRead).toBe(true);
  });

  test('a failed condition with no stored copy is a server error, not an empty reply', async () => {
    db.on(PutCommand).rejects(new ConditionalCheckFailedException({ message: 'stale', $metadata: {} }));
    db.on(GetCommand).resolves({});
    await expect(put(summary())).rejects.toThrow('missing');
  });

  test('rejects bad input without touching the table', async () => {
    expect(status(await put(summary({ type: 'swim' })))).toBe(400);
    expect(status(await put(summary({ distanceMeters: -1 })))).toBe(400);
    expect(status(await put(summary({ title: '   ' })))).toBe(400);
    expect(status(await put(summary({ title: 'x'.repeat(201) })))).toBe(400);
    expect(status(await put(summary({ updatedAt: 'yesterday' })))).toBe(400);
    expect(status(await put(summary(), '../other-user'))).toBe(400);
    expect(status(await handler(event({ pathParameters: { id: RIDE_ID }, body: 'not json' })))).toBe(400);
    expect(db.commandCalls(PutCommand)).toHaveLength(0);
  });

  test('accepts a deleted ride and a ride without elevation', async () => {
    db.on(PutCommand).resolves({});
    expect(status(await put(summary({ deletedAt: 1_794_070_000_000, elevationGainMeters: null })))).toBe(200);
  });

  test('refuses a request with no user in the token', async () => {
    const result = await handler(event({ pathParameters: { id: RIDE_ID }, body: JSON.stringify(summary()) }, null));
    expect(status(result)).toBe(401);
  });
});
