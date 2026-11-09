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

## Why Keyvector

**It's for teams whose data lives in Amazon Keyspaces and who now want to search it by
meaning**: "find tickets like this one", product search that understands the question,
retrieval for an AI assistant (RAG), "more like this" recommendations, near-duplicate
detection. Keyspaces can't do any of that today, and the usual ways to get it all cost more
than they look.

| Your other option | What it costs you |
|---|---|
| Migrate to a Cassandra with vector search (DataStax Astra DB, ScyllaDB, self-managed Cassandra 5) | Redoing a database migration you probably just finished, and leaving a managed AWS service |
| Move the data to DynamoDB or OpenSearch | Rewriting your data layer and running two databases during the move |
| Build the sync pipeline yourself | Weeks of work on the parts that are easy to get wrong (deletes, retries, restarts, backfill, composite keys), then owning it on call |
| Wait for AWS to add vectors to Keyspaces | An unknown wait |

With Keyvector you keep everything as it is and add search beside it:

- **No migration.** Keyspaces stays your source of truth, and your application keeps writing
  to it unchanged. Turn on the table's change stream and the index follows.
- **The index stays right.** Every insert, update, delete and expiry reaches the index, a
  failed batch is retried rather than skipped, and a restart picks up where it left off.
  [Tested end to end against live AWS](#project-status), including a model-server outage
  mid-run.
- **Results are never stale.** A query reads the matching rows back from Keyspaces, so you
  get what the table holds now, not a copy taken when the row was indexed.
- **Your data stays in your account.** It runs on your ECS, under an IAM role that can read
  one table and write one index. With a self-hosted model, your text never leaves your VPC.
- **Pick your model.** Any open Sentence-Transformers model on Hugging Face TEI (yours to
  fine-tune, no per-token charges, no request quota), Amazon Bedrock, or any OpenAI-compatible
  API, chosen per table in the config.
- **Cheap to run.** One small container per table. Rows are embedded only when their text
  changes, so updates to other columns cost nothing, and vectors live in S3 Vectors, which
  AWS prices for storage at scale rather than for always-on servers.
- **Open source, Apache-2.0.** Read every line before it touches your data, run it however
  you like, and change it if you need to.

**When it isn't the right fit:** if you are free to change databases, a database with
built-in vector search is simpler than running two systems. If you need search results to
be transactionally consistent with writes, note that the index trails the table by seconds.
And if you are on a database other than Keyspaces, this version won't help yet (see the
roadmap).

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
- **Embeddings included.** By default Keyvector runs a Sentence-Transformers model itself, in
  process with ONNX Runtime: no model server, no API key, no per-token cost, and text that never
  leaves the process. Or use Hugging Face TEI, Amazon Bedrock (Titan, Cohere), or any
  OpenAI-compatible `/embeddings` endpoint.
- **Long text and reranking.** `sync.chunk-words` splits long rows into overlapping chunks, one
  vector each, and removes a row's stale chunks when its text shrinks. `rerank.model` re-scores
  query results with a cross-encoder, in process, matching Hugging Face TEI's scores. See
  [Long text and reranking](https://keyvector.dev/guides/long-text/).
- **Least-privilege by construction.** The CloudFormation template grants read on one table
  and its stream, the connector's own lease tables, one vector index and one queue.

## Quick start

You need: a Keyspaces table with CDC turned on, an S3 vector bucket and index whose dimension
matches your model, Java 17+ and Maven (or Docker).

```bash
# 1. Turn on the stream (once). Keyvector needs new and old images to skip unchanged text.
#    In cqlsh:  ALTER TABLE media.movies WITH cdc = true
#               AND CUSTOM_PROPERTIES = {'cdc_specification': {'view_type': 'NEW_AND_OLD_IMAGES'}};

# 2. Configure and build. The example config embeds with all-MiniLM-L6-v2 inside Keyvector,
#    so there is no model server to run; it's downloaded and cached on first use.
cp keyvector.example.conf keyvector.conf      # set keyspace, table, columns, bucket, index
mvn -q -DskipTests package
alias keyvector='java -jar keyvector-app/target/keyvector-app-0.1.0-SNAPSHOT.jar'

# 3. Check everything before writing anything, then go.
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
| `query TEXT [--top-k N] [--filter JSON] [--no-rows] [--no-rerank]` | Semantic search; one JSON line per hit, with the current row. Reranked when `rerank.model` is set. |
| `check` | Validate the configuration against AWS without writing anything. |
| `fetch-model REPO [--revision R] [--reranker]` | Download a model for `embedder.type = "local"` (or a `rerank.model`) into the cache (used to bake it into an image). |

## Configuration

Everything is in [`keyvector.example.conf`](keyvector.example.conf), and every value can be
set by an environment variable instead (`keyvector.embedder.url` → `EMBEDDER_URL`). The
settings that matter:

| Setting | Meaning |
|---|---|
| `sync.text-columns` / `sync.text-template` | What each row is embedded as: columns joined, or `"{title}: {overview}"`. |
| `sync.metadata-columns` | Columns stored with each vector, for `--filter`. Timestamps become epoch ms so ranges work. |
| `sync.on-empty-text` | `delete` (default) or `skip` when a row's text becomes empty. |
| `embedder.type` | `local` (in process), `tei`, `bedrock` or `openai`. |
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
- **Titan Text Embeddings V2 can be slow on a new account.** Ours was allowed 60 requests a
  minute; the quota page marks it not adjustable, though accounts with more history get more
  (check yours in Service Quotas). Titan takes one text per request, so at 60 a minute it
  embeds one row a second, about 3,600 rows an hour.
  Keyvector paces itself to that by default (`embedder.requests-per-minute`). For a large
  backfill or a busy table use TEI, Cohere Embed on Bedrock (96 texts per request) or
  Bedrock batch inference.
- **The first start of a new deployment takes about 12 minutes** before changes flow: KCL
  adds an index to its DynamoDB lease table and waits for it. Later starts are immediate.
- **Changing the model means a new index.** Vectors from two models are not comparable; create
  a new index, point a second deployment at it, backfill, then switch queries over.

## Roadmap

- OpenSearch Service and DynamoDB (native vector search) as sinks
- `keyvector verify`: sample the table and the index and report drift
- Zero-downtime model migration (dual-write to a new index, then cut over)
- CloudWatch metrics and alarms: lag, embedded/skipped/dead-lettered counts
- Open-source Cassandra 5: Debezium's change topic in, a companion table with Cassandra's own vector index out
- Hybrid keyword and vector search
- Other sources with change streams: DynamoDB Streams

## Project status

Pre-release (0.1). The unit tests cover the sync rules, key encoding, the embedders and the
retry and dead-letter behaviour, chunking and reranking (`mvn test`, 259 tests).

**Tested end to end against live AWS on 2 November 2026** (Keyspaces CDC in us-east-1,
S3 Vectors, Bedrock Titan V2 and a local TEI with all-MiniLM-L6-v2), with the kit in
[`e2e/`](e2e/README.md):

- backfill of 302 rows gave exactly 302 vectors, keys with spaces, `&` and `=` included;
- 61 live changes were all accounted for: 31 embedded, 10 metadata-only changes reused the
  stored vector, 10 changes to an unembedded column wrote nothing, 10 deletes removed vectors;
  only the rows whose text changed got new vectors;
- stopping the connector, changing 5 rows and restarting applied exactly those 5;
- stopping the model server mid-run lost nothing: the batches waited and landed when it came back;
- a second connector replaying the whole stream on top of a backfill converged on the same index;
- semantic queries returned the expected rows first, and metadata filters worked.

**Repeated on 3 November 2026 with the in-process embedder** (`embedder.type = "local"`, no
model server): 302 rows backfilled in 17 seconds, the same 61-change split, exactly 5 changes
after a restart, and every test query's target first.

**Chunking and reranking, 9 November 2026:** 302 rows became exactly the predicted 914 chunk
vectors; live changes, shortened texts, deletes and a restart left exactly the predicted chunks
(942) with none stale; reranked queries put every target first.

Not yet tested live: the Fargate deployment (`deploy/keyvector.yaml`), the dead-letter queue
and redrive, and TTL expiry.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Keyvector started as a fork
of [aws-samples/sample-keyspaces-cdc-streams-connectors](https://github.com/aws-samples/sample-keyspaces-cdc-streams-connectors)
(MIT-0). It is not affiliated with Amazon Web Services.
