import { USER, RIDE_ID } from './helpers';
import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { mockClient } from 'aws-sdk-client-mock';
import { claimRideSlot, MAX_RIDES_PER_WEEK, rideLimitReached, weekOf } from '../lambda/usage';

const db = mockClient(DynamoDBDocumentClient);
beforeEach(() => db.reset());

const utc = (iso: string) => Date.parse(iso);

describe('the week a ride counts in', () => {
  test('runs Monday 00:00 to Sunday 23:59 UTC', () => {
    // Wednesday 26 November 2026.
    const week = weekOf(utc('2026-11-26T12:00:00Z'));
    expect(week.startsAt).toBe(utc('2026-11-23T00:00:00Z'));
    expect(week.resetsAt).toBe(utc('2026-11-30T00:00:00Z'));
    expect(week.key).toBe('RIDES#2026-W48');
  });

  test('Sunday night and Monday morning are different weeks', () => {
    expect(weekOf(utc('2026-11-29T23:59:59Z')).key).toBe('RIDES#2026-W48');
    expect(weekOf(utc('2026-11-30T00:00:00Z')).key).toBe('RIDES#2026-W49');
  });

  test('takes its year from its Thursday, as ISO weeks do', () => {
    // Tuesday 29 December 2026 is in the week of Thursday 31 December: 2026-W53.
    expect(weekOf(utc('2026-12-29T08:00:00Z')).key).toBe('RIDES#2026-W53');
    // Friday 1 January 2027 belongs to that same week, not to 2027.
    expect(weekOf(utc('2027-01-01T08:00:00Z')).key).toBe('RIDES#2026-W53');
    // Monday 4 January 2027 starts 2027-W01.
    expect(weekOf(utc('2027-01-04T00:00:00Z')).key).toBe('RIDES#2027-W01');
  });
});

describe('counting a ride', () => {
  const now = utc('2026-11-26T12:00:00Z');

  test('adds the ride to the week only while there is room, or it is already there', async () => {
    db.on(UpdateCommand).resolves({});
    expect(await claimRideSlot(db as unknown as DynamoDBDocumentClient, 'usage', USER, RIDE_ID, now)).toBeNull();
    const input = db.commandCalls(UpdateCommand)[0].args[0].input;
    expect(input.TableName).toBe('usage');
    expect(input.Key).toEqual({ userId: USER, period: 'RIDES#2026-W48' });
    expect(input.UpdateExpression).toBe('ADD rides :ride SET expiresAt = :expiresAt');
    expect(input.ConditionExpression).toBe('attribute_not_exists(rides) OR size(rides) < :max OR contains(rides, :id)');
    const values = input.ExpressionAttributeValues!;
    expect(values[':ride']).toEqual(new Set([RIDE_ID]));
    expect(values[':id']).toBe(RIDE_ID);
    expect(values[':max']).toBe(MAX_RIDES_PER_WEEK);
    // Two weeks after the week ends, in seconds.
    expect(values[':expiresAt']).toBe((utc('2026-11-30T00:00:00Z') + 14 * 86_400_000) / 1000);
  });

  test('a full week gives the time it resets', async () => {
    db.on(UpdateCommand).rejects(new ConditionalCheckFailedException({ message: 'full', $metadata: {} }));
    expect(await claimRideSlot(db as unknown as DynamoDBDocumentClient, 'usage', USER, RIDE_ID, now)).toEqual({
      resetsAt: utc('2026-11-30T00:00:00Z'),
    });
  });

  test('any other failure is left to throw', async () => {
    db.on(UpdateCommand).rejects(new Error('throttled'));
    await expect(claimRideSlot(db as unknown as DynamoDBDocumentClient, 'usage', USER, RIDE_ID, now)).rejects.toThrow('throttled');
  });

  test('the limit reply is a 429 the app can tell from a rate limit', () => {
    const reply = rideLimitReached(123) as { statusCode: number; body: string };
    expect(reply.statusCode).toBe(429);
    expect(JSON.parse(reply.body)).toEqual({ error: 'Weekly ride limit reached', limit: MAX_RIDES_PER_WEEK, resetsAt: 123 });
  });
});
