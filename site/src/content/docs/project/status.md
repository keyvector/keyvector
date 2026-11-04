---
title: Status and testing
description: What has been tested against live AWS, and what hasn't yet.
---

**Pre-release, version 0.1.** 232 unit tests cover:
- the sync rules
- key encoding
- the embedders
- throttling and retries
- the dead-letter behaviour
- the in-process embedder, whose vectors are compared with Hugging Face TEI's for two models
  (all-MiniLM-L6-v2 and bge-small-en-v1.5) and must match exactly

## Tested end to end against live AWS (2 November 2026)

The test used Keyspaces CDC in us-east-1, S3 Vectors, Bedrock Titan V2 and a local Hugging Face
TEI running all-MiniLM-L6-v2. The kit is
[`e2e/`](https://github.com/keyvector/keyvector/tree/main/e2e) in the repo.

| Step | Result |
|---|---|
| `keyvector check` | All five checks passed on both models |
| Backfill | 302 rows became exactly 302 vectors, including keys containing spaces, `&` and `=` |
| 61 live changes | All accounted for: 31 embedded, 10 metadata-only changes reused the stored vector, 10 changes to an unembedded column wrote nothing, 10 deletes removed vectors. Only rows whose text changed got new vectors |
| Stop, change 5 rows, restart | Exactly those 5 applied |
| Model server stopped mid-run | Nothing lost: the batches waited and landed when it came back |
| A second connector replaying the whole stream on top of a backfill | Converged on the same index |
| Semantic queries | Expected rows first; metadata filters worked |

The test also found four bugs, all fixed. The
[dev note](/notes/what-broke-against-real-aws/) covers them.

## Repeated with the in-process embedder (3 November 2026)

The same kit, with `embedder.type = "local"`: no model server and no Bedrock, with
all-MiniLM-L6-v2 running inside the connector on a laptop.

| Step | Result |
|---|---|
| `keyvector check` | All five checks passed, including loading the pinned model |
| Backfill | 302 rows in 17 seconds, exactly 302 vectors |
| 61 live changes | The same split: 31 embedded, 10 reused, 10 unchanged, 10 deleted |
| Stop, change 5 rows, restart | Exactly those 5 applied |
| Semantic queries | Each test query's target first, including a row that only ever arrived through the stream |

[What Keyvector creates in your AWS account](/notes/what-keyvector-creates-in-your-aws-account/)
shows this run in the AWS console.

## Not yet tested live

- The Fargate deployment template (`deploy/keyvector.yaml`)
- The dead-letter queue and `redrive`
- TTL expiry
