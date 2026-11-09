# End-to-end test

Runs Keyvector against real AWS: a Keyspaces table with CDC, S3 Vectors, Bedrock Titan V2
and a local Hugging Face TEI. It costs well under a dollar and takes about an hour, most of
it KCL's one-time 12-minute wait per connector. Everything it creates is deleted at the end.

## Setup

```bash
python3 -m venv .venv && .venv/bin/pip install -r e2e/requirements.txt   # boto3[crt] is needed for `aws login` credentials
mvn -q -DskipTests package
python3 e2e/make-configs.py <account-id>        # writes e2e/titan.conf, minilm.conf, local.conf and chunked.conf
```

Create the stack with CDC **off** (`e2e/stack.yaml`, stack name `keyvector-e2e`), so rows can
be loaded before the stream exists:

```bash
aws cloudformation deploy --stack-name keyvector-e2e --template-file e2e/stack.yaml --parameter-overrides Cdc=DISABLED
```

## Steps

| # | Do | Pass |
|---|---|---|
| 1 | `python e2e/e2e.py seed`, then redeploy with `Cdc=ENABLED` | 302 rows, stream `NEW_AND_OLD_IMAGES` |
| 2 | `keyvector check --config e2e/<conf>` | 5 of 5 |
| 3 | `keyvector run` in the background, then `keyvector backfill` | `e2e.py verify <index>`: 302 vectors, nothing missing |
| 4 | `e2e.py snapshot <index> before.json`, `e2e.py mutate` | applied 61: 31 embedded, 10 reused, 10 deleted, 10 unchanged; `verify <index> --snapshot before.json` ok |
| 5 | stop `run`, `e2e.py mutate-offline`, start `run` | exactly 5 applied after restart; verify ok |
| 6 | `keyvector query "astronauts stranded in space"` (and the other two anchors) | the anchor movie first |
| 7 | (TEI) stop the TEI container, `e2e.py mutate-retry`, start it again | all 5 rows arrive; verify ok |

Indexes: `movies-minilm` (TEI, `all-MiniLM-L6-v2`, 384 dimensions) and `movies-titan` (Bedrock, 256).
`local.conf` runs the same steps with the in-process embedder, writing to `movies-minilm` (same
model, identical vectors); skip step 7, since there is no model server to stop.

`chunked.conf` tests chunking and reranking: 10-word chunks, so every row spans several, and a
cross-encoder for queries. Run steps 1–6 with it, but check the index with
`e2e.py verify-chunks movies-minilm 10 2` instead of `verify`. That checks every row holds exactly
chunks `#0` to `#n-1`, that `n` is stored on each chunk and equals what an independent port of the
chunker predicts, and that no chunk survives its row. To test stale-chunk removal, shorten a few
overviews after `mutate`.

## Clean up

Delete the `keyvector-e2e` stack, then the six tables KCL created:
`keyvector-e2e-{minilm,titan,local,chunked}-lease-table`, `...-CoordinatorState` and `...-WorkerMetricStats`.
