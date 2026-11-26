import { body, event, RIDE_ID, status, USER } from './helpers';
import { HeadObjectCommand, NotFound, S3Client } from '@aws-sdk/client-s3';
import { mockClient } from 'aws-sdk-client-mock';
import { handler as downloadHandler } from '../lambda/track-download-url';

const s3 = mockClient(S3Client);
beforeEach(() => s3.reset());

const withId = (id = RIDE_ID, sub: string = USER) => event({ pathParameters: { id } }, sub);

describe('track download links', () => {
  test('the download link is given only once the file exists', async () => {
    s3.on(HeadObjectCommand).resolves({});
    const found = await downloadHandler(withId());
    expect(status(found)).toBe(200);
    expect(decodeURIComponent(new URL(body(found).url).pathname)).toBe(`/tracks/${USER}/${RIDE_ID}.json.gz`);
    expect(s3.commandCalls(HeadObjectCommand)[0].args[0].input).toEqual({
      Bucket: 'test-bucket',
      Key: `tracks/${USER}/${RIDE_ID}.json.gz`,
    });

    s3.on(HeadObjectCommand).rejects(new NotFound({ message: 'nope', $metadata: {} }));
    expect(status(await downloadHandler(withId()))).toBe(404);
  });

  test('bad ids and missing users are refused before anything is signed', async () => {
    expect(status(await downloadHandler(withId('../../etc')))).toBe(400);
    expect(status(await downloadHandler(withId('a/b')))).toBe(400);
    expect(status(await downloadHandler(event({ pathParameters: { id: RIDE_ID } }, null)))).toBe(401);
    expect(s3.commandCalls(HeadObjectCommand)).toHaveLength(0);
  });
});
