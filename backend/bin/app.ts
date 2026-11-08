#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { ActivityStatsStack } from '../lib/activity-stats-stack';

// One stack holds the whole backend. The account and region come from the AWS CLI profile
// used to deploy (`aws configure`), so nothing account-specific is kept in the repo.
const app = new cdk.App();
new ActivityStatsStack(app, 'ActivityStats', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION },
  description: 'Activity Stats: sign-in, ride summaries, track files and the sync API',
});
