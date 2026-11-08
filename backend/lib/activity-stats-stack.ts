import * as path from 'path';
import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import * as apigw from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpUserPoolAuthorizer } from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import { HttpLambdaIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import { Architecture, Runtime } from 'aws-cdk-lib/aws-lambda';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import { Construct } from 'constructs';

/** Where Cognito's sign-in page sends the user back to: the Android app's own link scheme. */
export const SIGN_IN_CALLBACK = 'com.danielbrog.activitystats://auth/callback';
export const SIGN_OUT_CALLBACK = 'com.danielbrog.activitystats://auth/signout';

/** The index the sync pull reads: a user's rides ordered by when the server last saved them. */
export const SYNCED_AT_INDEX = 'bySyncedAt';

/**
 * The whole Activity Stats backend:
 * - Cognito: accounts, with email and password on Cognito's hosted sign-in page.
 * - DynamoDB: one item per ride summary, keyed on userId and activityId.
 * - S3: one private, gzipped track file per ride, reached only through short-lived links.
 * - An HTTP API whose every route needs a valid Cognito token, in front of four Lambdas.
 *
 * Everything bills per request. Accounts, rides and track files are kept if the stack is
 * ever deleted (RemovalPolicy.RETAIN), so a mistaken `cdk destroy` can't lose them.
 */
export class ActivityStatsStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);

    // --- Accounts ---
    const userPool = new cognito.UserPool(this, 'Users', {
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

    // Cognito's hosted sign-in page. The prefix must be unique across AWS; the account id makes it so.
    const domain = userPool.addDomain('SignInDomain', {
      cognitoDomain: { domainPrefix: `activity-stats-${this.account}` },
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
    const fn = (name: string, file: string, description: string) =>
      new NodejsFunction(this, name, {
        entry: path.join(__dirname, '..', 'lambda', file),
        handler: 'handler',
        description,
        runtime: Runtime.NODEJS_22_X,
        architecture: Architecture.ARM_64,
        memorySize: 256,
        timeout: Duration.seconds(10),
        environment,
        // Bundle the AWS SDK too, so the code runs against the exact versions it was tested with.
        bundling: { minify: true, sourceMap: true, target: 'node22', externalModules: [] },
        logGroup: new logs.LogGroup(this, `${name}Logs`, {
          retention: logs.RetentionDays.ONE_MONTH,
          removalPolicy: RemovalPolicy.DESTROY,
        }),
      });

    const putActivity = fn('PutActivity', 'put-activity.ts', 'Saves a ride summary if it is newer than the stored one');
    const listActivities = fn('ListActivities', 'list-activities.ts', 'Lists rides saved since a given server time');
    const trackUpload = fn('TrackUploadUrl', 'track-upload-url.ts', 'Hands out a short-lived link to upload a track file');
    const trackDownload = fn('TrackDownloadUrl', 'track-download-url.ts', 'Hands out a short-lived link to download a track file');

    // Each function gets only what it needs. Which user's data it touches is checked in code,
    // from the verified token.
    table.grantReadWriteData(putActivity);
    table.grantReadData(listActivities);
    tracks.grantPut(trackUpload, 'tracks/*');
    tracks.grantRead(trackDownload, 'tracks/*');

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
    route('/activities/{id}/track-upload', apigw.HttpMethod.POST, trackUpload);
    route('/activities/{id}/track', apigw.HttpMethod.GET, trackDownload);

    // A ceiling on requests, so a bug in the app can't run up a bill.
    const stage = api.defaultStage!.node.defaultChild as apigw.CfnStage;
    stage.defaultRouteSettings = { throttlingRateLimit: 20, throttlingBurstLimit: 40 };

    // --- What the app needs to know (none of it is secret) ---
    new CfnOutput(this, 'ApiUrl', { value: api.apiEndpoint });
    new CfnOutput(this, 'UserPoolId', { value: userPool.userPoolId });
    new CfnOutput(this, 'AppClientId', { value: appClient.userPoolClientId });
    new CfnOutput(this, 'SignInUrl', { value: domain.baseUrl() });
    new CfnOutput(this, 'Region', { value: this.region });
  }
}
