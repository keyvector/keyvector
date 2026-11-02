# Security

Please report vulnerabilities privately through GitHub's "Report a vulnerability" (Security
tab), not in a public issue. We aim to acknowledge reports within three working days.

Keyvector runs with the IAM role you give it; `deploy/keyvector.yaml` scopes that role to one
table, its stream, one vector index and one queue. API keys for hosted embedding models are
read from an environment variable populated from Secrets Manager, and are never logged.
