import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { GetObjectCommand, NoSuchKey, S3Client } from '@aws-sdk/client-s3';
import { DynamoDBDocumentClient } from '@aws-sdk/lib-dynamodb';
import { pathIdOf, ApiEvent, ApiResult, error, requireEnv, TRACK_CONTENT_TYPE, trackKey, userIdOf } from './shared';
import { claimDownload, downloadLimitReached } from './usage';

const s3 = new S3Client({});
const db = DynamoDBDocumentClient.from(new DynamoDBClient({}));

/**
 * The largest file this returns. A Lambda reply can be at most 6 MB, and the file goes back
 * in base64, a third larger. Uploads are checked to 1 MB, so only a file stored before that
 * check could be bigger; the app skips it (a 413 is a refusal, not worth retrying).
 */
export const MAX_DOWNLOAD_BYTES = 4_000_000;

/**
 * GET /activities/{id}/track: the ride's track file itself (gzipped), or 404 if it hasn't
 * been uploaded yet. Only ever the signed-in user's own file. Replaces a presigned S3 link,
 * which could be fetched any number of times without the server knowing; going through here
 * lets each download count towards the day's [MAX_DOWNLOADS_PER_DAY]. A missing file isn't
 * counted; past the limit the reply is a 429.
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');
  const activityId = pathIdOf(event);
  if (!activityId) return error(400, 'Invalid activity id');

  let file;
  try {
    file = await s3.send(new GetObjectCommand({ Bucket: requireEnv('BUCKET_NAME'), Key: trackKey(userId, activityId) }));
  } catch (e) {
    if (e instanceof NoSuchKey || (e as { name?: string }).name === 'NoSuchKey') return error(404, 'No track file yet');
    throw e;
  }
  // A file not sent is closed unread, rather than left holding the connection to S3.
  const close = () => (file.Body as { destroy?: () => void } | undefined)?.destroy?.();
  // S3 always gives the size; without it the reply could pass Lambda's limit, so refuse.
  if (file.ContentLength === undefined || file.ContentLength > MAX_DOWNLOAD_BYTES) {
    close();
    return error(413, `Track larger than ${MAX_DOWNLOAD_BYTES} bytes`);
  }

  // Counted before reading, so a download past the limit doesn't fetch the file for nothing.
  let limited;
  try {
    limited = await claimDownload(db, requireEnv('USAGE_TABLE_NAME'), userId);
  } catch (e) {
    close();
    throw e;
  }
  if (limited) {
    close();
    return downloadLimitReached(limited.resetsAt);
  }
  const bytes = await file.Body!.transformToByteArray();

  // API Gateway turns a base64 body back into the bytes before sending it to the app.
  return {
    statusCode: 200,
    headers: { 'content-type': TRACK_CONTENT_TYPE },
    body: Buffer.from(bytes).toString('base64'),
    isBase64Encoded: true,
  };
};
