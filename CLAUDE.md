# CLAUDE.md — hayi-shop-backend

HaYi Shop backend: Spring Boot 4.1 modular monolith (Spring Modulith 2.1), Java 25, MySQL 8.0, Flyway, Gradle Kotlin DSL.
The user communicates in Vietnamese; reply in Vietnamese. Code, identifiers and commit messages are English.

## Source of truth

Canonical docs live outside this repo (no git): `/Users/hayi/Documents/projects/HaYi-Shop-Canonical-VI`.
See [docs/agents/domain.md](docs/agents/domain.md) for what lives where. **Docs win over code.**
If a ticket, the code and the docs disagree, stop and ask — never change a business rule silently.
Any agreed business change: update the canonical `CONTEXT.md` + `DECISIONS.md` first, then code.

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # JDK is keg-only via Homebrew
./gradlew build                                  # compile + all tests (Docker required: Testcontainers MySQL 8.0)
./gradlew test --tests '*ModularityTest'         # module boundaries
./gradlew bootRun                                # starts compose.yaml (MySQL 8.0 + SeaweedFS) automatically
```

## Architecture rules (non-negotiable)

- One top-level package per bounded context under `vn.hayi.shop`; layers `api / web / application / domain / infrastructure` (SOURCE-STRUCTURE.md §2.2).
- Other modules may only use `<module>.api` (+ `shared`, `platform`). `ModularityTest` and `ArchitectureTest` must stay green.
- `domain` depends only on `shared` — no Spring Web, no `platform`, no other module.
- No JPA relations and no FKs across modules: keep foreign IDs (`UUID customerId`).
- Aggregates are JPA entities with `@Version`; IDs are UUIDv7 generated in the app, stored as `BINARY(16)`.
- Hard-limit counters (slot capacity, promotion usage), projections and outbox claims use `JdbcClient` with explicit conditional SQL.
- Reliable side effects go through the project **Outbox** (`evt_outbox_event` / `evt_outbox_delivery`) — never Modulith's publication registry. Handlers are idempotent.
- Errors: RFC 9457 Problem Details with stable `code` (API README §6). Money: `long` VND. Instants: UTC. Operational dates/times: `Asia/Ho_Chi_Minh`.
- Never trust price, discount, total, permission or status from the client. Permission + branch scope are checked server-side on every endpoint.
- Business audit (`audit.api`) is written in the same transaction as the audited mutation.

## API & database

- **Design-first OpenAPI**: update the Markdown contract (canonical `docs/architecture/api/`) → `api-spec/*.yaml` → generated interfaces → controllers `implements` them. Never hand-edit generated code; generated sources stay out of git.
- Flyway: one migration per module per slice, DDL copied from canonical `docs/architecture/erd/`, naming `V{yyyy_MM_dd_HHmm}__{module}_{desc}.sql`. Never edit a migration that has run in a shared environment.

## Testing

- Use the `tdd` skill: red → green, one vertical slice at a time, tests at agreed seams (seams are listed in the ticket).
- Real MySQL via Testcontainers for anything touching persistence — **no H2**.
- Prefer testing through module `api`/use-case seams; mock only other modules' `api` facades and external systems.
- Test names describe behaviour in domain language (e.g. `confirmRejectedWhenPrepaymentNotSatisfied`).

## Workflow

- Tracker: GitHub Issues + Project "HaYi Shop" — see [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md).
- **WIP ≤ 3 In Progress items across the project.** Check before starting a ticket.
- Definition of Done: canonical `IMPLEMENTATION-PLAN.md` §1.1.
- Branch `feature/<ticket>-<slug>` from `main`; Conventional Commits; PR body links the ticket and the canonical sections touched.
- This repo is **public**: never commit secrets. Real credentials come from environment variables; `compose.yaml` and `docker/` hold dev-only values.

## Agent skills (vendored in `.claude/skills`, pinned — see VENDORED.md)

| When | Skill |
|---|---|
| Sharpen a plan/design with the user | `grill-with-docs` (uses `grilling`, `domain-modeling`) |
| Break a plan into tickets | `to-tickets` |
| Implement a ticket | `tdd` |
| Hard bug / regression | `diagnosing-bugs` |
| Review a branch/PR | `code-review` |
| Architecture health check | `improve-codebase-architecture`, `codebase-design` |
| Module boundaries / events | `spring-modulith` (with HaYi overrides) |
| Error responses | `problem-details-rfc9457` (with HaYi overrides) |
| Retried commands | `idempotency-patterns` (with HaYi overrides) |
| Migrations | `flyway-migrations` (with HaYi overrides) |

Each Spring skill ends with a **"HaYi overrides"** section that takes precedence over its generic text.

### Library docs

Use the **Context7 MCP** (`.mcp.json`) to look up Spring Boot 4.1 / Spring Modulith 2.1 / Spring Security 7 / Hibernate 7 / Flyway / Testcontainers APIs at the exact versions in `gradle/libs.versions.toml` and the Boot BOM — do not rely on memory for APIs that changed in Boot 4.
