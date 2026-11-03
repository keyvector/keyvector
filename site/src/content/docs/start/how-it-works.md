---
title: How it works
description: The stream, the planner, the embedder, the index and the query path.
---

Keyvector is a fork of AWS's
[sample Keyspaces CDC connector](https://github.com/aws-samples/sample-keyspaces-cdc-streams-connectors),
which reads a table's change stream with the Kinesis Client Library (KCL). Keyvector replaces the
sample's vector connector with a sync engine built to keep an index correct.

## Where the model fits

The embedding model and Keyvector do different jobs.

- **The model understands text.** A Sentence-Transformers model on Hugging Face TEI, or Titan
  on Bedrock, turns text into a vector that captures its meaning. It does this for every
  changed row, and for every question at query time.
- **Keyvector is the pipeline around the model.** It notices which rows changed, sends their
  text to the model, stores the vectors, keeps them in step with deletes and expiries, and at
  query time embeds the question, searches the index and reads the matching rows back from
  Keyspaces.

You choose the model in the configuration. Keyvector works the same way whichever you pick.

## The write path

1. **Read.** KCL spreads the stream's shards across workers, keeps each worker's position in a
   DynamoDB lease table, and survives restarts and shard splits.
2. **Plan.** Changes to the same row in a batch are collapsed to the row's state before and
   after. Then, per row:
   - **row gone** (deleted or expired): delete the vector;
   - **text changed**: embed the new text and write it;
   - **only metadata changed**: rewrite with the stored vector, without embedding;
   - **nothing relevant changed**: do nothing.
3. **Embed.** Texts go to the model in batches, in parallel, paced to the model's quota, with
   retries on throttling and server errors.
4. **Write.** Vectors are written only after every embedding in the batch succeeded, then
   deletes are applied. Both are idempotent, so a batch applied twice after a crash leaves the
   index as applying it once would.
5. **Acknowledge.** Only then does the worker save its position. A batch that keeps failing is
   retried with backoff. After `max-attempts` it's parked in a dead-letter queue, if you
   configured one; otherwise the shard waits rather than skip it.

## Row keys

Each vector's key is the row's full primary key, written as `column=value` pairs sorted by
name and percent-encoded, for example `movie_id=12&studio=Redwood%3DRidge`. The stream, the
backfill and the query all derive it the same way, and a query can turn it back into a
`WHERE` clause. See the dev note
[One key per row](/notes/one-key-per-row/).

## The query path

`keyvector query` embeds the question, asks S3 Vectors for the nearest keys (optionally
filtered on metadata), and reads those rows from Keyspaces. A hit whose row was deleted
since is dropped, so results are always current rows.
