---
title: Quick start
description: Index a Keyspaces table and run your first semantic query, from your own machine.
---

You need:
- a Keyspaces table
- an S3 vector bucket and index whose dimension matches your model
- Java 17+ and Maven, or Docker
- AWS credentials (`aws login`, SSO or a profile)

## 1. Turn on the table's change stream

Keyvector needs new and old row images. That's the default view type, and it lets Keyvector
skip updates that leave the text unchanged. In cqlsh:

```sql
ALTER TABLE media.movies WITH cdc = true
  AND CUSTOM_PROPERTIES = {'cdc_specification': {'view_type': 'NEW_AND_OLD_IMAGES'}};
```

:::caution
A stream's view type can't be changed later. Keyvector refuses to start on a `KEYS_ONLY` or
`OLD_IMAGE` stream, because every change would look like a delete.
:::

## 2. Configure and build

The example configuration embeds with
[all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) **inside
Keyvector**, so there's no model server to run and no API key. The model is downloaded once
(about 90 MB) and cached. Create your vector index with **384 dimensions** to match it, or see
[Choose an embedding model](/guides/models/) for the alternatives.

```bash
git clone https://github.com/keyvector/keyvector && cd keyvector
cp keyvector.example.conf keyvector.conf   # set keyspace, table, columns, bucket, index
mvn -q -DskipTests package
alias keyvector='java -jar keyvector-app/target/keyvector-app-0.1.0-SNAPSHOT.jar'
```

## 3. Check, run, backfill, query

```bash
keyvector check                  # reads only; five checks, all must pass
keyvector run &                  # follows the stream
keyvector backfill               # indexes the rows that already existed
keyvector query "a heist that goes wrong" --top-k 5
```

`check` confirms the stream exists and has new images, the table is readable, the model
answers with the dimensions you configured, and the index has the same dimensions.

:::note
The first time a deployment starts, the stream worker spends about 12 minutes preparing its
DynamoDB lease table before changes flow. Later starts are immediate. The backfill doesn't
wait for it.
:::
