---
title: Search options for Keyspaces
description: The three ways to add semantic search to Amazon Keyspaces data, how Keyvector compares with OpenSearch and with moving to a database that has vector search built in, and how to choose.
---

Amazon Keyspaces has no vector search of its own. Cassandra's data model is built for
wide-column rows, not high-dimensional vectors, so semantic search means pairing Keyspaces with
something that can search vectors. AWS documents three ways to do that.

1. **Stream the data into Amazon OpenSearch Service.** Capture changes with the table's change
   stream (or an API and Lambda layer), embed the text, and index records and vectors in
   OpenSearch. AWS describes the pattern in
   [Enable advanced search capabilities for Amazon Keyspaces data by integrating with Amazon OpenSearch Service](https://aws.amazon.com/blogs/big-data/enable-advanced-search-capabilities-for-amazon-keyspaces-data-by-integrating-with-amazon-opensearch-service/).
2. **Stream the table's changes into Amazon S3 Vectors.** Embed changed rows and store the
   vectors in an S3 vector index, then read the matching rows back from Keyspaces. AWS shows the
   pattern in
   [Stream live data from Amazon Keyspaces to S3 Vectors](https://aws.amazon.com/blogs/database/stream-live-data-from-amazon-keyspaces-to-s3-vector-for-real-time-ai-applications/).
   **Keyvector is a production implementation of this option.**
3. **Move the vector-heavy workload to a database with vector search built in**: DynamoDB
   ([now with native vector search](https://aws.amazon.com/blogs/database/build-semantic-search-with-native-vector-support-in-amazon-dynamodb/)),
   MemoryDB or ElastiCache (in-memory, very low latency), or a Cassandra-compatible database
   with vectors such as DataStax Astra DB or ScyllaDB.

## How they compare

| | Keyvector (option 2) | Amazon OpenSearch (option 1) | A database with vector search (option 3) |
|---|---|---|---|
| **Fit** | Adds semantic search beside Keyspaces | A dedicated search cluster beside Keyspaces | Replaces Keyspaces for this workload |
| **Data movement** | No migration. Vectors and the columns you filter on go to an S3 vector index in your account; rows are read from Keyspaces | Records are copied into OpenSearch and indexed there | Data moves out of Keyspaces |
| **Search** | Semantic (nearest-neighbor) search with metadata filters: equality, ranges, sets, prefixes, `and`/`or` | **Hybrid**: keyword (BM25) and semantic scoring combined, full-text queries, aggregations, rich filters | Varies. DynamoDB and MemoryDB search vectors with filters natively |
| **Keeping it in sync** | Built in: inserts, updates, deletes and TTL expiries from the change stream, with retries and a dead-letter queue | You build or adopt the pipeline (for example OpenSearch Ingestion), including how deletes and re-embedding are handled | None needed, since vectors live with the data, but your application has to create the embeddings |
| **What you run** | One small container per table on ECS; S3 Vectors is serverless | An OpenSearch domain, or Serverless capacity, which you size and pay for while it runs | Another primary database to operate and migrate to |
| **Cost shape** | Pay for embeddings only when text changes, S3 Vectors storage and queries, and the container | Cluster or serverless capacity, billed continuously, plus storage | Varies. DynamoDB is pay per request; in-memory options cost more per GB |
| **License and control** | Open source (Apache-2.0), deployed into your VPC with CloudFormation | Fully managed AWS service | Fully managed services |

## Where the model fits

Keyvector and embedding models don't compete; they're two parts of the same system. The
model, for example a Sentence-Transformers model on Hugging Face TEI, or Titan on Bedrock,
reads text and turns it into a vector that captures its meaning. Keyvector is the pipeline
around it. It notices which rows changed, sends their text to the model, stores the vectors,
and at query time embeds the question, searches the index and reads the rows back. OpenSearch
can call the same models through its ML Commons plugin. The choice between Keyvector and
OpenSearch is about the search engine around the model, not the model.

## Choose Keyvector when

- Keyspaces is your source of truth and you don't want to change how your application writes.
- You want inserts, updates, deletes and expiries reflected automatically, without building a
  pipeline.
- Your queries are semantic, optionally narrowed by structured filters: a category, a tenant, a
  status, a date or price range.
- You want low running cost and no cluster to size.
- You want to choose the model, including an open model running privately in your VPC.

## Choose OpenSearch when

- **Keywords matter as much as meaning.** A search for "waterproof jacket" must reward listings
  containing those exact words, and rank them by a blend of keyword and semantic scores. Keyvector
  matches by meaning only: it has no keyword index.
- You need full-text features: phrase queries, fuzzy matching, highlighting, aggregations and
  facets.
- You need filters beyond what S3 Vectors supports, such as more than 100 conditions, or more
  than 2 KB of filterable fields per row.

A filter alone isn't a reason to choose OpenSearch. "Jackets similar to this one, price under 50,
brand Nike" works in Keyvector if `price` and `brand` are metadata columns. S3 Vectors applies
the filter before the vector search, so the results are the closest matches among the rows that
pass it. See [Query](/guides/query/).

## Choose a database with vector search when

- You're free to move the workload, and storing vectors next to the data in one system matters
  more than keeping Keyspaces.
- You need the lowest possible search latency (MemoryDB and ElastiCache keep vectors in memory).

Be aware that these databases store and search vectors but don't create them: your application,
or a pipeline like Keyvector, still has to embed every write. See
[Keyspaces with Keyvector vs DynamoDB's native vector search](/notes/keyvector-vs-dynamodb-vector-search/).
