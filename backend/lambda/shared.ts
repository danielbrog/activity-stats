import type { APIGatewayProxyEventV2WithJWTAuthorizer, APIGatewayProxyResultV2 } from 'aws-lambda';

export type ApiEvent = APIGatewayProxyEventV2WithJWTAuthorizer;
export type ApiResult = APIGatewayProxyResultV2;

/**
 * Ride and segment ids are UUIDs made on the phone. This also keeps them safe inside S3 keys,
 * and it must never allow '#': a segment's sort key is `SEG#<id>` and its review's is
 * `SEG#<id>#REVIEW`, so an id with '#' in it could reach the server-owned review item.
 */
const ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;

export const ACTIVITY_TYPES = ['ride', 'run', 'walk'] as const;

/** How long a presigned upload or download link stays valid, in seconds. */
export const LINK_SECONDS = 300;

export function json(statusCode: number, body: unknown): ApiResult {
  return {
    statusCode,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  };
}

export const error = (statusCode: number, message: string) => json(statusCode, { error: message });

/**
 * The signed-in user's id, from the token API Gateway has already verified. This is the
 * only place a user id comes from: never the URL or the body, so nobody can reach someone
 * else's rides. Null if the claim is missing (which the authorizer shouldn't allow).
 */
export function userIdOf(event: ApiEvent): string | null {
  const sub = event.requestContext?.authorizer?.jwt?.claims?.sub;
  return typeof sub === 'string' && sub.length > 0 ? sub : null;
}

/** The {id} from the path, or null when it isn't a valid id (rides and segments use the same UUIDs). */
export function pathIdOf(event: ApiEvent): string | null {
  const id = event.pathParameters?.id;
  return typeof id === 'string' && ID_PATTERN.test(id) ? id : null;
}

/** Where a ride's track file lives: under its owner's folder, so keys can never collide. */
export const trackKey = (userId: string, activityId: string) => `tracks/${userId}/${activityId}.json.gz`;

export function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

/** A listing's page position, handed to the app as an opaque string. */
export const encodeCursor = (key: Record<string, unknown>) =>
  Buffer.from(JSON.stringify(key), 'utf8').toString('base64url');

export function decodeCursor(cursor: string): Record<string, unknown> | undefined {
  try {
    const value: unknown = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8'));
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}
