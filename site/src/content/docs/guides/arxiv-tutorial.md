---
title: Build semantic search on Amazon Keyspaces
description: A walkthrough that mirrors AWS's DynamoDB vector search post on Amazon Keyspaces with Keyvector. Same dataset, same model, same queries, plus what happens when rows change.
---

AWS's Database Blog shows how to
[build semantic search with native vector support in Amazon DynamoDB](https://aws.amazon.com/blogs/database/build-semantic-search-with-native-vector-support-in-amazon-dynamodb/)
over research paper abstracts. This is the same walkthrough on **Amazon Keyspaces**, which has
no vector search of its own, using Keyvector. It uses the same dataset (the first 1,000 papers
of [arxiv-abstracts-2021](https://huggingface.co/datasets/gfissore/arxiv-abstracts-2021)), the
same model (Titan Text Embeddings V2, 1,024 dimensions), the same text (title and abstract) and
the same three queries.

Two things differ:

- **Your application contains no embedding code.** It writes ordinary rows. Keyvector follows
  the table's change stream, embeds each new or changed row, and keeps the index in step.
- **The walkthrough doesn't stop at the first load.** Steps 5 and 6 change and delete papers and
  show the index following along. That's the part a one-off load script doesn't have to deal
  with, and a real application does.

Every output below is from an actual run on 3 November 2026. The files are in
[`examples/arxiv-search`](https://github.com/keyvector/keyvector/tree/main/examples/arxiv-search).

## Prerequisites

- An AWS account, with Bedrock access to Titan Text Embeddings V2 in your Region.
- Java 17+ and Maven (to build Keyvector), and Python 3.12+ for the loader:
  `pip install cassandra-driver cassandra-sigv4 "boto3[crt]" certifi`.
- AWS credentials in your environment (`aws login` works).

## Step 1: Create the table, its change stream and the vector index

[`stack.yaml`](https://github.com/keyvector/keyvector/blob/main/examples/arxiv-search/stack.yaml)
creates:
- a keyspace `research`, with a table `papers` (`paper_id`, `title`, `abstract`, `authors`,
  `category`) and its change stream turned on with new and old images;
- an S3 vector bucket, with an index `papers` (1,024 dimensions, cosine distance).

```bash
aws cloudformation deploy --stack-name keyvector-papers --template-file examples/arxiv-search/stack.yaml
```

The whole stack is created in about a minute.

## Step 2: Start Keyvector

The configuration says what to embed and where to put it:

```hocon
sync {
  text-template = "{title}. {abstract}"   # the same text AWS's walkthrough embeds
  metadata-columns = ["category"]         # stored with each vector, for filtering
}
embedder { type = "bedrock", model = "amazon.titan-embed-text-v2:0", dimensions = 1024 }
sink { vector-bucket = "keyvector-papers-<account-id>", vector-index = "papers" }
```

Check everything, then start following the stream **before** loading data:

```bash
export SINK_VECTOR_BUCKET=keyvector-papers-<account-id>
keyvector check --config examples/arxiv-search/keyvector.conf
keyvector run   --config examples/arxiv-search/keyvector.conf &
```

```text
ok    text and metadata settings: template {title}. {abstract}
ok    CDC stream: arn:aws:cassandra:us-east-1:…:/keyspace/research/table/papers/stream/… (NEW_AND_OLD_IMAGES)
ok    table: primary key [paper_id]
ok    embedder: bedrock amazon.titan-embed-text-v2:0, 1024 dimensions
ok    vector index: s3vectors keyvector-papers-…/papers, 1024 dimensions
All checks passed.
```

## Step 3: Load the papers, with no embedding code

[`load.py`](https://github.com/keyvector/keyvector/blob/main/examples/arxiv-search/load.py)
streams the dataset and writes each paper as a plain row. Here is the whole write path:

```python
insert = session.prepare(
    "INSERT INTO papers (paper_id, title, abstract, authors, category) VALUES (?, ?, ?, ?, ?)")
for p in papers:
    session.execute(insert, (p["id"], clean(p["title"]), clean(p["abstract"]),
                             clean(p["authors"]), category_of(p)))
```

```text
$ python load.py
Wrote 1000 papers to research.papers        # 2 minutes
```

Compare this with
[AWS's step 2](https://aws.amazon.com/blogs/database/build-semantic-search-with-native-vector-support-in-amazon-dynamodb/),
where the loader calls Bedrock for every paper and writes the vector alongside it. Here that's
Keyvector's job. In your real application it means any code path that writes a paper, today or
later, gets it indexed without knowing embeddings exist.

### How long until it's searchable

- **The first start of a new deployment waits about 12 minutes** while KCL prepares its lease
  table. In our run, Keyvector took the stream's shards at 17:58. Later starts are immediate.
- **Then the embedding rate is your Titan quota.** Our new account was allowed 60 requests a
  minute, so the 1,000 papers took 17 minutes, the last landing at 18:14:52, with no throttling
  or retries. Accounts with a higher quota go faster: set `embedder.requests-per-minute` to match
  yours, or use a self-hosted model. See [Choose an embedding model](/guides/models/).

## Step 4: Search

```bash
keyvector query "detecting gravitational waves from black hole mergers" --top-k 3
```

Each result is the paper's current row, read back from Keyspaces:

```text
Query: "detecting gravitational waves from black hole mergers"
  1. Black hole puncture initial data with realistic gravitational wave content [gr-qc] (distance: 0.653)
  2. Gravitational Wave Signals from Chaotic System: A Point Mass with A Disk [gr-qc] (distance: 0.696)
  3. Search for gravitational-wave bursts in LIGO data from the fourth science run [gr-qc] (distance: 0.714)

Query: "improving the efficiency of solar cells"
  1. Efficiency of thin film photocells [cond-mat.mtrl-sci] (distance: 0.526)
  2. Genetic Optimization of Photonic Bandgap Structures [physics.optics] (distance: 0.742)
  3. Proposal for an Enhanced Optical Cooling System Test in an Electron Storage Ring [physics.acc-ph] (distance: 0.778)

Query: "quantum error correction for fault-tolerant computing"
  1. Entanglement of Subspaces and Error Correcting Codes [quant-ph] (distance: 0.695)
  2. Effects of Imperfect Gate Operations in Shor's Prime Factorization Algorithm [quant-ph] (distance: 0.715)
  3. Suppression of 1/f noise in one-qubit systems [quant-ph] (distance: 0.728)
```

Cosine distance: lower is closer. None of the top results contains the query's exact wording.

## Step 5: Filter

`category` is a metadata column, so a search can be limited to it. S3 Vectors applies the filter
before the vector search, so these are the closest astrophysics papers, not the closest papers
overall with the others thrown away:

```bash
keyvector query "detecting gravitational waves from black hole mergers" --top-k 3 \
  --filter '{"category":"astro-ph"}'
```

```text
  1. 0704.0297  Remnant evolution after a carbon-oxygen white dwarf merger [astro-ph] (distance: 0.769)
  2. 0704.0287  Search for Very High Energy Emission from Gamma-Ray Bursts using Milagro [astro-ph] (distance: 0.771)
  3. 0704.0626  Probing dark energy with steerable wavelets through correlation of WMAP and NV… [astro-ph] (distance: 0.812)
```

## Step 6: Change a paper, delete a paper

This is where a load script ends and an application begins. First, rewrite the abstract of paper
0704.0002, a combinatorics paper, so that it describes gravitational-wave detection. It's an
ordinary CQL update:

```sql
UPDATE papers SET abstract = 'We report the first detection of gravitational waves from the merger
  of two black holes, observed by a pair of laser interferometers.' WHERE paper_id = '0704.0002';
```

Keyvector saw the change and re-embedded that one row:

```text
Applied 1 changes: 1 embedded, 0 reused, 0 deleted, 0 unchanged, 0 rejected    # ~13 s after the write
```

The same query now ranks it first:

```text
  1. 0704.0002  Sparsity-certifying Graph Decompositions [math.CO] (distance: 0.276)
  2. 0704.0628  Black hole puncture initial data with realistic gravitational wave content [gr-qc] (distance: 0.653)
  3. 0704.0719  Gravitational Wave Signals from Chaotic System: A Point Mass with A Disk [gr-qc] (distance: 0.696)
```

Next, delete paper 0704.0001, a study of diphoton production at hadron colliders:

```sql
DELETE FROM papers WHERE paper_id = '0704.0001';
```

```text
Applied 1 changes: 0 embedded, 0 reused, 1 deleted, 0 unchanged, 0 rejected    # ~10 s after the delete
```

A query aimed squarely at it no longer finds it:

```text
Query: "calculation of prompt diphoton production cross sections at Tevatron and LHC energies"
  1. 0704.0642  Direct photons and dileptons via color dipoles [hep-ph] (distance: 0.644)
  2. 0704.0710  Photoproduction of pi0 omega off protons for E(gamma) < 3 GeV [nucl-ex] (distance: 0.732)
  3. 0704.0037  The discrete dipole approximation for simulation of light scattering by partic… [physics.optics] (distance: 0.806)
```

An update to a column that isn't embedded or filtered on, such as `authors`, costs nothing:
Keyvector compares the row before and after, and skips it.

## Cost

The whole walkthrough, including the 1,000 embeddings, a few dozen queries and about an hour of
running, costs well under a dollar:

- **Keyspaces:** about 1,000 writes, which the free tier covers.
- **Titan:** at $0.02 per million input tokens, 1,000 abstracts cost cents.
- **S3 Vectors:** storage and queries for 1,000 vectors cost a fraction of a cent.

Run on ECS instead of your machine, one small Keyvector container is the only thing that keeps
running.

## Clean up

```bash
aws cloudformation delete-stack --stack-name keyvector-papers
```

Then delete the three DynamoDB tables KCL created: `keyvector-papers-lease-table`,
`keyvector-papers-lease-table-CoordinatorState` and `keyvector-papers-lease-table-WorkerMetricStats`.
