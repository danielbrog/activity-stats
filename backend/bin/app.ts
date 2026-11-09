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

// Where alarm emails go. Kept out of git: it comes from `alarmEmail` in cdk.context.json
// (git-ignored, on this PC only) or from `-c alarmEmail=...` on the command line.
const alarmEmail: unknown = app.node.tryGetContext('alarmEmail');
if (typeof alarmEmail !== 'string' || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(alarmEmail)) {
  throw new Error(
    'Set the alarm email first: put {"alarmEmail": "you@example.com"} in backend/cdk.context.json, ' +
      'or pass -c alarmEmail=you@example.com',
  );
}

new ActivityStatsStack(app, 'ActivityStats', {
  alarmEmail,
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: REGION },
  description: 'Activity Stats: sign-in, ride summaries, track files and the sync API',
});
