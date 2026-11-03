---
title: Choose an embedding model
description: TEI with your own Sentence-Transformers model, Amazon Bedrock, or an OpenAI-compatible API, and what each costs in throughput.
---

Set `embedder.type` in the configuration. Whichever you choose, `embedder.dimensions` must equal
the model's output and the vector index's dimension; Keyvector checks this at startup.

| | `tei` | `bedrock` | `openai` |
|---|---|---|---|
| What | Hugging Face text-embeddings-inference running any Sentence-Transformers model | Titan Text Embeddings V2, Cohere Embed | OpenAI or any server speaking its `/embeddings` API (vLLM, Infinity, Ollama) |
| Where your text goes | Nowhere: it runs beside Keyvector | Bedrock, in your account | That API |
| Per-token cost | None, you pay for the compute | Yes | Yes |
| Throughput limit | Your hardware | **Titan V2: 60 requests a minute, one text each, not adjustable.** Cohere takes 96 texts per request | The provider's |

## Titan V2's quota

Measured on a new account in November 2026: on-demand Titan Text Embeddings V2 allows **60
requests a minute**, and AWS marks the quota as not adjustable. Titan takes one text per
request, so that's one row per second, about 3,600 rows an hour. Keyvector paces itself to it
by default, so it doesn't spend its time being throttled. To go faster, set
`embedder.requests-per-minute` only if your account's quota is higher.

For a large backfill or a busy table:
- use TEI;
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
