# Project Agent Instructions

## Purpose

SuperBizAgent is a Spring Boot 3.2 / Java 17 OnCall assistant using DashScope,
Milvus, Redis, and PostgreSQL for RAG chat, incidents, durable diagnosis jobs,
evidence, and historical-case retrieval.

## Run and verify

- Start local dependencies with `docker compose up -d etcd minio milvus redis postgres attu`.
- Run the application with `mvn spring-boot:run` on port 9900.
- Use `TESTING.md` for the layered frontend, Maven, integration, Compose, and image checks.
- Keep secrets in environment variables or external files; do not commit credentials.

## Project layout

- `src/main/java/org/example/`: application, controllers, services, tools, and configuration.
- `src/main/resources/`: Spring profiles, Flyway migrations, prompts, and the static UI.
- `aiops-docs/`: example operational knowledge-base documents.
- `docker-compose.yml`, `Dockerfile`, `pom.xml`: local infrastructure and build contracts.
- `README.md`: user-facing setup, API, configuration, and operating documentation.

## Change and cleanup rules

- Inspect `git status` first and preserve existing user changes.
- After any agent-created code change, review the actual diff before reporting completion;
  fix or explicitly document Critical/Important findings.
- Do not delete branches, worktrees, runtime data, logs, uploads, volumes, or session
  artifacts without explicit user confirmation after reporting the candidates.
- Keep ignored local runtime/session files out of commits.

## Current handoff

As of 2026-07-22, the active lane is `codex/readme-update` and contains pre-existing
uncommitted application, frontend, test, CI, README, and local testing-workflow changes.
Treat those changes as user-owned until reviewed and intentionally committed. This
closeout adds this rule file and synchronizes the missing configuration entries in README.
