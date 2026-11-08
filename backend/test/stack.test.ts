import { App } from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { ActivityStatsStack, SIGN_IN_CALLBACK } from '../lib/activity-stats-stack';

// Skip bundling the Lambda code: these tests check the stack's settings, not the code.
const app = new App({ context: { 'aws:cdk:bundling-stacks': [] } });
const stack = new ActivityStatsStack(app, 'Test', { env: { account: '123456789012', region: 'us-east-1' } });
const template = Template.fromStack(stack);

describe('ActivityStatsStack', () => {
  test('the track bucket is private, encrypted and HTTPS-only, and kept on delete', () => {
    template.hasResource('AWS::S3::Bucket', {
      DeletionPolicy: 'Retain',
      Properties: {
        PublicAccessBlockConfiguration: {
          BlockPublicAcls: true,
          BlockPublicPolicy: true,
          IgnorePublicAcls: true,
          RestrictPublicBuckets: true,
        },
        BucketEncryption: Match.objectLike({
          ServerSideEncryptionConfiguration: [{ ServerSideEncryptionByDefault: { SSEAlgorithm: 'AES256' } }],
        }),
      },
    });
    template.hasResourceProperties('AWS::S3::BucketPolicy', {
      PolicyDocument: {
        Statement: Match.arrayWith([
          Match.objectLike({ Effect: 'Deny', Condition: { Bool: { 'aws:SecureTransport': 'false' } } }),
        ]),
      },
    });
  });

  test('the table is keyed on user then ride, pay per request, with the syncedAt index', () => {
    template.hasResource('AWS::DynamoDB::Table', {
      DeletionPolicy: 'Retain',
      Properties: {
        KeySchema: [
          { AttributeName: 'userId', KeyType: 'HASH' },
          { AttributeName: 'activityId', KeyType: 'RANGE' },
        ],
        BillingMode: 'PAY_PER_REQUEST',
        PointInTimeRecoverySpecification: { PointInTimeRecoveryEnabled: true },
        GlobalSecondaryIndexes: [
          Match.objectLike({
            IndexName: 'bySyncedAt',
            KeySchema: [
              { AttributeName: 'userId', KeyType: 'HASH' },
              { AttributeName: 'syncedAt', KeyType: 'RANGE' },
            ],
          }),
        ],
      },
    });
  });

  test('every API route needs a Cognito token', () => {
    const routes = template.findResources('AWS::ApiGatewayV2::Route');
    const keys = Object.values(routes).map((r) => r.Properties.RouteKey).sort();
    expect(keys).toEqual([
      'GET /activities',
      'GET /activities/{id}/track',
      'POST /activities/{id}/track-upload',
      'PUT /activities/{id}',
    ]);
    for (const route of Object.values(routes)) {
      expect(route.Properties.AuthorizationType).toBe('JWT');
      expect(route.Properties.AuthorizerId).toBeDefined();
    }
    template.resourceCountIs('AWS::ApiGatewayV2::Authorizer', 1);
  });

  test('requests are throttled so a bug cannot run up a bill', () => {
    template.hasResourceProperties('AWS::ApiGatewayV2::Stage', {
      DefaultRouteSettings: { ThrottlingRateLimit: 20, ThrottlingBurstLimit: 40 },
    });
  });

  test('the app client is public, uses the code flow, and returns to the app', () => {
    template.hasResourceProperties('AWS::Cognito::UserPoolClient', {
      GenerateSecret: false,
      AllowedOAuthFlows: ['code'],
      AllowedOAuthScopes: Match.arrayWith(['openid', 'email']),
      CallbackURLs: [SIGN_IN_CALLBACK],
      PreventUserExistenceErrors: 'ENABLED',
    });
    template.hasResource('AWS::Cognito::UserPool', {
      DeletionPolicy: 'Retain',
      Properties: Match.objectLike({ UsernameAttributes: ['email'], DeletionProtection: 'ACTIVE' }),
    });
  });

  test('the four functions run Node 22 on ARM with short timeouts', () => {
    const functions = template.findResources('AWS::Lambda::Function', {
      Properties: { Runtime: 'nodejs22.x' },
    });
    expect(Object.keys(functions)).toHaveLength(4);
    for (const f of Object.values(functions)) {
      expect(f.Properties.Architectures).toEqual(['arm64']);
      expect(f.Properties.Timeout).toBe(10);
    }
  });

  test('the upload function can only write track files, and cannot read them or the table', () => {
    const policies = Object.values(template.findResources('AWS::IAM::Policy'));
    const uploadPolicy = policies.find((p) =>
      JSON.stringify(p.Properties.Roles).includes('TrackUploadUrl'),
    );
    expect(uploadPolicy).toBeDefined();
    const text = JSON.stringify(uploadPolicy!.Properties.PolicyDocument);
    expect(text).toContain('s3:PutObject');
    expect(text).toContain('/tracks/*');
    expect(text).not.toContain('s3:GetObject');
    expect(text).not.toContain('dynamodb:');
  });
});
