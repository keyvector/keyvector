# Deploying Keyvector

One CloudFormation stack per table. The stack runs in your account and your VPC.

## Before you start

1. **Turn on CDC for the table**, with new and old images:
   ```sql
   ALTER TABLE media.movies WITH cdc = true
     AND CUSTOM_PROPERTIES = {'cdc_specification': {'view_type': 'NEW_AND_OLD_IMAGES'}};
   ```
2. **Push the image** to a registry the task can pull from:
   ```bash
   aws ecr create-repository --repository-name keyvector
   docker build --platform linux/amd64 -t keyvector .
   docker tag keyvector <account>.dkr.ecr.<region>.amazonaws.com/keyvector:0.1.0
   aws ecr get-login-password | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
   docker push <account>.dkr.ecr.<region>.amazonaws.com/keyvector:0.1.0
   ```
3. **Pick subnets** that can reach AWS APIs (a NAT gateway or VPC endpoints for Keyspaces,
   Keyspaces Streams, DynamoDB, S3 Vectors, SQS, CloudWatch Logs, ECR and Bedrock if used).
   With `Embedder=tei` the model is downloaded from huggingface.co when the task starts, so
   the subnets also need internet access. To avoid that, bake the model into your own TEI image.

## Create the stack

```bash
aws cloudformation deploy \
  --stack-name keyvector-movies \
  --template-file deploy/keyvector.yaml \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    KeyspaceName=media TableName=movies \
    TextColumns=title,overview MetadataColumns=genre,release_date \
    Embedder=tei EmbedderModel=sentence-transformers/all-MiniLM-L6-v2 Dimensions=384 \
    VectorBucketName=my-vectors VectorIndexName=movies \
    Image=<account>.dkr.ecr.<region>.amazonaws.com/keyvector:0.1.0 \
    VpcId=vpc-... SubnetIds=subnet-...,subnet-...
```

The vector bucket and index are kept if the stack is deleted (`DeletionPolicy: Retain`).
Pass `CreateVectorBucket=false` and/or `CreateVectorIndex=false` to use existing ones.

## Backfill, redrive and query

They run as one-off tasks from the same task definition:

```bash
aws ecs run-task --cluster keyvector-movies --launch-type FARGATE \
  --task-definition <TaskDefinition output> \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-...],securityGroups=[<SecurityGroup output>]}' \
  --overrides '{"containerOverrides":[{"name":"keyvector","command":["backfill","--skip-existing"]}]}'
```

Use `["redrive"]` to drain the dead-letter queue and `["check"]` to validate the setup.

## What the IAM role can do

- `cassandra:GetStream`, `GetShardIterator`, `GetRecords` on this table's stream, and
  `ListStreams` to find it
- `cassandra:Select` on this table and the `system*` keyspaces (the driver reads the schema)
- DynamoDB on `<stack>-lease-table*` only (KCL's lease and coordination tables)
- `s3vectors:PutVectors`, `DeleteVectors`, `GetVectors`, `QueryVectors`, `GetIndex` on this index
- SQS on this stack's dead-letter queue; `cloudwatch:PutMetricData`
- `bedrock:InvokeModel` on the chosen model, only when `Embedder=bedrock`

## Sizing

- **First start**: KCL creates its lease tables and an index on them, and assigns no shard
  until the index is active: about 12 minutes the first time, immediate after that.
- **Bedrock Titan V2** is limited to 60 requests (one row each) a minute per account, and
  the quota is not adjustable; Keyvector paces itself to it. Use TEI or Cohere Embed when
  rows change faster than that, or for a large backfill.

- **Workers**: KCL spreads the stream's shards over the tasks. More tasks than shards add nothing.
- **TEI on CPU**: all-MiniLM-L6-v2 embeds hundreds of short texts per second per vCPU; a
  larger model (bge-large, 1024 dimensions) is several times slower. For sustained high
  write rates, run TEI on a GPU (ECS on EC2 with a GPU instance) and point `EmbedderUrl` at it.
