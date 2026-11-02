# Keyvector

**Semantic search for Amazon Keyspaces, without moving your data.**

Amazon Keyspaces (for Apache Cassandra) has no vector search. Keyvector adds it from the
outside: it follows a table's change stream, embeds the text of every row that changes, and
keeps an Amazon S3 Vectors index in step with the table: inserts, updates, deletes and TTL
expiries. A query embeds the question, finds the nearest rows in the index, and reads those
rows back from Keyspaces, so results are always current.

It runs in **your** AWS account, on ECS Fargate, under an IAM role that can read one table
and write one index. Your text never leaves your account unless you choose a hosted embedding
API.

```
 Keyspaces table ──CDC stream──▶ keyvector run ──▶ embed ──▶ S3 Vectors index
   (cdc = true)                    │               │  (TEI sidecar running your
                                   │               │   Sentence-Transformers model,
                                   │               │   or Bedrock, or OpenAI-compatible)
                                   └─ failed batch ─▶ SQS dead-letter queue ─▶ keyvector redrive

 keyvector query "..." ──▶ embed ──▶ nearest keys ──▶ SELECT rows from Keyspaces ──▶ results
```

## What it does that a script doesn't

- **Never skips a change.** A batch that fails is retried with backoff; after `max-attempts`
  it is parked in a dead-letter queue and the shard moves on. Without a queue the shard waits
  rather than lose a row. (The AWS sample this is forked from logged the failure and then
  checkpointed past it.)
- **Deletes and expiries remove vectors.** A deleted or TTL-expired row's vector is deleted.
- **Pays to embed only when the text changed.** An update to a column you don't embed costs
  nothing. A change to a metadata column rewrites the stored vector without re-embedding.
- **One key per row, from the primary key.** Composite keys of any CQL scalar type, encoded
  reversibly, so the stream, the backfill and the query all agree on which vector is which row.
- **Backfill** the rows a table had before the stream was switched on; resumable with
  `--skip-existing`.
- **Your model, or a managed one.** Hugging Face text-embeddings-inference (any
  Sentence-Transformers model) as a sidecar in the same task, Amazon Bedrock (Titan Text
  Embeddings v2, Cohere Embed), or any OpenAI-compatible `/embeddings` endpoint.
- **Least-privilege by construction.** The CloudFormation template grants read on one table
  and its stream, the connector's own lease tables, one vector index and one queue.

## Quick start

You need: a Keyspaces table with CDC turned on, an S3 vector bucket and index whose dimension
matches your model, Java 17+ and Maven (or Docker).

```bash
# 1. Turn on the stream (once). Keyvector needs new and old images to skip unchanged text.
#    In cqlsh:  ALTER TABLE media.movies WITH cdc = true
#               AND CUSTOM_PROPERTIES = {'cdc_specification': {'view_type': 'NEW_AND_OLD_IMAGES'}};

# 2. Run a model locally (or skip this and use embedder.type = "bedrock").
docker run -p 8080:80 ghcr.io/huggingface/text-embeddings-inference:cpu-1.8 \
  --model-id sentence-transformers/all-MiniLM-L6-v2

# 3. Configure and build.
cp keyvector.example.conf keyvector.conf      # set keyspace, table, columns, bucket, index
mvn -q -DskipTests package
alias keyvector='java -jar keyvector-app/target/keyvector-app-0.1.0-SNAPSHOT.jar'

# 4. Check everything before writing anything, then go.
keyvector check
keyvector run &                 # follows the stream
keyvector backfill              # indexes the rows that already existed
keyvector query "a heist that goes wrong" --top-k 5
```

`keyvector check` reads only: it confirms the stream exists, the table is readable, the model
answers with the dimensions you configured, and the index has the same dimensions.

## Deploy to your account

[`deploy/keyvector.yaml`](deploy/keyvector.yaml) creates the vector bucket and index
(optional), a dead-letter queue, the IAM roles, and an ECS Fargate service running Keyvector,
with the model in the same task when `Embedder` is `tei`. See [deploy/README.md](deploy/README.md).

## Commands

| Command | What it does |
|---|---|
| `run` | Follow the CDC stream and keep the index in sync. The ECS service runs this. |
| `backfill [--skip-existing] [--limit N]` | Index the rows the table already had. Run it after `run` is up. |
| `resync KEY...` | Re-read named rows from Keyspaces and fix their vectors (or delete them if the row is gone). |
| `redrive [--queue-url U]` | Resync every row parked in the dead-letter queue. |
| `query TEXT [--top-k N] [--filter JSON] [--no-rows]` | Semantic search; one JSON line per hit, with the current row. |
| `check` | Validate the configuration against AWS without writing anything. |

## Configuration

Everything is in [`keyvector.example.conf`](keyvector.example.conf), and every value can be
set by an environment variable instead (`keyvector.embedder.url` → `EMBEDDER_URL`). The
settings that matter:

| Setting | Meaning |
|---|---|
| `sync.text-columns` / `sync.text-template` | What each row is embedded as: columns joined, or `"{title}: {overview}"`. |
| `sync.metadata-columns` | Columns stored with each vector, for `--filter`. Timestamps become epoch ms so ranges work. |
| `sync.on-empty-text` | `delete` (default) or `skip` when a row's text becomes empty. |
| `embedder.type` | `tei`, `bedrock` or `openai`. |
| `embedder.dimensions` | Must equal the model's output and the index's dimension; checked at startup. |
| `embedder.document-prefix` / `query-prefix` | For models that want them (E5: `passage: ` / `query: `). |
| `processor.max-attempts` / `dead-letter-queue-url` | When a failing batch is parked rather than retried. |

## Guarantees and limits

- **At least once, in order per row.** A batch may be applied twice after a crash; every write
  is idempotent, so that is harmless. Changes to one row arrive on one shard, in order.
- **The index trails the table by seconds**, not transactionally. A query reads rows back
  from Keyspaces, so a returned row is current; a row changed in the last few seconds may
  rank by its previous text.
- **Backfill and stream can race on one row**: if the backfill reads a row, the stream then
  applies a newer change, and the backfill writes last, the older text stays indexed until
  that row changes again. `keyvector resync KEY` fixes it. Run the backfill once, after the
  stream is running.
- **Vector keys are at most 1,024 characters** (S3 Vectors). A row whose encoded primary key
  is longer is skipped and logged, not retried.
- **Filterable metadata is at most 2 KB per vector** (S3 Vectors). Store long text with
  `sync.store-text-as`, which the template makes non-filterable.
- **One table per deployment.** Run one stack per table.
- **Changing the model means a new index.** Vectors from two models are not comparable; create
  a new index, point a second deployment at it, backfill, then switch queries over.

## Roadmap

- OpenSearch Service and DynamoDB (native vector search) as sinks
- Chunking long text into several vectors per row
- `keyvector verify`: sample the table and the index and report drift
- Zero-downtime model migration (dual-write to a new index, then cut over)
- CloudWatch metrics and alarms: lag, embedded/skipped/dead-lettered counts
- Other sources with change streams: DynamoDB Streams, Cassandra 5 CDC

## Project status

Pre-release (0.1). The unit tests cover the sync rules, key encoding, the embedders and the
retry/dead-letter behaviour (`mvn test`). It has **not yet run end to end against a live
Keyspaces stream**. That is the next milestone, and until then treat it as a preview.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Keyvector started as a fork
of [aws-samples/sample-keyspaces-cdc-streams-connectors](https://github.com/aws-samples/sample-keyspaces-cdc-streams-connectors)
(MIT-0). It is not affiliated with Amazon Web Services.
