---
title: Roadmap
description: What's planned next.
---

- ~~Embeddings with no model server~~: shipped as `embedder.type = "local"`
- **`keyvector verify`:** sample the table and the index and report drift
- **CloudWatch metrics and alarms:** lag, and embedded, skipped and dead-lettered counts
- **Chunking** long text into several vectors per row
- **More destinations:** OpenSearch Service, and DynamoDB's native vector search
- **Zero-downtime model migration:** write to a new index alongside the old one, then cut over
- **More sources with change streams:** DynamoDB Streams, Cassandra 5 CDC
- **A smaller container image**

Ideas and requests are welcome as
[GitHub issues](https://github.com/keyvector/keyvector/issues).
