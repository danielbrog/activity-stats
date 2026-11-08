#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { ActivityStatsStack } from '../lib/activity-stats-stack';

/**
 * Where the backend lives. Fixed here rather than taken from the CLI's default region, so a
 * deploy from a terminal set to another region can't create a second copy of everything.
 */
export const REGION = 'us-east-2';

// One stack holds the whole backend. The account comes from whoever is signed in to the
// AWS CLI (`aws login`), so nothing account-specific is kept in the repo.
const app = new cdk.App();
new ActivityStatsStack(app, 'ActivityStats', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: REGION },
  description: 'Activity Stats: sign-in, ride summaries, track files and the sync API',
});
