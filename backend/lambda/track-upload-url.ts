import { PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { activityIdOf, ApiEvent, ApiResult, error, json, LINK_SECONDS, requireEnv, trackKey, userIdOf } from './shared';

const s3 = new S3Client({});

/** The type the app must send the file with; it is part of the signed link. */
export const TRACK_CONTENT_TYPE = 'application/gzip';

/**
 * POST /activities/{id}/track-upload: a link the app can PUT the ride's gzipped track file
 * to for the next few minutes. The file always lands in the signed-in user's own folder.
 */
export const handler = async (event: ApiEvent): Promise<ApiResult> => {
  const userId = userIdOf(event);
  if (!userId) return error(401, 'Not signed in');
  const activityId = activityIdOf(event);
  if (!activityId) return error(400, 'Invalid activity id');

  const url = await getSignedUrl(
    s3,
    new PutObjectCommand({
      Bucket: requireEnv('BUCKET_NAME'),
      Key: trackKey(userId, activityId),
      ContentType: TRACK_CONTENT_TYPE,
    }),
    { expiresIn: LINK_SECONDS },
  );
  return json(200, { url, method: 'PUT', headers: { 'Content-Type': TRACK_CONTENT_TYPE }, expiresIn: LINK_SECONDS });
};
