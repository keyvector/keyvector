---
title: Guarantees and limits
description: Delivery semantics, consistency, and the AWS limits that apply.
---

## Guarantees

- **At least once, in order per row.** A batch may be applied twice after a crash. Every write
  is idempotent, so that's harmless. Changes to one row arrive on one shard, in order.
- **Nothing is skipped.** A failing batch is retried until it's applied or parked in the
  dead-letter queue. The worker's position moves past a batch only after one of those happens.
- **A failed batch writes nothing.** Vectors are written only after every embedding in the
  batch succeeded.

## Consistency

The index trails the table by seconds. A query reads rows back from Keyspaces, so a returned
row is current, but a row changed in the last few seconds may still be ranked by its previous
text.

## Limits

- **Vector keys are at most 1,024 characters** (S3 Vectors). A row whose encoded primary key is
  longer is skipped and logged, not retried.
- **Filterable metadata is at most 2 KB per vector.** Store long text with `sync.store-text-as`
  and make that key non-filterable.
- **Titan Text Embeddings V2 on a new account: as few as 60 requests a minute**, one text
  each. Check your quota in Service Quotas. See
  [Choose an embedding model](/guides/models/).
- **First start takes about 12 minutes** while KCL prepares its lease table.
- **One table per deployment.**
- **Changing the model means a new index.**
