---
title: Configuration
description: The settings in keyvector.conf and their environment-variable overrides.
---

Configuration is a HOCON file; start from
[`keyvector.example.conf`](https://github.com/keyvector/keyvector/blob/main/keyvector.example.conf).
The `local` embedder caches models under `~/.cache/keyvector/models`, or `$KEYVECTOR_MODEL_CACHE`;
`HF_TOKEN` is sent for gated models.

Every value can be overridden by an environment variable named after its path, without the
`keyvector.` prefix, upper-cased, with dots and dashes as underscores. For example,
`keyvector.embedder.url` becomes `EMBEDDER_URL`. The CloudFormation template configures the
container entirely this way.

| Setting | Default | Meaning |
|---|---|---|
| `stream.keyspace-name`, `stream.table-name` | | The table to sync. |
| `stream.application-name` | | Names the KCL lease tables. One per table; changing it starts the stream over. |
| `sync.text-columns` | | Columns joined (blank line between) as the text to embed. |
| `sync.text-template` | | Or a template, e.g. `"{title}: {overview}"`. Set exactly one of the two. |
| `sync.max-text-chars` | `8000` | Text is cut to this length. |
| `sync.metadata-columns` | `[]` | Columns stored with each vector, for filtering. |
| `sync.store-text-as` | | Also store the embedded text under this metadata key (make it non-filterable). |
| `sync.on-empty-text` | `delete` | `delete` the vector or `skip` when a row's text becomes empty. |
| `sync.key-columns` | | Only if stream records carry no primary key (they normally do). |
| `embedder.type` | `bedrock` | `local` (in process), `tei`, `bedrock` or `openai`. The example config uses `local`. |
| `embedder.model` | Titan V2 | A Hugging Face repo id (`local`), or a Bedrock or OpenAI model id. |
| `embedder.model-revision` | `main` | `local`: the Hugging Face revision. Pin a commit hash. |
| `embedder.model-path` | | `local`: a directory holding the model's files; nothing is downloaded. |
| `embedder.onnx-file` | | `local`: the ONNX file inside the model, if not `onnx/model.onnx` or `model.onnx`. |
| `embedder.max-tokens` | the model's | `local`: truncate text to this many tokens. |
| `embedder.threads` | all cores | `local`: ONNX Runtime threads. |
| `embedder.dimensions` | | Must equal the model's output and the index. |
| `embedder.url` | | TEI base URL, or an OpenAI-compatible base URL. |
| `embedder.api-key-env` | | Name of the environment variable holding an API key; never the key itself. |
| `embedder.batch-size` | `32` | Texts per request (Titan: always 1; Cohere: at most 96). |
| `embedder.concurrency` | `4` | Requests in flight. |
| `embedder.requests-per-minute` | `0` (Titan: `60`) | Most requests started per minute; 0 means no limit. |
| `embedder.document-prefix`, `query-prefix` | | For models that want them. |
| `sink.vector-bucket`, `sink.vector-index` | | The S3 Vectors index. |
| `processor.max-attempts` | `10` | After this many failures a batch goes to the dead-letter queue, if set. |
| `processor.dead-letter-queue-url` | | SQS queue for batches that keep failing. Without one, the shard keeps retrying. |
