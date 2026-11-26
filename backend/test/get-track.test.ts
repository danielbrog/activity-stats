import { event, RIDE_ID, status, USER } from './helpers';
import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { GetObjectCommand, NoSuchKey, S3Client } from '@aws-sdk/client-s3';
import { DynamoDBDocumentClient, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { mockClient } from 'aws-sdk-client-mock';
import { handler, MAX_DOWNLOAD_BYTES } from '../lambda/get-track';

const s3 = mockClient(S3Client);
const db = mockClient(DynamoDBDocumentClient);
beforeEach(() => {
  s3.reset();
  db.reset();
  db.on(UpdateCommand).resolves({});
});

const withId = (id = RIDE_ID, sub: string | null = USER) => event({ pathParameters: { id } }, sub);

/** A stored file as GetObject hands it back: its size, and a body that can be read once. */
function stored(bytes: Uint8Array, size = bytes.length) {
  const destroy = jest.fn();
  return {
    reply: { ContentLength: size, Body: { transformToByteArray: async () => bytes, destroy } },
    destroy,
  };
}

type Reply = { statusCode: number; body: string; headers: Record<string, string>; isBase64Encoded?: boolean };

describe('downloading a track', () => {
  const file = new Uint8Array([0x1f, 0x8b, 8, 0, 1, 2, 3]);

  test("returns the signed-in user's own file, in base64, and counts it", async () => {
    s3.on(GetObjectCommand).resolves(stored(file).reply as never);
    const reply = (await handler(withId())) as Reply;
    expect(reply.statusCode).toBe(200);
    expect(reply.isBase64Encoded).toBe(true);
    expect(reply.headers['content-type']).toBe('application/gzip');
    expect(new Uint8Array(Buffer.from(reply.body, 'base64'))).toEqual(file);
    expect(s3.commandCalls(GetObjectCommand)[0].args[0].input).toEqual({
      Bucket: 'test-bucket',
      Key: `tracks/${USER}/${RIDE_ID}.json.gz`,
    });
    const count = db.commandCalls(UpdateCommand);
    expect(count).toHaveLength(1);
    expect(count[0].args[0].input.TableName).toBe('test-usage');
    expect(count[0].args[0].input.Key!.userId).toBe(USER);
  });

  test('a file not uploaded yet is a 404 and is not counted', async () => {
    s3.on(GetObjectCommand).rejects(new NoSuchKey({ message: 'nope', $metadata: {} }));
    expect(status(await handler(withId()))).toBe(404);
    expect(db.commandCalls(UpdateCommand)).toHaveLength(0);
  });

  test("past the day's limit it is a 429 with the reset time, and the file is not read", async () => {
    const kept = stored(file);
    s3.on(GetObjectCommand).resolves(kept.reply as never);
    db.on(UpdateCommand).rejects(new ConditionalCheckFailedException({ message: 'full', $metadata: {} }));
    const reply = (await handler(withId())) as Reply;
    expect(reply.statusCode).toBe(429);
    expect(reply.isBase64Encoded).toBeFalsy();
    const parsed = JSON.parse(reply.body);
    expect(parsed.limit).toBe(500);
    // The next 00:00 UTC.
    expect(parsed.resetsAt % 86_400_000).toBe(0);
    expect(parsed.resetsAt).toBeGreaterThan(Date.now());
    expect(kept.destroy).toHaveBeenCalled();
  });

  test('a file whose size S3 did not give is refused, and not counted', async () => {
    const unknown = stored(file);
    s3.on(GetObjectCommand).resolves({ ...unknown.reply, ContentLength: undefined } as never);
    expect(status(await handler(withId()))).toBe(413);
    expect(unknown.destroy).toHaveBeenCalled();
    expect(db.commandCalls(UpdateCommand)).toHaveLength(0);
  });

  test('a failed count closes the file and is left to throw', async () => {
    const kept = stored(file);
    s3.on(GetObjectCommand).resolves(kept.reply as never);
    db.on(UpdateCommand).rejects(new Error('throttled'));
    await expect(handler(withId())).rejects.toThrow('throttled');
    expect(kept.destroy).toHaveBeenCalled();
  });

  test('a file too big for a Lambda reply is refused unread, and not counted', async () => {
    const big = stored(file, MAX_DOWNLOAD_BYTES + 1);
    s3.on(GetObjectCommand).resolves(big.reply as never);
    expect(status(await handler(withId()))).toBe(413);
    expect(big.destroy).toHaveBeenCalled();
    expect(db.commandCalls(UpdateCommand)).toHaveLength(0);
  });

  test('other S3 failures are left to throw, so the alarm sees them', async () => {
    s3.on(GetObjectCommand).rejects(new Error('S3 down'));
    await expect(handler(withId())).rejects.toThrow('S3 down');
  });

  test('bad ids and missing users are refused before anything is read', async () => {
    expect(status(await handler(withId('../../etc')))).toBe(400);
    expect(status(await handler(withId('a/b')))).toBe(400);
    expect(status(await handler(withId(RIDE_ID, null)))).toBe(401);
    expect(s3.commandCalls(GetObjectCommand)).toHaveLength(0);
    expect(db.commandCalls(UpdateCommand)).toHaveLength(0);
  });
});
