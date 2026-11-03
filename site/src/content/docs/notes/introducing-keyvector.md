---
title: "Introducing Keyvector: semantic search for Amazon Keyspaces, without moving your data"
description: Amazon Keyspaces has no vector search. Keyvector is an open-source connector that adds it from your table's change stream, inside your own AWS account.
date: 2026-11-03
authors: yogesh
tags: [announcement]
featured: true
excerpt: Keyspaces has no vector search. Keyvector follows your table's change stream, embeds what changed, and keeps a vector index in step, so you can search your data by meaning without migrating it.
---

If your data lives in Amazon Keyspaces, you probably chose it to stop running Cassandra
yourself. Then someone asks for search that understands meaning:
- "find tickets like this one";
- product search that copes with how people actually describe things;
- retrieval for an AI assistant.

That's where Keyspaces stops: it has no vector type and no vector index. This year DynamoDB
got native vector search. Keyspaces didn't.

**Keyvector is an open-source connector that adds semantic search to Keyspaces without
moving your data.** It follows your table's change stream, embeds the text of every row that
changes, and keeps an Amazon S3 Vectors index in step with the table. A search embeds the
question, finds the nearest rows, and reads them back from Keyspaces, so results are always
current. It runs in your own AWS account, and it's Apache-2.0 on
[GitHub](https://github.com/keyvector/keyvector).

## The options you have today

AWS documents three ways to get semantic search over Keyspaces data:
1. stream it into OpenSearch;
2. stream it into S3 Vectors;
3. move the workload to a database with vector search built in.

Each is reasonable, and each costs something:
- **Moving databases** means redoing a migration you probably just finished.
- **OpenSearch** is the right tool when keyword matching matters as much as meaning, but it's
  a cluster to size and pay for while it runs.
- **Streaming into S3 Vectors** is the light option, but AWS's version of it is a
  [sample](https://github.com/aws-samples/sample-keyspaces-cdc-streams-connectors). It ignores
  deletes, skips a batch that fails, and handles only single-column keys.

Keyvector is that third pattern built to run unattended for months. There's a fuller comparison
on [Search options for Keyspaces](/start/options/).

## What it looks like

We rebuilt AWS's own
[DynamoDB vector search walkthrough](https://aws.amazon.com/blogs/database/build-semantic-search-with-native-vector-support-in-amazon-dynamodb/)
on Keyspaces: same 1,000 arXiv papers, same Titan model, same queries. The difference starts
with the loader. In the DynamoDB version the application calls Bedrock for every paper. With
Keyvector the application just writes rows:

```python
for p in papers:
    session.execute(insert, (p["id"], p["title"], p["abstract"], p["authors"], p["category"]))
```

Keyvector embeds them, and a query finds the right papers by meaning:

```text
$ keyvector query "detecting gravitational waves from black hole mergers" --top-k 3
  1. Black hole puncture initial data with realistic gravitational wave content [gr-qc]
  2. Gravitational Wave Signals from Chaotic System: A Point Mass with A Disk [gr-qc]
  3. Search for gravitational-wave bursts in LIGO data from the fourth science run [gr-qc]
```

The part a load script never has to deal with is change. We rewrote one paper's abstract
with an ordinary CQL `UPDATE`. About 13 seconds later it was re-embedded and ranked first for
that query. We deleted another paper, and about 10 seconds later its vector was gone. Changes
to columns you don't embed cost nothing: Keyvector compares the row before and after, and
skips them. The [full walkthrough](/guides/arxiv-tutorial/) shows every step with real output.

## Built to keep the index right

Most of the work went into the parts that fail quietly:
- **Nothing is skipped.** A batch that fails is retried with backoff and isn't acknowledged
  until it's applied. If it keeps failing, its rows go to a dead-letter queue to be resynced.
- **Deletes and expiries remove vectors.**
- **Any primary key works.** Composite keys of any CQL type become one reversible vector key,
  so the stream, the backfill and the query always agree on which vector is which row.
- **Filters run inside the search.** The columns you choose are stored with each vector, and
  S3 Vectors applies filters (category, tenant, price or date ranges) before the vector search.
- **Your choice of model:** an open Sentence-Transformers model on Hugging Face TEI, running
  privately in your VPC, or Amazon Bedrock, or any OpenAI-compatible API.
- **Least privilege.** The CloudFormation template gives the task read access to one table and
  write access to one index, and nothing else.

Before publishing, we ran it end to end against real AWS. A backfill, 61 live changes, a
restart, a model server stopped mid-run and a full stream replay all ended with the index
matching the table exactly. The run found four bugs, all fixed. The most useful finding: a new
AWS account's Titan quota can be as low as 60 embeddings a minute, so Keyvector now paces
itself rather than spend its time being throttled. That story is in
[What broke when we ran it against real AWS](/notes/what-broke-against-real-aws/).

## What it isn't

Keyvector matches by meaning. It has no keyword index, so if exact phrases or blended
keyword-and-semantic ranking matter to you, OpenSearch is the better fit. The index trails the
table by seconds, not transactionally. And it's a 0.1 preview: the connector is tested against
live AWS, but the one-click Fargate deployment, the dead-letter redrive and TTL expiry haven't
been run end to end yet. The [status page](/project/status/) is kept honest about that.

## Try it

- [Quick start](/start/quick-start/): index a table from your own machine.
- [Tutorial](/guides/arxiv-tutorial/): the arXiv walkthrough, start to finish, well under a dollar.
- [Deploy to your account](/guides/deploy/): one CloudFormation stack per table.

Next on the [roadmap](/project/roadmap/):
- **Embeddings with no model server at all**, run inside Keyvector itself.
- A **verify** command that reports any drift between the table and the index.
- **DynamoDB as a source**, to keep its new vector indexes filled.

If you run Keyspaces and want semantic search, I'd like to hear what you're trying to build.
[Open an issue](https://github.com/keyvector/keyvector/issues) with your use case, or star the
repo to follow along.
