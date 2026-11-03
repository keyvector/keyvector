---
title: Deploy to your AWS account
description: One CloudFormation stack per table, running on ECS Fargate with least-privilege IAM.
---

[`deploy/keyvector.yaml`](https://github.com/keyvector/keyvector/blob/main/deploy/keyvector.yaml)
creates:
- the vector bucket and index (optional);
- a dead-letter queue;
- the IAM roles;
- an ECS Fargate service running Keyvector. With `Embedder=local` (the default) the model runs
  inside Keyvector; with `Embedder=tei` it runs in a second container in the same task.

:::caution[Preview]
The template passes `cfn-lint` but hasn't yet been deployed end to end; the connector itself
has been tested against live AWS from a workstation. See [Status and testing](/project/status/).
:::

## Before you start

1. **Turn on the table's change stream** with new and old images (see the
   [Quick start](/start/quick-start/)).
2. **Push the image** to a registry the task can pull from:

   ```bash
   aws ecr create-repository --repository-name keyvector
   docker build --platform linux/amd64 -t keyvector .
   docker tag keyvector <account>.dkr.ecr.<region>.amazonaws.com/keyvector:0.1.0
   aws ecr get-login-password | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
   docker push <account>.dkr.ecr.<region>.amazonaws.com/keyvector:0.1.0
   ```

3. **Pick subnets** that can reach AWS APIs, through a NAT gateway or VPC endpoints for:
   - Keyspaces and Keyspaces Streams
   - DynamoDB, S3 Vectors and SQS
   - CloudWatch Logs and ECR
   - Bedrock, if you use it

   With `local` or TEI, the model is downloaded from huggingface.co at start unless the image
   already contains it. To need no internet access, bake it in:
   `docker build --build-arg MODEL=<repo> --build-arg MODEL_REVISION=<commit> …`.

## Create the stack

```bash
aws cloudformation deploy \
  --stack-name keyvector-movies \
  --template-file deploy/keyvector.yaml \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    KeyspaceName=media TableName=movies \
    TextColumns=title,overview MetadataColumns=genre,release_date \
    Embedder=local EmbedderModel=sentence-transformers/all-MiniLM-L6-v2 \
    ModelRevision=1110a243fdf4706b3f48f1d95db1a4f5529b4d41 Dimensions=384 \
    VectorBucketName=my-vectors VectorIndexName=movies \
    Image=<account>.dkr.ecr.<region>.amazonaws.com/keyvector:0.1.0 \
    VpcId=vpc-... SubnetIds=subnet-...,subnet-...
```

The vector bucket and index are kept if the stack is deleted. Pass `CreateVectorBucket=false`
or `CreateVectorIndex=false` to use existing ones.

## One-off tasks

Backfill, redrive and check run as one-off tasks from the same task definition:

```bash
aws ecs run-task --cluster keyvector-movies --launch-type FARGATE \
  --task-definition <TaskDefinition output> \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-...],securityGroups=[<SecurityGroup output>]}' \
  --overrides '{"containerOverrides":[{"name":"keyvector","command":["backfill","--skip-existing"]}]}'
```

## Sizing

- **First start:** KCL creates its lease tables and an index on them, and assigns no shard
  until that index is active. That takes about 12 minutes the first time and is immediate after.
- **Workers:** KCL spreads the stream's shards over the tasks. Running more tasks than shards
  adds nothing.
- **TEI on CPU:** all-MiniLM-L6-v2 embeds hundreds of short texts per second per vCPU. For high
  write rates, run TEI on a GPU and point `EmbedderUrl` at it.
