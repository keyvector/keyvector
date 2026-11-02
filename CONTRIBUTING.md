# Contributing

Issues and pull requests are welcome.

- Build and test: `mvn test` (Java 17+). Unit tests need no AWS account.
- New rules belong in the pure classes (`Planner`, `RowKey`, `Values`, `TextTemplate`) with a
  test that fails without them; the AWS-facing classes should stay thin.
- Every write to a sink must be idempotent. The stream delivers at least once.
- By contributing you agree your contribution is licensed under the Apache License 2.0.
