---
title: Roadmap
description: What's planned next.
---

- ~~Embeddings with no model server~~: shipped as `embedder.type = "local"`
- ~~Chunking long text~~ and ~~reranking~~: shipped as `sync.chunk-words` and `rerank.model` ([guide](/guides/long-text/))
- **Open-source Cassandra 5:** follow Debezium's change topic in Kafka and keep vectors in a companion table with Cassandra's own vector index
- **Hybrid search:** keyword and vector together
- **`keyvector verify`:** sample the table and the index and report drift
- **CloudWatch metrics and alarms:** lag, and embedded, skipped and dead-lettered counts
- **More destinations:** OpenSearch Service, and DynamoDB's native vector search
- **Zero-downtime model migration:** write to a new index alongside the old one, then cut over
- **More sources with change streams:** DynamoDB Streams
- **A smaller container image**

Ideas and requests are welcome as
[GitHub issues](https://github.com/keyvector/keyvector/issues).
