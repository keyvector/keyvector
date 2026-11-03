---
title: Keyspaces with Keyvector vs DynamoDB's native vector search
description: AWS added vector search to DynamoDB. How it compares with Keyspaces plus Keyvector, and which part of the problem each one solves.
date: 2026-11-03
authors: yogesh
tags: [comparison, dynamodb]
excerpt: DynamoDB now searches vectors natively, but it doesn't make them. Keyvector's job is the part DynamoDB leaves to your code.
---

In 2026 AWS added native vector search to DynamoDB, and its Database Blog walked through
[building semantic search with it](https://aws.amazon.com/blogs/database/build-semantic-search-with-native-vector-support-in-amazon-dynamodb/)
over 1,000 arXiv paper abstracts. It's a good design, and it's worth being precise about what
it does and doesn't do, because Amazon Keyspaces still has no equivalent.

We rebuilt the same walkthrough on Keyspaces with Keyvector: same dataset, same model, same
queries. See [Build semantic search on Amazon Keyspaces](/guides/arxiv-tutorial/).

## Side by side

| | DynamoDB native vector search | Keyspaces with Keyvector |
|---|---|---|
| Where vectors live | In the table, as an attribute, with a vector index on it | In an S3 Vectors index, kept in sync with the table |
| **Who creates the embeddings** | **Your application**: in AWS's walkthrough, the app calls Bedrock and writes the vector with every `put_item` | **Keyvector**, from the table's change stream. Application code doesn't change |
| When text is updated or a row deleted | Your code re-embeds, or you build a stream and function to do it | Automatic: changed text is re-embedded, deleted and expired rows lose their vectors, and nothing else is touched |
| Search | One call, `SearchVectors`, returns the items with a score | Two steps: S3 Vectors returns the nearest keys, then the rows are read from Keyspaces |
| Filtering | Inline filters on attributes projected into the index | Metadata filters on the columns you choose |
| Distance | Cosine, dot product, Euclidean | Cosine, Euclidean |
| Model | Any; you supply the vectors | TEI with any open model, Bedrock, or an OpenAI-compatible API, configured per table |
| Billing | Per byte written into, searched in and stored in the vector index, on top of the table | S3 Vectors storage and queries, the embedding model, and one small container |
| Runs | Serverless, inside DynamoDB | In your account, on ECS, under a least-privilege role |

## What DynamoDB does better

It's built into the database, so a search returns the items themselves in one call, with no
second store and no second read. AWS describes it as scaling to trillions of vectors with
consistently low latency. If you're on DynamoDB, or free to move to it, that's hard to beat.

## What it leaves to you

DynamoDB stores and searches vectors; it doesn't create them. In AWS's walkthrough the
application generates each embedding with Bedrock before writing the item. That's fine for a
one-off load of 1,000 papers. In a real application, every code path that writes the text
also has to embed it. You also have to handle updates (re-embed only when the text changed),
deletes, retries when the model is throttled, and existing data. That's a synchronization
pipeline, the very thing AWS's post lists as the burden of a separate vector store.

That pipeline is exactly what Keyvector is. On Keyspaces it's the only way to get vector
search without moving your data. The same engine could also keep a DynamoDB vector index
filled from DynamoDB Streams, which is on the [roadmap](/project/roadmap/).

## So which?

- **On DynamoDB:** use its native vector search. Keep the embedding in your write path if
  that's simple for you; otherwise that's the gap a change-stream embedder fills.
- **On Keyspaces:** there's no native option. Keyvector gives you semantic search without a
  migration or new application code.
- **Choosing a database today, with vector search as a core need:** a database with it built
  in is simpler than two systems.
