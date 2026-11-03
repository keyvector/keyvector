---
title: Backfill, resync and redrive
description: Indexing rows that existed before the stream, fixing individual rows, and draining the dead-letter queue.
---

## Backfill

The change stream only carries changes made after it was turned on. `keyvector backfill`
indexes the rows the table already had:

```bash
keyvector backfill                 # every row
keyvector backfill --skip-existing # leave rows that already have a vector (resume a stopped backfill)
keyvector backfill --limit 1000    # try it on a sample first
```

**Run it after `keyvector run` is up**, so a row updated during the backfill is also on the
stream, and the stream's later write wins.

Even so, there's a narrow race. The backfill can read a row, the stream can then apply a
newer change to it, and the backfill can write last. If that happens, the older text stays
indexed until the row changes again. `keyvector resync` fixes it.

## Resync

```bash
keyvector resync 'movie_id=12&studio=Aurora%20Pictures'
```

This re-reads each named row from Keyspaces and writes its current state, or deletes the vector
if the row is gone. Reading the current row, instead of replaying an old change, is what makes
it safe to run at any time.

## Redrive

When a batch fails `max-attempts` times and a dead-letter queue is configured, the rows'
keys (not their contents) are parked there and the shard moves on. `keyvector redrive`
resyncs every parked row and deletes each message only after its rows were applied.
