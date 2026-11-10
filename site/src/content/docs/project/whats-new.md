---
title: What's new
description: Every Keyvector release and update, newest first.
---

## 9 November 2026: dependency security update

Every library Keyvector ships was checked against the OSV vulnerability database, including the
ones it pulls in indirectly. **None has a known vulnerability now**; before, 31 did.

- **Updated:** Jackson 2.22.4, Netty 4.1.140, Guava 33.8, Apache HttpClient 5.6.5 and HttpCore 5.4.4,
  and logging to logback 1.5.38 with SLF4J 2.
- **Removed:** two optional modules Keyvector never used, which brought most of the rest. KCL's
  Glue Schema Registry decoder carried Kafka, LZ4, Snappy and Wire. The Cassandra driver's
  geospatial library carried a 2009 `org.json` and Jackson 1.x. The jar is 37 MB smaller.
- **Tested against live AWS** before release: the stream connector, backfill and queries.

## 9 November 2026: chunking and reranking

- **Chunking** (`sync.chunk-words`). Long rows are split into overlapping, sentence-aligned chunks,
  one vector each, so an article or transcript is searchable all the way through. When a row's text
  shrinks or the row is deleted, every stale chunk goes too.
- **Reranking** (`rerank.model`). A cross-encoder re-scores query results inside Keyvector, reading
  the query and each row's text together. Its scores match Hugging Face's own server to within
  0.00001; it takes about 75 ms for 50 candidates.
- **Queries** return each row once, with the chunk that matched and the reranker's score.
- Tested against live AWS: 302 rows became exactly the 914 predicted chunks, and live changes,
  shortened texts, deletes and a restart left no stale chunk behind.

[Long text and reranking →](/guides/long-text/)

## 3 November 2026: embeddings without a model server

- **`embedder.type = "local"`** runs a Sentence-Transformers model inside Keyvector with ONNX Runtime:
  no TEI container, no API key, no per-token cost. It's now the default.
- Its vectors are identical to Hugging Face's own server's (cosine similarity 1.000000) for two
  models, checked in CI on every push. About 546 texts a second on a laptop.
- `keyvector fetch-model` and the Dockerfile's `MODEL` argument bake a model into the image, so it
  runs with no network access.

[Embeddings without a model server →](/notes/embeddings-without-a-model-server/) ·
[What Keyvector creates in your AWS account →](/notes/what-keyvector-creates-in-your-aws-account/)

## 2 November 2026: Keyvector 0.1

The first public release: an open-source connector that keeps an Amazon S3 Vectors index in sync
with an Amazon Keyspaces table, from its change stream, in your own AWS account.

- Inserts, updates, deletes and TTL expiries all reach the index; failed batches are retried, never
  skipped, with a dead-letter queue and redrive.
- Embeds only when a row's text changes.
- Backfill, resync and a `query` command that returns current rows.
- Tested end to end against live AWS.

[Introducing Keyvector →](/notes/introducing-keyvector/) ·
[Tutorial: search research papers →](/guides/arxiv-tutorial/)
