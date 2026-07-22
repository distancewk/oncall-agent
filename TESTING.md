# Testing and quality workflow

This project uses a layered test process. Fast checks run on every pull request, while
database and packaging checks provide confidence that the application can be built and
deployed as a complete unit.

## Local verification

Run the following before opening a pull request:

```bash
# Frontend syntax and behavior tests
node --check src/main/resources/static/app.js
node --test src/test/js/evidenceRendering.test.mjs src/test/js/incidentFrontendActions.test.mjs

# Java unit, controller, service, configuration, static analysis, and packaging checks
mvn -B -ntp test
mvn -B -ntp verify

# PostgreSQL integration tests (requires a running Docker daemon)
mvn -B -ntp -Ppostgres-it verify

# Compose schema and required-variable validation
export DASHSCOPE_API_KEY=ci-placeholder
export MINIO_ROOT_USER=ci-minio
export MINIO_ROOT_PASSWORD=ci-minio-password
export POSTGRES_PASSWORD=ci-postgres
export APP_API_TOKEN=ci-api-token
export APP_WEBHOOK_SECRET=ci-webhook-secret
export APP_INCIDENT_JDBC_PASSWORD=ci-postgres
docker compose config --quiet

# Application container build
docker build --tag super-biz-agent:local .
```

When Docker is unavailable, the regular Java tests and quality checks still run, but
`PostgresqlDurableWorkflowIT` is skipped by Testcontainers. GitHub Actions checks Docker
first and verifies the Failsafe report, so the protected-branch CI path fails clearly
instead of silently omitting that integration coverage.

## Pull request gates

Every pull request targeting `main` runs `.github/workflows/ci.yml` with these jobs:

1. **Frontend and Compose checks** — JavaScript syntax, frontend behavior tests, and
   Docker Compose configuration validation.
2. **Maven verification and PostgreSQL integration tests** — Java tests, Flyway-backed
   persistence checks, Testcontainers PostgreSQL integration tests, SpotBugs, PMD,
   Checkstyle reporting, and Maven packaging.
3. **Container image build** — verifies that the production Dockerfile can build from a
   clean checkout. This is currently an additional CI check; promote it to a required
   branch-protection check after this workflow change has been merged into `main`, so
   existing branches do not become blocked by a check they cannot emit yet.

The `main` branch currently requires the first two checks above, a pull request with at
least one approval, resolved review conversations, and an up-to-date branch before merge.

## Test layers

| Layer | Scope | Typical trigger |
| --- | --- | --- |
| Frontend unit/behavior | Rendering, escaping, timeline and action behavior | Every pull request |
| Java unit/component | Services, controllers, configuration, repositories and failure paths | Every pull request |
| Static analysis | SpotBugs and PMD blocking; Checkstyle reporting | Every pull request |
| Persistence integration | PostgreSQL SQL, migrations, transactions, leases and durable jobs | Every pull request with Docker |
| Compose validation | Required variables and service graph syntax | Every pull request |
| Container packaging | Multi-stage image build and runtime artifact assembly | Every pull request |

## Failure handling

- A failed required check blocks merging; fix the cause and rerun the workflow.
- A flaky test must be fixed or quarantined with an issue and an owner; do not add retries
  that hide deterministic failures.
- Test data must be isolated and disposable. Integration tests must not use production
  credentials or external business services.
- New behavior requires a regression test at the lowest practical layer, plus an
  integration test when it changes database, queue, transaction, or external-boundary
  behavior.
- Release candidates should rerun the full workflow from the exact release commit and
  perform a manual smoke test against a non-production environment.
