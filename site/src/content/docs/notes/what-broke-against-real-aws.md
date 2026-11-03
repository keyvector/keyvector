---
title: What broke when we ran it against real AWS
description: 223 unit tests passed. Then we pointed Keyvector at a live Keyspaces stream and found four bugs and two surprises.
date: 2026-11-02
authors: yogesh
tags: [testing, bedrock, kcl]
excerpt: A 60-request-a-minute quota nobody can raise, a shutdown that never finished, and a 12-minute wait we hadn't planned for.
---

Unit tests can tell you a planner makes the right decision. They can't tell you what Bedrock
does to a new account, or what KCL does the first time it starts. So we built a test kit
([`e2e/`](https://github.com/keyvector/keyvector/tree/main/e2e)) and ran Keyvector against a
real Keyspaces table, a real change stream and real S3 Vectors indexes. We embedded with both
Amazon Bedrock Titan V2 and a local Hugging Face TEI running all-MiniLM-L6-v2. The whole thing
cost well under a dollar.

The sync logic held up: every one of 61 live changes was accounted for, a restart resumed at
exactly the right place, and stopping the model server mid-run lost nothing. Here's what
didn't hold up.

## Titan V2 allowed our new account 60 requests a minute

On our new account, on-demand **Titan Text Embeddings V2 was limited to 60 requests a
minute**, and Service Quotas listed that quota as not adjustable. (Accounts with more history
evidently get more: AWS's own DynamoDB walkthrough embeds 1,000 papers in 2 to 4 minutes.)
Titan embeds one text per request, so at 60 a minute that's one row per second. A 1M-row backfill through Titan would take about 11.5 days.

That turned up our first bug: **the Bedrock embedder didn't retry throttling.** It decided what
to retry by asking the SDK's `retryable()` and searching the error message for
"ThrottlingException". But the SDK reports a throttle it has already retried as *not*
retryable, and the message says "Too many requests". So the first throttle failed the whole
batch. In the stream, the processor's own batch retry would eventually have saved it; in a
backfill, the run simply died, though it had written nothing.

Two fixes:
- **Retry on the actual signal:** a throttling or 429 status, a 5xx, or a network error.
- **Pace requests to the quota in the first place.** Keyvector now spaces requests across all
  its threads to a per-minute limit, which defaults to 60 for Titan. The rerun of the same
  backfill took 5½ minutes for 320 rows, with zero throttles.

The bigger lesson is about the product, not the code. If a table changes faster than one row a
second, or needs a big backfill, use TEI, Cohere Embed (96 texts per request) or Bedrock batch
inference. "Bring your own model" turns out to be about throughput, not only privacy.

## The first start waits 12 minutes

On its first start, the stream worker did nothing for twelve minutes. KCL 3 adds a secondary
index to its DynamoDB lease table and assigns no shard until the index is active. That took
about 12 minutes per new application name, both times we measured. Later starts are immediate.
It's documented now, because "nothing is happening" is the worst thing a first-time user can
see.

## Shutdown could hang forever

While KCL was waiting on that index, stopping the worker hung. Our shutdown hook waited for
KCL's graceful shutdown without a timeout, and KCL can't finish shutting down while it's still
starting, so only `kill -9` worked. Now the hook gives up after 30 seconds. Nothing is lost by
giving up: an unacknowledged batch is simply read again by the next worker.

## A busy port killed the startup thread

Restarting quickly put a second worker on the same health-check port. Binding failed, the
exception escaped `main`, and the worker kept running with no health endpoint and a scary stack
trace. Now it logs the problem and carries on.

## `aws login` credentials didn't work

The fork's AWS SDK for Java (2.33) predated `aws login`, the CLI's browser sign-in, so the
connector couldn't use the credentials every new AWS user now has. Upgrading to 2.46 and adding
the `signin` module fixed it. The Python side needed `boto3[crt]` for the same reason.

## The other surprises

- **A new account's Bedrock access is blocked while AWS verifies the account**, for up to two
  hours. `keyvector check` caught it before anything ran, which is the reason it exists.
- **AWS's "new AWS experience" accounts can't use Amazon Keyspaces at all** unless you activate
  advanced features. We tested in a classic account.
- KCL logs `Incomplete hash range found` every two minutes against Keyspaces streams. It
  expects Kinesis-style hash ranges, which these streams don't have. It's noise; processing
  is unaffected.
