---
title: Commands
description: Every keyvector command and option.
---

```text
keyvector <command> [--config keyvector.conf] [options]
```

The config file defaults to `$APPLICATION_CONF_LOCATION`, then `./keyvector.conf`.

| Command | What it does |
|---|---|
| `run` | Follow the table's change stream and keep the index in sync. The ECS service runs this. |
| `backfill [--page-size N] [--batch-size N] [--limit N] [--skip-existing]` | Index the rows the table already had. |
| `resync KEY [KEY...]` | Re-read these rows and fix their vectors, or delete them if the row is gone. |
| `redrive [--queue-url URL]` | Resync every row parked in the dead-letter queue. |
| `query TEXT [--top-k N] [--filter JSON] [--no-rows]` | Semantic search; one JSON line per hit. |
| `check` | Validate the configuration against AWS without writing anything. |
| `fetch-model REPO [--revision R] [--onnx-file F]` | Download a model for `embedder.type = "local"` into the cache. Needs no config; the Dockerfile uses it to bake a model into the image. |

`run` serves `GET /health` on port 8081 (`HEALTH_PORT`) for container health checks.
