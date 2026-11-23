import { GetObjectCommand, HeadObjectCommand, NotFound, S3Client } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { pathIdOf, ApiEvent, ApiResult, error, json, LINK_SECONDS, requireEnv, trackKey, userIdOf } from './shared';

const s3 = new S3Client({});

/**
 * GET /activities/{id}/track: a link to download the ride's track file for the next few
 * minutes, or 404 if it hasn't been uploaded yet. Only ever the signed-in user's own file.
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');
  const activityId = pathIdOf(event);
  if (!activityId) return error(400, 'Invalid activity id');

  const Bucket = requireEnv('BUCKET_NAME');
  const Key = trackKey(userId, activityId);
  try {
    await s3.send(new HeadObjectCommand({ Bucket, Key }));
  } catch (e) {
    if (e instanceof NotFound || (e as { name?: string }).name === 'NotFound') return error(404, 'No track file yet');
    throw e;
  }
  const url = await getSignedUrl(s3, new GetObjectCommand({ Bucket, Key }), { expiresIn: LINK_SECONDS });
  return json(200, { url, method: 'GET', expiresIn: LINK_SECONDS });
};
