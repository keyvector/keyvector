---
title: Why we forked the AWS sample, and what we changed
description: AWS published a Keyspaces-to-S3-Vectors connector as a demo. Here's what it would have done to production data.
date: 2026-11-02
authors: yogesh
tags: [design, reliability]
excerpt: AWS's sample connector is a good start and a risky finish. Five problems, each invisible until data goes missing.
---

In April 2026 AWS published a blog post and a
[sample repository](https://github.com/aws-samples/sample-keyspaces-cdc-streams-connectors)
showing how to stream Amazon Keyspaces changes into an S3 Vectors index. It's MIT-0
licensed, it reads the change stream with the Kinesis Client Library (KCL), and it handles
the genuinely hard parts of stream processing: leases, checkpoints and shard splits. We kept
all of that.

The vector connector itself is a demo, though, and reading it as if it would run in production
turned up five problems. None of them raises an error. Each one silently leaves the index wrong.

## 1. A failed batch was skipped, silently

When applying a batch failed, the processor logged the error and returned. KCL then delivered
the next batch, and when that one succeeded, checkpointed past both. The rows in the failed
batch never got a vector, and nothing anywhere said so.

Keyvector retries a failing batch with backoff and doesn't save the worker's position until
the batch is applied. After a configurable number of attempts it parks the rows' keys in a
dead-letter queue and moves on. Without a queue it keeps retrying: a stalled shard is a
visible failure (the stream's age climbs), a skipped batch isn't.

## 2. Deletes were ignored

The sample read only each change's new image. A deleted or expired row has none, so its vector
stayed in the index forever, and searches kept finding rows that no longer existed.

## 3. Every environment variable was printed at startup

The runner logged its whole environment, so an embedding API key would have ended up in
CloudWatch Logs. Keyvector logs no environment and reads keys from a named variable that ECS
fills from Secrets Manager.

## 4. The deployment template was too broad, and wrong

The CloudFormation template gave the task's execution role full ECS access, and its task role
`dynamodb:*` and `cassandra:*` on every resource. It also set the table-name variable to the
*keyspace* name.

Keyvector's template scopes every permission to one table, its stream, the connector's own
lease tables, one vector index and one queue (see [IAM permissions](/reference/iam/)).

## 5. Two settings were quietly ignored

- **The stream:** with several streams on a table (CDC switched off and on again), the sample
  took whichever was listed first, which could be a disabled one that never delivers anything.
  Keyvector takes the newest enabled stream.
- **The region:** the sample configured the Cassandra driver's SigV4 plugin with a key named
  `region`. The plugin reads `aws-region`, so the setting did nothing and only worked because
  `AWS_REGION` happened to be set.

## One trap that isn't in the sample

A stream can record new images, old images, both, or keys only, and the choice can't be changed
later. A connector that reads "no new image" as "row deleted", which is the natural way to
detect deletes, would read every insert and update on a `KEYS_ONLY` or `OLD_IMAGE` stream as a
delete and empty the index. Keyvector checks the stream's view type at startup and refuses to
run on one without new images.

The sample did its job, which was to show the pattern. Keyvector's job is to keep an index
right for months without anyone watching.
