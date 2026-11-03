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

`--filter` narrows a search to rows whose metadata matches. Metadata is the columns listed in
`sync.metadata-columns`, stored with each vector.

```bash
keyvector query "lightweight rain shell" --filter '{"$and": [{"brand": "Nike"}, {"price": {"$lt": 50}}]}'
```

The filter uses S3 Vectors' syntax:
- equality, `$ne`;
- ranges (`$gt`, `$gte`, `$lt`, `$lte`);
- sets (`$in`, `$nin`), `$exists`;
- `$and` and `$or`;
- prefix matching (`$startsWith`).

Timestamps are stored as epoch milliseconds, so date ranges work:

```json
{"$and": [{"category": "documentary"}, {"release_date": {"$gt": 1577836800000}}]}
```

**Filters are applied before the vector search, not after.** On indexes in vector buckets
created on or after 30 September 2026 (S3 Vectors' `ENHANCED` mode, which includes everything
Keyvector's template creates), S3 Vectors first finds the rows that pass the filter, then
returns the nearest of those. A narrow filter doesn't leave you with fewer results than you
asked for, and you don't need to post-filter in your application. On older `CLASSIC` indexes,
filtering happens during the search and a narrow filter can return fewer than `--top-k`
results; AWS documents how to switch an index to `ENHANCED`.

### Limits

- **2 KB of filterable metadata per row, and up to 100 conditions per query.** Keep long text
  out of filterable metadata: store it with `sync.store-text-as` and declare that key
  non-filterable.
- **Only columns you listed can be filtered.** Adding a column later means a resync or a fresh
  backfill so existing vectors get it.
- **No keyword search.** The query text is matched by meaning. If exact-word matching or blended
  keyword and semantic ranking matters, see [Search options](/start/options/).

## From your code

Keyvector stores vectors under a reversible row key, so any S3 Vectors client can query the
index. Decode a hit's key as `column=value` pairs, percent-encoded and joined with `&`, then
read the row by primary key.
