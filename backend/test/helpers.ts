import type { ApiEvent } from '../lambda/shared';

process.env.TABLE_NAME = 'test-table';
process.env.BUCKET_NAME = 'test-bucket';
process.env.AWS_REGION = 'us-east-1';
// Presigning only needs credentials to sign with; these never reach AWS.
process.env.AWS_ACCESS_KEY_ID = 'test-access-key';
process.env.AWS_SECRET_ACCESS_KEY = 'test-secret';

export const USER = '11111111-aaaa-bbbb-cccc-000000000001';
export const RIDE_ID = '6f1c2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b';

/**
 * An HTTP API event as API Gateway sends it after the Cognito authorizer passed, for user
 * [sub]; null leaves the user out of the token.
 */
export function event(overrides: Partial<ApiEvent> = {}, sub: string | null = USER): ApiEvent {
  return {
    version: '2.0',
    routeKey: 'TEST',
    rawPath: '/',
    rawQueryString: '',
    headers: {},
    isBase64Encoded: false,
    requestContext: {
      accountId: '123456789012',
      apiId: 'api',
      domainName: 'example.com',
      domainPrefix: 'example',
      http: { method: 'GET', path: '/', protocol: 'HTTP/1.1', sourceIp: '127.0.0.1', userAgent: 'test' },
      requestId: 'req',
      routeKey: 'TEST',
      stage: '$default',
      time: '',
      timeEpoch: 0,
      authorizer: { principalId: '', integrationLatency: 0, jwt: { claims: sub ? { sub } : {}, scopes: [] } },
    },
    ...overrides,
  } as ApiEvent;
}

/** A valid summary body, as the app sends it. */
export function summary(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    type: 'ride',
    startedAt: 1_794_060_185_000,
    endedAt: 1_794_063_305_000,
    distanceMeters: 21_430.5,
    movingTimeMs: 2_820_000,
    elapsedTimeMs: 3_120_000,
    avgSpeedMps: 7.6,
    maxSpeedMps: 12.4,
    elevationGainMeters: 312,
    title: 'Morning ride',
    updatedAt: 1_794_063_400_000,
    deletedAt: null,
    ...overrides,
  };
}

export const body = (result: unknown) => JSON.parse((result as { body: string }).body);
export const status = (result: unknown) => (result as { statusCode: number }).statusCode;
