import { body, event, segment, SEGMENT_ID, status, USER } from './helpers';
import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';
import { mockClient } from 'aws-sdk-client-mock';
import { handler } from '../lambda/put-segment';

const db = mockClient(DynamoDBDocumentClient);
beforeEach(() => db.reset());

const put = (data: unknown, id = SEGMENT_ID) =>
  handler(event({ pathParameters: { id }, body: JSON.stringify(data) }));

const stale = () => new ConditionalCheckFailedException({ message: 'stale', $metadata: {} });

describe('PUT /segments/{id}', () => {
  test('saves the segment item under the user from the token, with a server syncedAt', async () => {
    db.on(PutCommand).resolves({});
    const before = Date.now();
    const result = await put(segment());

    expect(status(result)).toBe(200);
    const input = db.commandCalls(PutCommand)[0].args[0].input;
    expect(input.TableName).toBe('test-segments');
    expect(input.Item).toMatchObject({ userId: USER, sk: `SEG#${SEGMENT_ID}`, name: 'Bayview climb', sport: 'ride' });
    expect(input.Item!.syncedAt).toBeGreaterThanOrEqual(before);
    expect(input.ConditionExpression).toBe(
      'attribute_not_exists(sk) OR (updatedAt <= :updatedAt AND #path = :path AND sport = :sport)',
    );
  });

  test('drops review fields and anything unknown, so a phone can never set its own status', async () => {
    db.on(PutCommand).resolves({});
    await put(segment({ userId: 'someone-else', status: 'PUBLIC', verifiedSegmentId: 'v-1', queue: 'SUBMITTED', admin: true }));
    const item = db.commandCalls(PutCommand)[0].args[0].input.Item!;
    expect(item.userId).toBe(USER);
    for (const field of ['status', 'verifiedSegmentId', 'queue', 'admin']) expect(item).not.toHaveProperty(field);
  });

  test('returns the stored copy with a 409 when it is newer', async () => {
    db.on(PutCommand).rejects(stale());
    db.on(GetCommand).resolves({
      Item: { userId: USER, sk: `SEG#${SEGMENT_ID}`, ...segment({ name: 'Renamed', updatedAt: 9_000 }), syncedAt: 1 },
    });
    const result = await put(segment({ updatedAt: 5_000 }));

    expect(status(result)).toBe(409);
    expect(body(result).applied).toBe(false);
    expect(body(result).current).toMatchObject({ kind: 'segment', id: SEGMENT_ID, name: 'Renamed', updatedAt: 9_000 });
    expect(body(result).current).not.toHaveProperty('userId');
    expect(body(result).current).not.toHaveProperty('sk');
  });

  test("refuses a copy that changes the segment's line or sport, and hands back the stored copy", async () => {
    db.on(PutCommand).rejects(stale());
    db.on(GetCommand).resolves({ Item: { userId: USER, sk: `SEG#${SEGMENT_ID}`, ...segment({ updatedAt: 1 }) } });
    const result = await put(segment({ path: '_ulLnnqC_mqNvxq`@' }));
    expect(status(result)).toBe(400);
    expect(body(result).current).toMatchObject({ kind: 'segment', id: SEGMENT_ID, path: segment().path });
    expect(status(await put(segment({ sport: 'run' })))).toBe(400);
  });

  test('a changed line is refused even when the stored copy is also newer', async () => {
    db.on(PutCommand).rejects(stale());
    db.on(GetCommand).resolves({ Item: { userId: USER, sk: `SEG#${SEGMENT_ID}`, ...segment({ updatedAt: 9_999_999_999_999 }) } });
    expect(status(await put(segment({ path: '_ulLnnqC', updatedAt: 1_000 })))).toBe(400);
  });

  test('reads the stored copy up to date, and treats a missing one as a server error', async () => {
    db.on(PutCommand).rejects(stale());
    db.on(GetCommand).resolves({});
    await expect(put(segment())).rejects.toThrow('missing');
    expect(db.commandCalls(GetCommand)[0].args[0].input.ConsistentRead).toBe(true);
  });

  test('an id that could reach the review item is refused', async () => {
    for (const id of ['x#REVIEW', 'x%23REVIEW', `${SEGMENT_ID}#REVIEW`]) expect(status(await put(segment(), id))).toBe(400);
    expect(db.commandCalls(PutCommand)).toHaveLength(0);
  });

  test('a time more than a day ahead is refused, so a wrong clock cannot lock a segment', async () => {
    const tooFar = Date.now() + 2 * 24 * 60 * 60 * 1000;
    expect(status(await put(segment({ updatedAt: tooFar })))).toBe(400);
    expect(status(await put(segment({ createdAt: tooFar })))).toBe(400);
    expect(db.commandCalls(PutCommand)).toHaveLength(0);
  });

  test('the name is saved trimmed', async () => {
    db.on(PutCommand).resolves({});
    await put(segment({ name: '  Bayview climb  ' }));
    expect(db.commandCalls(PutCommand)[0].args[0].input.Item!.name).toBe('Bayview climb');
  });

  test('a newer copy without deletedAt brings a deleted segment back (how Undo syncs)', async () => {
    db.on(PutCommand).resolves({});
    expect(status(await put(segment({ deletedAt: null, updatedAt: 1_794_070_000_000 })))).toBe(200);
    // The condition only compares updatedAt, line and sport: deletedAt doesn't block it.
    expect(db.commandCalls(PutCommand)[0].args[0].input.ConditionExpression).not.toContain('deletedAt');
    expect(db.commandCalls(PutCommand)[0].args[0].input.Item!.deletedAt).toBeNull();
  });

  test('rejects bad input without touching the table', async () => {
    const bad = [
      segment({ name: '   ' }),
      segment({ name: 'x'.repeat(61) }),
      segment({ path: '' }),
      segment({ path: 'has spaces' }),
      segment({ path: '?'.repeat(50_001) }),
      segment({ distanceMeters: 99 }),
      segment({ distanceMeters: 100_001 }),
      segment({ sport: 'swim' }),
      segment({ updatedAt: 0 }),
      segment({ createdAt: 'yesterday' }),
      segment({ deletedAt: -1 }),
    ];
    for (const data of bad) expect(status(await put(data))).toBe(400);
    expect(status(await put(segment(), '../other-user'))).toBe(400);
    expect(status(await handler(event({ pathParameters: { id: SEGMENT_ID }, body: 'not json' })))).toBe(400);
    expect(db.commandCalls(PutCommand)).toHaveLength(0);
  });

  test('accepts a deleted segment and every activity type', async () => {
    db.on(PutCommand).resolves({});
    expect(status(await put(segment({ deletedAt: 1_794_070_000_000 })))).toBe(200);
    for (const sport of ['ride', 'run', 'walk']) expect(status(await put(segment({ sport })))).toBe(200);
  });

  test('refuses a request with no user in the token', async () => {
    const result = await handler(event({ pathParameters: { id: SEGMENT_ID }, body: JSON.stringify(segment()) }, null));
    expect(status(result)).toBe(401);
  });
});
