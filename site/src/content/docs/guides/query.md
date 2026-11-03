---
title: Query
description: Semantic search with metadata filters, returning current rows from Keyspaces.
---

```bash
keyvector query "astronauts stranded in space" --top-k 5
keyvector query "astronauts stranded in space" --filter '{"genre":"sci-fi"}'
keyvector query "astronauts stranded in space" --no-rows   # keys and metadata only
```

Each hit is one JSON line: the key, the distance, the stored metadata and the row as Keyspaces
holds it now. A hit whose row has since been deleted is dropped.

## Filters

`--filter` takes S3 Vectors' filter syntax over the columns listed in `sync.metadata-columns`.
Timestamps are stored as epoch milliseconds, so date ranges work with `$gt` and `$lt`:

```json
{"$and": [{"genre": "documentary"}, {"release_date": {"$gt": 1577836800000}}]}
```

S3 Vectors allows 2 KB of filterable metadata per vector.

## From your code

Keyvector stores vectors under a reversible row key, so any S3 Vectors client can query the
index. Decode a hit's key as `column=value` pairs, percent-encoded and joined with `&`, then
read the row by primary key.
