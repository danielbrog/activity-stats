import * as path from 'path';
import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import * as apigw from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpUserPoolAuthorizer } from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import { HttpLambdaIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import * as budgets from 'aws-cdk-lib/aws-budgets';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import { SnsAction } from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as iam from 'aws-cdk-lib/aws-iam';
import { Architecture, Runtime } from 'aws-cdk-lib/aws-lambda';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sns from 'aws-cdk-lib/aws-sns';
import { EmailSubscription } from 'aws-cdk-lib/aws-sns-subscriptions';
import { Construct } from 'constructs';

/** Where Cognito's sign-in page sends the user back to: the Android app's own link scheme. */
export const SIGN_IN_CALLBACK = 'com.danielbrog.activitystats://auth/callback';
export const SIGN_OUT_CALLBACK = 'com.danielbrog.activitystats://auth/signout';

/** The index the sync pull reads: a user's rides ordered by when the server last saved them. */
export const SYNCED_AT_INDEX = 'bySyncedAt';

/** The sparse index of segments waiting for an admin to review them (see docs/database/segments.md). */
export const REVIEW_QUEUE_INDEX = 'reviewQueue';

/** Monthly spend, in US dollars, above which the budget alert emails. */
export const MONTHLY_BUDGET_USD = 5;

export interface ActivityStatsStackProps extends StackProps {
  /**
   * Where alarm and budget emails go. Passed in at deploy time (see bin/app.ts) rather than
   * written in the code, so the address never lands in git.
   */
  readonly alarmEmail: string;
}

/**
 * The whole Activity Stats backend:
 * - Cognito: accounts, with email and password on Cognito's hosted sign-in page.
 * - DynamoDB: one item per ride summary, keyed on userId and activityId.
 * - S3: one private, gzipped track file per ride, reached only through short-lived links.
 * - An HTTP API whose every route needs a valid Cognito token, in front of four Lambdas.
 *
 * - Alarms and a monthly budget that email when something breaks or costs climb.
 *
 * Everything bills per request. Accounts, rides and track files are kept if the stack is
 * ever deleted (RemovalPolicy.RETAIN), so a mistaken `cdk destroy` can't lose them.
 */
export class ActivityStatsStack extends Stack {
  constructor(scope: Construct, id: string, props: ActivityStatsStackProps) {
    super(scope, id, props);

    // --- Accounts ---
    // 'UsersV2': the original pool ('Users') was deleted outside CDK, and a new id is what
    // makes CloudFormation create a fresh one. Never rename it or move it into another
    // construct: either changes its logical id and makes yet another pool, with new accounts
    // and user ids.
    const userPool = new cognito.UserPool(this, 'UsersV2', {
      userPoolName: 'activity-stats-users',
      selfSignUpEnabled: true,
      signInAliases: { email: true },
      autoVerify: { email: true },
      standardAttributes: { email: { required: true, mutable: true } },
      passwordPolicy: { minLength: 8, requireLowercase: true, requireDigits: true, requireUppercase: false, requireSymbols: false },
      accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
      featurePlan: cognito.FeaturePlan.LITE,
      deletionProtection: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    // Cognito's hosted sign-in page. The prefix must be unique across AWS, and riders see it in
    // the address bar, so it names the app rather than the AWS account.
    const domain = userPool.addDomain('SignInDomain', {
      cognitoDomain: { domainPrefix: 'activity-stats-auth' },
    });

    // The Android app: a public client (no secret, since an app can't keep one), signing in
    // with the authorization code flow and PKCE.
    const appClient = userPool.addClient('AndroidApp', {
      generateSecret: false,
      authFlows: { userSrp: true },
      oAuth: {
        flows: { authorizationCodeGrant: true },
        scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL, cognito.OAuthScope.PROFILE],
        callbackUrls: [SIGN_IN_CALLBACK],
        logoutUrls: [SIGN_OUT_CALLBACK],
      },
      supportedIdentityProviders: [cognito.UserPoolClientIdentityProvider.COGNITO],
      preventUserExistenceErrors: true,
      enableTokenRevocation: true,
      accessTokenValidity: Duration.hours(1),
      idTokenValidity: Duration.hours(1),
      refreshTokenValidity: Duration.days(30),
    });

    // --- Ride summaries ---
    const table = new dynamodb.Table(this, 'Activities', {
      partitionKey: { name: 'userId', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'activityId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
      deletionProtection: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    // syncedAt is stamped by the server on every save, so "changed since" never depends on
    // a phone's clock. updatedAt (set on the phone) still decides which copy is newer.
    table.addGlobalSecondaryIndex({
      indexName: SYNCED_AT_INDEX,
      partitionKey: { name: 'userId', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'syncedAt', type: dynamodb.AttributeType.NUMBER },
      projectionType: dynamodb.ProjectionType.ALL,
    });

    // --- Segments (docs/database/segments.md) ---
    // On-demand, backed up and kept like Activities. Only the segment Lambdas below use
    // Segments; VerifiedSegments and PublicSegmentTiles start empty, for the review and
    // catalogue slices, and no rider-facing Lambda may write them.
    const keptTable = (id: string, partitionKey: dynamodb.Attribute, sortKey?: dynamodb.Attribute) =>
      new dynamodb.Table(this, id, {
        partitionKey,
        sortKey,
        billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
        pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
        deletionProtection: true,
        removalPolicy: RemovalPolicy.RETAIN,
      });
    const str = (name: string) => ({ name, type: dynamodb.AttributeType.STRING });
    const num = (name: string) => ({ name, type: dynamodb.AttributeType.NUMBER });

    // Personal segments and their reviews: SEG#<id> (the rider's) and SEG#<id>#REVIEW (the server's).
    const segments = keptTable('Segments', str('userId'), str('sk'));
    segments.addGlobalSecondaryIndex({
      indexName: SYNCED_AT_INDEX,
      partitionKey: str('userId'),
      sortKey: num('syncedAt'),
      projectionType: dynamodb.ProjectionType.ALL,
    });
    // Sparse: only review items waiting for a decision carry `queue`.
    segments.addGlobalSecondaryIndex({
      indexName: REVIEW_QUEUE_INDEX,
      partitionKey: str('queue'),
      sortKey: num('submittedAt'),
      projectionType: dynamodb.ProjectionType.ALL,
    });
    // Server-owned copies made on approval, and the search index phones read by map tile.
    // Nothing uses them yet: the review and catalogue slices will grant their admin Lambdas
    // write access; rider-facing Lambdas only ever get read access to the tiles.
    keptTable('VerifiedSegments', str('segmentId'));
    keptTable('PublicSegmentTiles', str('tile'), str('segmentId'));

    // --- Usage counts ---
    // How much each account used in a period: new rides per week (RIDES#2026-W48, holding the
    // ride ids), and track downloads and edits per day (DOWNLOADS#2026-11-26 and
    // EDITS#2026-11-26, each a number). Only counts, so
    // it isn't backed up or kept, and each row deletes itself two weeks after its period ends
    // (expiresAt, in seconds).
    const usage = new dynamodb.Table(this, 'Usage', {
      partitionKey: str('userId'),
      sortKey: str('period'),
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      timeToLiveAttribute: 'expiresAt',
      removalPolicy: RemovalPolicy.DESTROY,
    });

    // --- Track files ---
    const tracks = new s3.Bucket(this, 'Tracks', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      lifecycleRules: [{ abortIncompleteMultipartUploadAfter: Duration.days(1) }],
      removalPolicy: RemovalPolicy.RETAIN,
    });

    // --- Functions ---
    const environment = {
      TABLE_NAME: table.tableName,
      BUCKET_NAME: tracks.bucketName,
      // Error stack traces point at the TypeScript source, not the minified bundle.
      NODE_OPTIONS: '--enable-source-maps',
    };
    const fn = (name: string, file: string, description: string, extraEnvironment: Record<string, string> = {}) =>
      new NodejsFunction(this, name, {
        entry: path.join(__dirname, '..', 'lambda', file),
        handler: 'handler',
        description,
        runtime: Runtime.NODEJS_22_X,
        architecture: Architecture.ARM_64,
        memorySize: 256,
        timeout: Duration.seconds(10),
        environment: { ...environment, ...extraEnvironment },
        // Bundle the AWS SDK too, so the code runs against the exact versions it was tested with.
        bundling: { minify: true, sourceMap: true, target: 'node22', externalModules: [] },
        logGroup: new logs.LogGroup(this, `${name}Logs`, {
          retention: logs.RetentionDays.ONE_MONTH,
          removalPolicy: RemovalPolicy.DESTROY,
        }),
      });

    const usageEnvironment = { USAGE_TABLE_NAME: usage.tableName };
    const putActivity = fn('PutActivity', 'put-activity.ts', 'Saves a ride summary if it is newer than the stored one', usageEnvironment);
    const listActivities = fn('ListActivities', 'list-activities.ts', 'Lists rides saved since a given server time');
    const putTrack = fn('PutTrack', 'put-track.ts', 'Checks an uploaded track file and stores a clean copy', usageEnvironment);
    const getTrack = fn('GetTrack', 'get-track.ts', 'Returns a track file, counting it towards the daily download limit', usageEnvironment);
    const segmentEnvironment = { SEGMENTS_TABLE_NAME: segments.tableName };
    const putSegment = fn('PutSegment', 'put-segment.ts', 'Saves a personal segment if it is newer than the stored one', {
      ...segmentEnvironment,
      ...usageEnvironment,
    });
    const listSegments = fn('ListSegments', 'list-segments.ts', 'Lists personal segments and reviews saved since a given server time', segmentEnvironment);

    // Each function gets only what it needs. Which user's data it touches is checked in code,
    // from the verified token.
    table.grantReadWriteData(putActivity);
    table.grantReadData(listActivities);
    tracks.grantPut(putTrack, 'tracks/*');
    tracks.grantRead(getTrack, 'tracks/*');
    // Counting is one conditional update; no function may read or delete counts.
    usage.grant(putTrack, 'dynamodb:UpdateItem');
    usage.grant(putActivity, 'dynamodb:UpdateItem');
    usage.grant(getTrack, 'dynamodb:UpdateItem');
    usage.grant(putSegment, 'dynamodb:UpdateItem');
    // Narrower than grantReadWriteData / grantReadData: Segments will also hold server-owned
    // review items, so these may only write a segment item and list by the rider's own index.
    segments.grant(putSegment, 'dynamodb:PutItem', 'dynamodb:GetItem');
    listSegments.addToRolePolicy(
      new iam.PolicyStatement({
        actions: ['dynamodb:Query'],
        resources: [`${segments.tableArn}/index/${SYNCED_AT_INDEX}`],
      }),
    );

    // --- API ---
    const authorizer = new HttpUserPoolAuthorizer('CognitoAuthorizer', userPool, {
      userPoolClients: [appClient],
    });
    const api = new apigw.HttpApi(this, 'Api', {
      description: 'Activity Stats sync API',
      defaultAuthorizer: authorizer,
    });
    const route = (routePath: string, method: apigw.HttpMethod, handler: NodejsFunction) =>
      api.addRoutes({
        path: routePath,
        methods: [method],
        integration: new HttpLambdaIntegration(`${handler.node.id}Integration`, handler),
        authorizer,
      });
    route('/activities/{id}', apigw.HttpMethod.PUT, putActivity);
    route('/activities', apigw.HttpMethod.GET, listActivities);
    // Uploads go through the API, not straight to S3, so every file is checked before it is stored.
    route('/activities/{id}/track', apigw.HttpMethod.PUT, putTrack);
    // Downloads too, so each one can be counted (a presigned S3 link could be fetched any number of times).
    route('/activities/{id}/track', apigw.HttpMethod.GET, getTrack);
    route('/segments/{id}', apigw.HttpMethod.PUT, putSegment);
    route('/segments', apigw.HttpMethod.GET, listSegments);

    // A ceiling on requests, so a bug in the app can't run up a bill.
    const stage = api.defaultStage!.node.defaultChild as apigw.CfnStage;
    stage.defaultRouteSettings = { throttlingRateLimit: 20, throttlingBurstLimit: 40 };

    // --- Alerts ---
    // One email channel for both alarms and the budget. AWS emails a confirmation link first.
    const alerts = new sns.Topic(this, 'Alarms', {
      topicName: 'ActivityStatsAlarms',
      displayName: 'Activity Stats alerts',
    });
    alerts.addSubscription(new EmailSubscription(props.alarmEmail));
    // A topic policy replaces SNS's default one, so every service that publishes here needs
    // its own statement: CloudWatch for this stack's alarms, and Budgets for the budget.
    alerts.addToResourcePolicy(
      new iam.PolicyStatement({
        principals: [new iam.ServicePrincipal('cloudwatch.amazonaws.com')],
        actions: ['sns:Publish'],
        resources: [alerts.topicArn],
        conditions: {
          StringEquals: { 'aws:SourceAccount': this.account },
          ArnLike: { 'aws:SourceArn': `arn:${this.partition}:cloudwatch:${this.region}:${this.account}:alarm:ActivityStats-*` },
        },
      }),
    );
    alerts.addToResourcePolicy(
      new iam.PolicyStatement({
        principals: [new iam.ServicePrincipal('budgets.amazonaws.com')],
        actions: ['sns:Publish'],
        resources: [alerts.topicArn],
        conditions: { StringEquals: { 'aws:SourceAccount': this.account } },
      }),
    );
    const notify = new SnsAction(alerts);

    // Built-in metrics only, so these stay within CloudWatch's free alarms. No traffic counts
    // as fine, and an email also goes out when an alarm clears.
    const alarm = (name: string, metric: cloudwatch.IMetric, threshold: number, description: string) => {
      const a = new cloudwatch.Alarm(this, name, {
        alarmName: `ActivityStats-${name}`,
        alarmDescription: description,
        metric,
        threshold,
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      });
      a.addAlarmAction(notify);
      a.addOkAction(notify);
    };
    const fiveMinutes = { period: Duration.minutes(5), statistic: cloudwatch.Stats.SUM };
    const functions: [NodejsFunction, string][] = [
      [putActivity, 'Saving a ride summary failed'],
      [listActivities, 'Listing changed rides failed'],
      [putTrack, 'Saving a track file failed'],
      [getTrack, 'Returning a track file failed'],
      [putSegment, 'Saving a segment failed'],
      [listSegments, 'Listing changed segments failed'],
    ];
    for (const [handler, description] of functions) {
      alarm(`${handler.node.id}Errors`, handler.metricErrors(fiveMinutes), 1, `${description} at least once in 5 minutes`);
    }
    alarm('ApiServerErrors', api.metricServerError(fiveMinutes), 5, 'The API returned 5 or more server errors in 5 minutes');

    // A whole-account monthly budget: one email when actual spend passes the limit, one when
    // AWS forecasts the month will.
    const overBudget = (notificationType: 'ACTUAL' | 'FORECASTED') => ({
      notification: { notificationType, comparisonOperator: 'GREATER_THAN', threshold: 100, thresholdType: 'PERCENTAGE' },
      subscribers: [{ subscriptionType: 'SNS', address: alerts.topicArn }],
    });
    const budget = new budgets.CfnBudget(this, 'MonthlyBudget', {
      budget: {
        budgetName: 'ActivityStats-monthly',
        budgetType: 'COST',
        timeUnit: 'MONTHLY',
        budgetLimit: { amount: MONTHLY_BUDGET_USD, unit: 'USD' },
      },
      notificationsWithSubscribers: [overBudget('ACTUAL'), overBudget('FORECASTED')],
    });
    // The topic must allow Budgets to publish before the budget is created.
    budget.node.addDependency(alerts);

    // --- What the app needs to know (none of it is secret) ---
    new CfnOutput(this, 'ApiUrl', { value: api.apiEndpoint });
    new CfnOutput(this, 'UserPoolId', { value: userPool.userPoolId });
    new CfnOutput(this, 'AppClientId', { value: appClient.userPoolClientId });
    new CfnOutput(this, 'SignInUrl', { value: domain.baseUrl() });
    new CfnOutput(this, 'Region', { value: this.region });
  }
}
