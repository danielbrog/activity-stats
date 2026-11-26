import { PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { pathIdOf, ApiEvent, ApiResult, error, json, requireEnv, trackKey, userIdOf } from './shared';
import { MAX_TRACK_BYTES, parseTrack } from './track';

const s3 = new S3Client({});

/** The type a stored track file has. */
export const TRACK_CONTENT_TYPE = 'application/gzip';

/**
 * PUT /activities/{id}/track: saves the ride's track file. The app sends the gzipped file as
 * the request body; it is checked (see [parseTrack]) and a clean copy is stored in the
 * signed-in user's own folder. Replaces the old direct upload to S3, whose files nothing
 * checked: any content, any size.
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');
  const activityId = pathIdOf(event);
  if (!activityId) return error(400, 'Invalid activity id');

  const body = event.body ?? '';
  // Base64 is a third larger than the bytes it holds: refuse an oversized file before decoding it.
  if (body.length > Math.ceil(MAX_TRACK_BYTES / 3) * 4 + 4) return error(413, `Track larger than ${MAX_TRACK_BYTES} bytes`);
  // API Gateway hands binary bodies (application/gzip) over in base64; anything else isn't a track.
  if (!event.isBase64Encoded) return error(400, 'Not a track file');

  const parsed = parseTrack(Buffer.from(body, 'base64'));
  if (!parsed.ok) return error(parsed.status, parsed.message);

  await s3.send(
    new PutObjectCommand({
      Bucket: requireEnv('BUCKET_NAME'),
      Key: trackKey(userId, activityId),
      Body: parsed.gzipped,
      ContentType: TRACK_CONTENT_TYPE,
    }),
  );
  return json(200, { saved: true, fixes: parsed.fixes });
};
