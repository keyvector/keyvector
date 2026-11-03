---
title: IAM permissions
description: Exactly what the Keyvector task role can do.
---

The task role in `deploy/keyvector.yaml` can do what the connector needs, and nothing else:

| Purpose | Actions | Scope |
|---|---|---|
| Read the change stream | `cassandra:GetStream`, `GetShardIterator`, `GetRecords` | This table's stream |
| Find the stream | `cassandra:ListStreams` | `*` (no resource-level scope exists) |
| Read rows (backfill, query) | `cassandra:Select` | This table, and the `system*` keyspaces the driver reads the schema from |
| Lease tables | DynamoDB read, write, create | `<stack>-lease-table*` only |
| Vector index | `s3vectors:PutVectors`, `DeleteVectors`, `GetVectors`, `QueryVectors`, `GetIndex` | This index |
| Dead-letter queue | `sqs:SendMessage`, `ReceiveMessage`, `DeleteMessage` | This stack's queue |
| Metrics | `cloudwatch:PutMetricData` | `*` |
| Embeddings | `bedrock:InvokeModel` | The chosen model, only when the embedder is Bedrock |

An OpenAI-compatible API key, if any, comes from Secrets Manager through the execution role and
reaches the container as an environment variable; it's never logged.
