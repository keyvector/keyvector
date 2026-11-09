---
title: Long text and reranking
description: Split long rows into chunks with one vector each, and rerank query results with a cross-encoder for more precise answers.
---

Two settings for when rows hold more than a sentence or two: **chunking**, so a long text is
searchable all the way through, and **reranking**, so the best of the matches comes first.

## Chunking

An embedding model reads a limited number of tokens: all-MiniLM-L6-v2 stops at 256 (about 190
English words) and ignores the rest. A row holding an article, a transcript or a support ticket
thread is then only searchable by its opening. Chunking splits the text and stores one vector per
chunk:

```hocon
sync {
  text-columns = ["title", "body"]
  max-text-chars = 200000   # raise this, or long texts are cut before they are chunked
  chunk-words = 150         # about 200 tokens: under MiniLM's 256
  # chunk-overlap-words = 30  (default: a fifth of chunk-words)
  # max-chunks = 100
}
```

- **Words, not tokens,** so the setting works with every embedder. English runs about 1.3 tokens a
  word: keep `chunk-words` × 1.3 under the model's limit.
- **Sentences stay whole** where they fit. A chunk ends at a sentence or paragraph boundary; only a
  sentence longer than a chunk is cut.
- **Chunks overlap.** Each starts with the last `chunk-overlap-words` of the one before, so an idea
  that crosses a boundary is still found.
- **A short text is unchanged.** A row that fits in one chunk is stored as a single chunk with the
  exact text.

### What is stored

A row's chunks are keyed `<row key>#0`, `<row key>#1` and so on. Each chunk carries the row's
metadata columns, plus `kv_chunk` (its number) and `kv_chunks` (how many the row has). With
`sync.store-text-as`, each chunk stores its own text, not the whole row's.

The sync rules stay the same, per row:
- **Text changed:** the row's chunks are embedded again. If the new text has fewer chunks, the extra
  ones are deleted. Keyvector reads the count stored on chunk 0 before writing, so this works for a
  backfill and for a stream without old images too.
- **Only metadata changed:** every chunk keeps its stored vector, with nothing embedded.
- **Row deleted or expired:** every chunk is deleted.

Chunk 0 is deleted last and written last. A batch that fails halfway therefore leaves a count that
still covers every chunk, and the retry finishes the job. The tests replay exactly that case.

### Turning it on for an existing index

Set `chunk-words` and run `keyvector backfill`. Each row's single vector is replaced by its chunks;
the old unchunked vector is deleted as each row is rewritten. In CloudFormation, set the
`ChunkWords` and `MaxTextChars` parameters. Changing `chunk-words` later works the same way: a
backfill rewrites every row with the new chunks.

### Queries

A query can match several chunks of the same row. Each row is returned once, at its best chunk,
and the result says which chunk matched:

```json
{"key":"paper_id=2401.01234","chunk":3,"distance":0.31,"metadata":{...},"row":{...}}
```

Keyvector asks the index for three times as many vectors as rows wanted (at most 100), so that
`--top-k` distinct rows remain after duplicates are folded together.

## Reranking

Vector search compares the query's vector with each row's, which is fast but approximate. A
**cross-encoder** reads the query and a candidate text *together* and scores how well the text
answers the query: slower, and usually more precise. Keyvector uses one at query time, on the
best candidates the vector search returns:

```hocon
rerank {
  model = "cross-encoder/ms-marco-MiniLM-L6-v2"
  model-revision = "233902d25c440f23af6f7d6e94d2946bac0bee0a"
  candidates = 50
}
```

```bash
keyvector query "how were gravitational waves first detected?" --top-k 5
keyvector query "..." --no-rerank      # compare with plain vector ranking
```

Each result then has a `score` (higher is better) next to its `distance`, and results are in
score order.

- **It runs in process,** on ONNX Runtime like the [local embedder](/guides/models/). The model is
  downloaded once and cached; `keyvector fetch-model REPO --reranker` or the Dockerfile's
  `RERANK_MODEL` build argument bakes it into an image.
- **It reads current text.** The reranker reads the row as Keyspaces holds it now, or with
  chunking, the matching chunk rebuilt from that row. With `--no-rows`, it reads the text stored
  under `sync.store-text-as`.
- **It matches the reference.** Its scores equal Hugging Face text-embeddings-inference's for the
  same model to within 0.00001, including a text long enough to be truncated. CI repeats that
  comparison on every push.
- **It costs about 75 ms for 50 candidates** on a 10-core laptop CPU. Lower `candidates` to trade
  precision for speed.
- **Only `keyvector query` uses it.** The stream connector never loads the reranker.

Any Hugging Face cross-encoder with an ONNX export and a single output score works, for example
`BAAI/bge-reranker-base` or `mixedbread-ai/mxbai-rerank-xsmall-v1`. `keyvector check` loads the
configured reranker and confirms it ranks a relevant text above an unrelated one.
