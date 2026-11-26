import { App } from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { ActivityStatsStack, SIGN_IN_CALLBACK } from '../lib/activity-stats-stack';

// Skip bundling the Lambda code: these tests check the stack's settings, not the code.
const app = new App({ context: { 'aws:cdk:bundling-stacks': [] } });
const ALARM_EMAIL = 'alerts@example.com';
const stack = new ActivityStatsStack(app, 'Test', {
  alarmEmail: ALARM_EMAIL,
  env: { account: '123456789012', region: 'us-east-2' },
});
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
      'GET /segments',
      'PUT /activities/{id}',
      'PUT /activities/{id}/track',
      'PUT /segments/{id}',
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

  test('there is one user pool, with one app client and one sign-in domain', () => {
    template.resourceCountIs('AWS::Cognito::UserPool', 1);
    template.resourceCountIs('AWS::Cognito::UserPoolClient', 1);
    template.resourceCountIs('AWS::Cognito::UserPoolDomain', 1);
    template.hasResourceProperties('AWS::Cognito::UserPool', { UserPoolName: 'activity-stats-users' });
    // Riders see the sign-in address, so it names the app, not the AWS account.
    template.hasResourceProperties('AWS::Cognito::UserPoolDomain', { Domain: 'activity-stats-auth' });
  });

  test('the pool and its client keep their logical ids, so a refactor cannot make a new, empty pool', () => {
    expect(Object.keys(template.findResources('AWS::Cognito::UserPool'))).toEqual([
      expect.stringMatching(/^UsersV2[0-9A-F]{8}$/),
    ]);
    expect(Object.keys(template.findResources('AWS::Cognito::UserPoolClient'))).toEqual([
      expect.stringMatching(/^UsersV2AndroidApp[0-9A-F]{8}$/),
    ]);
  });

  test('the six functions run Node 22 on ARM with short timeouts', () => {
    const functions = template.findResources('AWS::Lambda::Function', {
      Properties: { Runtime: 'nodejs22.x' },
    });
    expect(Object.keys(functions)).toHaveLength(6);
    for (const f of Object.values(functions)) {
      expect(f.Properties.Architectures).toEqual(['arm64']);
      expect(f.Properties.Timeout).toBe(10);
    }
  });

  test('alerts go by email through one topic that both CloudWatch and Budgets may publish to', () => {
    template.resourceCountIs('AWS::SNS::Topic', 1);
    template.hasResourceProperties('AWS::SNS::Subscription', { Protocol: 'email', Endpoint: ALARM_EMAIL });
    // The topic policy replaces SNS's default, so a missing statement silently drops alarms.
    template.hasResourceProperties('AWS::SNS::TopicPolicy', {
      PolicyDocument: {
        Statement: Match.arrayWith([
          Match.objectLike({
            Effect: 'Allow',
            Principal: { Service: 'cloudwatch.amazonaws.com' },
            Action: 'sns:Publish',
            Condition: Match.objectLike({ StringEquals: { 'aws:SourceAccount': '123456789012' } }),
          }),
          Match.objectLike({
            Effect: 'Allow',
            Principal: { Service: 'budgets.amazonaws.com' },
            Action: 'sns:Publish',
            Condition: { StringEquals: { 'aws:SourceAccount': '123456789012' } },
          }),
        ]),
      },
    });
  });

  test('each function has an error alarm and the API a server-error alarm, all emailing', () => {
    const alarms = Object.values(template.findResources('AWS::CloudWatch::Alarm'));
    expect(alarms.map((a) => a.Properties.AlarmName).sort()).toEqual([
      'ActivityStats-ApiServerErrors',
      'ActivityStats-ListActivitiesErrors',
      'ActivityStats-ListSegmentsErrors',
      'ActivityStats-PutActivityErrors',
      'ActivityStats-PutSegmentErrors',
      'ActivityStats-PutTrackErrors',
      'ActivityStats-TrackDownloadUrlErrors',
    ]);
    for (const a of alarms) {
      const p = a.Properties;
      expect(p.Period).toBe(300);
      expect(p.Statistic).toBe('Sum');
      expect(p.TreatMissingData).toBe('notBreaching');
      expect(p.ComparisonOperator).toBe('GreaterThanOrEqualToThreshold');
      expect(p.AlarmActions).toHaveLength(1);
      expect(p.OKActions).toEqual(p.AlarmActions);
    }
    const byName = (name: string) => alarms.find((a) => a.Properties.AlarmName === name)!.Properties;
    expect(byName('ActivityStats-PutActivityErrors')).toMatchObject({ Namespace: 'AWS/Lambda', MetricName: 'Errors', Threshold: 1 });
    expect(byName('ActivityStats-ApiServerErrors')).toMatchObject({ Namespace: 'AWS/ApiGateway', MetricName: '5xx', Threshold: 5 });
  });

  test('a $5 monthly budget emails on actual and forecast spend', () => {
    template.hasResourceProperties('AWS::Budgets::Budget', {
      Budget: Match.objectLike({ BudgetType: 'COST', TimeUnit: 'MONTHLY', BudgetLimit: { Amount: 5, Unit: 'USD' } }),
      NotificationsWithSubscribers: [
        Match.objectLike({ Notification: Match.objectLike({ NotificationType: 'ACTUAL', Threshold: 100 }) }),
        Match.objectLike({ Notification: Match.objectLike({ NotificationType: 'FORECASTED', Threshold: 100 }) }),
      ],
    });
  });

  test('the track function can only write track files, and cannot read them or the table', () => {
    const policies = Object.values(template.findResources('AWS::IAM::Policy'));
    const uploadPolicy = policies.find((p) =>
      JSON.stringify(p.Properties.Roles).includes('PutTrack'),
    );
    expect(uploadPolicy).toBeDefined();
    const text = JSON.stringify(uploadPolicy!.Properties.PolicyDocument);
    expect(text).toContain('s3:PutObject');
    expect(text).toContain('/tracks/*');
    expect(text).not.toContain('s3:GetObject');
    // Only counting new rides: one update on the Usage table, nothing on any other table.
    const dynamo = JSON.stringify(text).match(/dynamodb:[A-Za-z]+/g) ?? [];
    expect([...new Set(dynamo)]).toEqual(['dynamodb:UpdateItem']);
    expect(text).toContain('Usage');
  });

  test('the Usage table counts rides per account and week and cleans itself up', () => {
    template.hasResourceProperties('AWS::DynamoDB::Table', {
      KeySchema: [
        { AttributeName: 'userId', KeyType: 'HASH' },
        { AttributeName: 'period', KeyType: 'RANGE' },
      ],
      BillingMode: 'PAY_PER_REQUEST',
      TimeToLiveSpecification: { AttributeName: 'expiresAt', Enabled: true },
    });
    // Its logical id is pinned: renaming it would make a new, empty table and reset every count.
    const tables = Object.keys(template.findResources('AWS::DynamoDB::Table'));
    expect(tables.filter((id) => /^Usage[0-9A-F]{8}$/.test(id))).toHaveLength(1);
  });

  // --- Segments (docs/database/segments.md)

  /** The table whose keys are exactly [keys], as name → type. */
  const tableWithKeys = (keys: Record<string, 'HASH' | 'RANGE'>) =>
    Object.entries(template.findResources('AWS::DynamoDB::Table')).find(([, t]) => {
      const schema = t.Properties.KeySchema as { AttributeName: string; KeyType: string }[];
      return schema.length === Object.keys(keys).length && schema.every((k) => keys[k.AttributeName] === k.KeyType);
    });

  test('the three segment tables are keyed as designed, backed up, protected and kept on delete', () => {
    const tables = [
      tableWithKeys({ userId: 'HASH', sk: 'RANGE' }),
      tableWithKeys({ segmentId: 'HASH' }),
      tableWithKeys({ tile: 'HASH', segmentId: 'RANGE' }),
    ];
    for (const found of tables) {
      expect(found).toBeDefined();
      const [, t] = found!;
      expect(t.DeletionPolicy).toBe('Retain');
      expect(t.Properties.BillingMode).toBe('PAY_PER_REQUEST');
      expect(t.Properties.DeletionProtectionEnabled).toBe(true);
      expect(t.Properties.PointInTimeRecoverySpecification).toEqual({ PointInTimeRecoveryEnabled: true });
    }
  });

  test('Segments has the sync index and the sparse review queue index', () => {
    const [, t] = tableWithKeys({ userId: 'HASH', sk: 'RANGE' })!;
    const indexes = t.Properties.GlobalSecondaryIndexes as { IndexName: string; KeySchema: unknown }[];
    expect(indexes.map((i) => i.IndexName).sort()).toEqual(['bySyncedAt', 'reviewQueue']);
    expect(indexes.find((i) => i.IndexName === 'bySyncedAt')!.KeySchema).toEqual([
      { AttributeName: 'userId', KeyType: 'HASH' },
      { AttributeName: 'syncedAt', KeyType: 'RANGE' },
    ]);
    expect(indexes.find((i) => i.IndexName === 'reviewQueue')!.KeySchema).toEqual([
      { AttributeName: 'queue', KeyType: 'HASH' },
      { AttributeName: 'submittedAt', KeyType: 'RANGE' },
    ]);
  });

  test('only the segment functions are told the segment table, and nothing the verified ones', () => {
    const functions = Object.values(template.findResources('AWS::Lambda::Function', { Properties: { Runtime: 'nodejs22.x' } }));
    const withSegments = functions.filter((f) => 'SEGMENTS_TABLE_NAME' in (f.Properties.Environment?.Variables ?? {}));
    expect(withSegments).toHaveLength(2);
    const everyEnvironment = JSON.stringify(functions.map((f) => f.Properties.Environment));
    expect(everyEnvironment).not.toContain('VERIFIED');
    expect(everyEnvironment).not.toContain('TILES');
  });

  test('the segment routes exist and need a Cognito token', () => {
    for (const routeKey of ['PUT /segments/{id}', 'GET /segments']) {
      template.hasResourceProperties('AWS::ApiGatewayV2::Route', { RouteKey: routeKey, AuthorizationType: 'JWT' });
    }
  });

  test('the segment functions can use Segments only, and never write a verified segment', () => {
    const [segmentsId] = tableWithKeys({ userId: 'HASH', sk: 'RANGE' })!;
    const [verifiedId] = tableWithKeys({ segmentId: 'HASH' })!;
    const [tilesId] = tableWithKeys({ tile: 'HASH', segmentId: 'RANGE' })!;
    const policies = Object.values(template.findResources('AWS::IAM::Policy'));
    const policyOf = (fn: string) =>
      JSON.stringify(policies.find((p) => JSON.stringify(p.Properties.Roles).includes(fn))!.Properties.PolicyDocument);

    // Segments will also hold server-owned review items, so these get only what they need.
    const put = policyOf('PutSegment');
    expect(put).toContain(segmentsId);
    expect(put).toContain('dynamodb:PutItem');
    expect(put).toContain('dynamodb:GetItem');
    for (const action of ['DeleteItem', 'UpdateItem', 'BatchWriteItem', 'Scan', 'Query']) {
      expect(put).not.toContain(`dynamodb:${action}`);
    }
    const list = policyOf('ListSegments');
    expect(list).toContain(segmentsId);
    expect(list).toContain('dynamodb:Query');
    expect(list).toContain('/index/bySyncedAt');
    for (const forbidden of ['dynamodb:PutItem', 'dynamodb:Scan', 'dynamodb:GetItem', 'reviewQueue']) {
      expect(list).not.toContain(forbidden);
    }

    // No function in the stack may touch the verified tables yet: only the admin routes will.
    const everyPolicy = JSON.stringify(policies.map((p) => p.Properties.PolicyDocument));
    expect(everyPolicy).not.toContain(verifiedId);
    expect(everyPolicy).not.toContain(tilesId);
  });
});
