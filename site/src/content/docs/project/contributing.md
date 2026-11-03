---
title: Contributing
description: How to build, test and contribute.
---

- **Build and test:** `mvn test` (Java 17+). The unit tests need no AWS account.
- **End to end:** [`e2e/README.md`](https://github.com/keyvector/keyvector/blob/main/e2e/README.md)
  runs the whole path against real AWS for well under a dollar.
- **Where rules live:** new rules belong in the pure classes (`Planner`, `RowKey`, `Values`,
  `TextTemplate`), each with a test that fails without it. The AWS-facing classes stay thin.
- **Every write to a sink must be idempotent.** The stream delivers at least once.
- **Security issues:** report them privately through
  [GitHub's vulnerability reporting](https://github.com/keyvector/keyvector/security/advisories/new),
  not in a public issue.

Contributions are licensed under the Apache License 2.0.
