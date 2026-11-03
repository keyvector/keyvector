---
title: "One key per row: turning a Cassandra primary key into a vector key"
description: Why the vector key is the whole primary key, written reversibly, and why both readers must normalize values the same way.
date: 2026-11-02
authors: yogesh
tags: [design]
excerpt: The same row arrives as strings from the stream and as typed values from CQL. If they don't produce the same key, the index quietly holds two vectors for one row.
---

Every vector in S3 Vectors has a key, a string of up to 1,024 characters. Keyvector has to
choose one for each row, and three different paths have to agree on it:
- the **stream**, when a row changes;
- the **backfill**, when it reads existing rows through CQL;
- the **query**, which turns a hit back into a row to read.

## Use the whole primary key, written so it can be read back

AWS's sample used a single text column as the key. That breaks the moment a table has a
composite primary key, which in Cassandra is most tables. Keyvector uses all the primary key
columns, sorted by name, written as `column=value` pairs joined by `&`, with each part
percent-encoded:

```text
movie_id=12&studio=Redwood%3DRidge
```

Sorting makes the key independent of column order. Percent-encoding means a value containing
`&` or `=` can't be mistaken for a separator. Our test table deliberately has studios called
"Northlight & Co" and "Redwood=Ridge". And because the key is reversible, a query hit can be
turned straight into `WHERE movie_id = ? AND studio = ?` without storing anything else.

## The harder part: the same value has to look the same twice

A row reaches Keyvector in two shapes:
- **from the change stream**, every scalar is a string: an `int` is `"42"`, a timestamp is
  text in a format AWS's docs don't pin down;
- **from CQL**, values are typed Java objects: `Integer`, `Instant`, `UUID`, `ByteBuffer`.

If the two produce different text for the same value, the backfill and a later update write
the same row under two keys, and the index silently holds both.

So both paths normalize to one form before a key is built:
- every integer type becomes a `Long`;
- `float` is read through its shortest decimal form, so `0.1f` is `0.1` and not
  `0.10000000149`;
- a timestamp becomes epoch milliseconds, whether it arrived as digits, as ISO-8601 or as
  CQL's `yyyy-MM-dd HH:mm:ss.SSSZ`;
- a blob becomes hex.

A unit test feeds every key type through both paths and requires identical keys. The live test
then backfilled 302 rows and streamed 61 changes over them, ending with exactly one vector per
row.

## Keys that are too long

S3 Vectors keys stop at 1,024 characters. A row whose encoded primary key is longer can never
be stored, and retrying won't change that, so Keyvector logs it and skips it rather than
stalling the shard behind it forever. In practice a primary key that long is rare.
