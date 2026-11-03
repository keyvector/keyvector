---
title: Why Keyvector
description: Who Keyvector is for, what the alternatives cost, and when it isn't the right fit.
---

**Keyvector is for teams whose data lives in Amazon Keyspaces and who now want to search it by
meaning.** Typical uses:

- "Find tickets like this one"
- Product or catalog search that understands the question
- Retrieval for an AI assistant (RAG)
- "More like this" recommendations
- Near-duplicate detection

Keyspaces can't do any of that on its own: it has no vector type and no vector index.

## The alternatives, and what they cost

| Your other option | What it costs you |
|---|---|
| Migrate to a Cassandra with vector search (DataStax Astra DB, ScyllaDB, self-managed Cassandra 5) | Redoing a database migration you probably just finished, and leaving a managed AWS service |
| Move the data to DynamoDB or OpenSearch | Rewriting your data layer and running two databases during the move |
| Build the sync pipeline yourself | Weeks of work on the parts that are easy to get wrong (deletes, retries, restarts, backfill, composite keys), then owning it on call |
| Wait for AWS to add vectors to Keyspaces | An unknown wait |

## What you get instead

- **No migration.** Keyspaces stays your source of truth, and your application keeps writing to
  it unchanged. Turn on the table's change stream and the index follows.
- **The index stays right.** Every insert, update, delete and expiry reaches the index, a failed
  batch is retried rather than skipped, and a restart picks up where it left off. See
  [Status and testing](/project/status/).
- **Results are never stale.** A query reads the matching rows back from Keyspaces, so you get
  what the table holds now.
- **Your data stays in your account.** It runs on your ECS, under an IAM role that can read one
  table and write one index. With a self-hosted model your text never leaves your VPC.
- **Pick your model.** Open Sentence-Transformers models on Hugging Face TEI (fine-tune them, no
  per-token charges, no request quota), Amazon Bedrock, or any OpenAI-compatible API.
- **Cheap to run.** One small container per table. Rows are embedded only when their text
  changes, and vectors live in S3 Vectors, priced for storage rather than always-on servers.
- **Open source, Apache-2.0.**

## When it isn't the right fit

- If you're free to change databases, a database with built-in vector search is simpler than
  running two systems.
- If you need search results to be transactionally consistent with writes: the index trails the
  table by seconds.
- If your data isn't in Keyspaces, this version won't help yet (see the [roadmap](/project/roadmap/)).
