# End-to-end test

Runs Keyvector against real AWS: a Keyspaces table with CDC, S3 Vectors, Bedrock Titan V2
and a local Hugging Face TEI. It costs well under a dollar and takes about an hour, most of
it KCL's one-time 12-minute wait per connector. Everything it creates is deleted at the end.

## Setup

```bash
python3 -m venv .venv && .venv/bin/pip install -r e2e/requirements.txt   # boto3[crt] is needed for `aws login` credentials
mvn -q -DskipTests package
python3 e2e/make-configs.py <account-id>        # writes e2e/titan.conf and e2e/minilm.conf
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

## Clean up

Delete the `keyvector-e2e` stack, then the six tables KCL created:
`keyvector-e2e-{minilm,titan}-lease-table`, `...-CoordinatorState` and `...-WorkerMetricStats`.
