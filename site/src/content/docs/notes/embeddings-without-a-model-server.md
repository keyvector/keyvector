---
title: Embeddings without a model server
description: Keyvector can now run a Sentence-Transformers model itself, with ONNX Runtime and Hugging Face's tokenizers. Its vectors match the reference implementation exactly.
date: 2026-11-03
authors: yogesh
tags: [embeddings, release]
excerpt: No TEI container, no API key, no per-token cost. One setting, and Keyvector embeds in process at hundreds of texts a second, with vectors identical to Hugging Face's own server.
---

Until today, Keyvector needed something else to turn text into vectors: a Hugging Face TEI
container next to it, or an API such as Bedrock. Each of those is a second thing to run or a
second thing to pay for, and our live test showed the catch with the API route: a new account's
Titan quota allowed one embedding a second.

Keyvector can now run the model itself:

```hocon
embedder {
  type = "local"
  model = "sentence-transformers/all-MiniLM-L6-v2"
  model-revision = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
  dimensions = 384
}
```

That's the whole setup. It's now the default in the example configuration and the CloudFormation
template.

## How it works

We didn't reimplement transformers. Keyvector puts together three things that already exist:

- **ONNX Runtime** (Microsoft, MIT) runs the model. Popular Sentence-Transformers models publish
  an ONNX export on the Hugging Face Hub; any other can be exported with `optimum`.
- **Hugging Face's tokenizers** (the Rust library, through AWS's Deep Java Library) turn text
  into tokens exactly as the model was trained to see them.
- **Pooling and normalization**, the last step that turns the model's per-token outputs into one
  vector: a few dozen lines of arithmetic with their own unit tests.

Both libraries ship native code for Linux on x86 and ARM, macOS on Apple Silicon and Windows, so
it runs the same on Fargate and on a laptop. The model is downloaded once from the Hub and cached,
or baked into the image with `docker build --build-arg MODEL=…`. We built that image and ran it
with networking switched off entirely, and it embedded fine.

## The part that has to be right: matching the reference

An embedding pipeline that's subtly wrong doesn't fail. Tokenize slightly differently, pool by
averaging when the model expects its first token, or forget to normalize, and you still get
vectors of the right length. Search just quietly gets worse, and nobody can tell why.

So Keyvector reads every model-specific choice from the model's own files rather than assuming:
- the pooling mode from `1_Pooling/config.json` (MiniLM averages its tokens; bge uses the first);
- whether to normalize from `modules.json`;
- the token limit from `sentence_bert_config.json`.

Then we tested it against the reference. We generated vectors with Hugging Face's TEI for two
models with different pooling, at pinned revisions, over six texts chosen to break things:
- plain English sentences;
- accents and emoji (`Café crème brûlée… 😀`);
- Chinese;
- a one-character text;
- a text long enough to be truncated.

Keyvector's in-process vectors match every one of them at **cosine similarity 1.000000**, for
both all-MiniLM-L6-v2 and bge-small-en-v1.5. That comparison now runs in CI on every push.

## How fast

On a 10-core Apple Silicon machine, embedding 1,000 texts of about 60 words each:

| Model | Texts per second |
|---|---|
| all-MiniLM-L6-v2 (384 dimensions) | 546 |
| bge-small-en-v1.5 (384 dimensions) | 301 |

A Fargate task has fewer and slower cores, so expect less there; measure your own. Even a
fraction of these numbers is far beyond one embedding a second, and none of it is billed per
token.

## Against real AWS

On 3 November 2026 the same end-to-end run that tested TEI and Bedrock was repeated with
`embedder.type = "local"`, with no model server and no Bedrock: a Keyspaces table with CDC in
us-east-1 and an S3 Vectors index.

- `keyvector check` passed all five checks, including loading the pinned model.
- The backfill embedded 302 rows in 17 seconds, and every row had exactly one vector.
- 61 live changes came off the stream with the expected split: 31 embedded, 10 reused, 10 deleted
  and 10 unchanged.
- Stopping the connector, changing 5 rows and restarting it applied exactly those 5.
- All three test queries returned their target movie first. One of the three movies only ever
  reached the table through the stream.

The connector ran on a laptop, not on Fargate; that deployment is still to be tested live.

## What it costs

- **A bigger artifact.** The native libraries add about 65 MB to the jar, and a baked-in model
  adds its own size (90 MB for MiniLM).
- **Memory and CPU.** The model runs in Keyvector's process, so size the task for it. Embedding
  scales with the connector rather than independently of it.
- **Large models belong elsewhere.** 1,024-dimension models such as bge-large are several times
  slower on CPU. For those, or for a very busy table, TEI on a GPU is still the better tool, and
  Keyvector still supports it.

## Why it matters

The pitch for Keyvector was always "semantic search for Keyspaces without moving your data".
Now it's also "without a second system to run": one container reads the change stream, makes the
embeddings, and keeps the index right. DynamoDB's native vector search leaves embeddings to
your application, and AWS's sample connector needs Bedrock for them. Keyvector now makes them
itself, by default.

Try it with the [quick start](/start/quick-start/), or read
[Choose an embedding model](/guides/models/) for when to use something else.
