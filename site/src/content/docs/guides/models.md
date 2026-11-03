---
title: Choose an embedding model
description: Run a Sentence-Transformers model inside Keyvector, or use Hugging Face TEI, Amazon Bedrock, or an OpenAI-compatible API, and what each costs in throughput.
---

Set `embedder.type` in the configuration. Whichever you choose, `embedder.dimensions` must equal
the model's output and the vector index's dimension; Keyvector checks this at startup.

| | `local` | `tei` | `bedrock` | `openai` |
|---|---|---|---|---|
| What | A Sentence-Transformers model run **inside Keyvector** with ONNX Runtime | Hugging Face text-embeddings-inference, a separate model server | Titan Text Embeddings V2, Cohere Embed | OpenAI, or any server speaking its `/embeddings` API (vLLM, Infinity, Ollama) |
| Where your text goes | Nowhere: it never leaves the process | A container beside Keyvector | Bedrock, in your account | That API |
| Per-token cost | None | None | Yes | Yes |
| Throughput | Your CPU: we measured 546 texts a second with all-MiniLM-L6-v2 on a 10-core machine | Your hardware, including GPUs | Your Bedrock quota; Titan takes one text per request | The provider's |
| Best for | Getting started, privacy, small and medium models | Large models, GPUs, scaling the model separately | Managed models, no compute to size | Models only available through an API |

## Local: embeddings inside Keyvector

```hocon
embedder {
  type = "local"
  model = "sentence-transformers/all-MiniLM-L6-v2"
  model-revision = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"   # pin a commit
  dimensions = 384
}
```

Any model on the Hugging Face Hub with an ONNX export works: Keyvector looks for
`onnx/model.onnx`, then `model.onnx` (override with `embedder.onnx-file`).
`sentence-transformers/all-MiniLM-L6-v2`, `BAAI/bge-small-en-v1.5` and `intfloat/e5-small-v2`
all have one; others can be exported with `optimum-cli export onnx`.

How the model pools and normalizes, and how many tokens it reads, comes from its own
configuration files, never from guesses. Keyvector's vectors are tested against Hugging Face's
TEI for the same model: they match to a cosine similarity of 1.000000 (see the dev note
[Embeddings without a model server](/notes/embeddings-without-a-model-server/)).

**In production, don't download at startup.** Bake the model into the image, so a task needs no
access to Hugging Face:

```bash
docker build --build-arg MODEL=sentence-transformers/all-MiniLM-L6-v2 \
  --build-arg MODEL_REVISION=1110a243fdf4706b3f48f1d95db1a4f5529b4d41 -t keyvector .
```

Or point `embedder.model-path` at a directory holding the model's files. Use `HF_TOKEN` for gated
models.

**When to use something else:**
- **Large models** (bge-large, e5-large, 1,024 dimensions) are several times slower on CPU.
  Running them on TEI with a GPU is the better fit for a busy table.
- **Scaling the model separately** from the stream worker is easier with a separate TEI service.

## Titan V2's quota

Titan Text Embeddings V2 takes one text per request, so its request quota is its row rate.
**On a new account in November 2026 ours was 60 requests a minute**, one row a second, about
3,600 rows an hour, and the Service Quotas page marked it as not adjustable. Accounts with
more history get more: AWS's own DynamoDB walkthrough embedded 1,000 papers in 2 to 4
minutes. Check yours under Service Quotas, "On-demand model inference requests per minute
for Amazon Titan Text Embeddings V2".

Keyvector paces itself to 60 a minute by default, so it doesn't spend its time being
throttled. If your quota is higher, set `embedder.requests-per-minute` to match.

For a large backfill or a busy table:
- use `local` or TEI;
- or use Cohere Embed on Bedrock (96 texts per request);
- or embed the backfill with Bedrock batch inference and leave only live changes to Keyvector.

## Prefixes

Some models expect the document and the query to be marked: E5 wants `passage: ` and
`query: `. Set `embedder.document-prefix` and `embedder.query-prefix`.

## Changing models

Vectors from two models can't be compared. To change models:
1. create a new index;
2. run a second deployment that writes to it;
3. backfill;
4. switch your queries over.
