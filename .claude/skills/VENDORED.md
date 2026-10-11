# Vendored agent skills

Skills are copied from upstream at a **pinned commit**, reviewed before use, and updated only through a PR (Decision J3).
Project-specific deviations are appended as a **"HaYi overrides"** section in the skill, or listed below.
Reviewed 2026-10-11: no network fetches, no remote code execution, no instructions outside the skill's purpose.

| Skills | Source @ commit | License | Local changes |
|---|---|---|---|
| `grill-with-docs`, `grilling`, `domain-modeling`, `tdd`, `diagnosing-bugs`, `improve-codebase-architecture`, `codebase-design`, `code-review`, `to-tickets`, `setup-matt-pocock-skills` | [mattpocock/skills](https://github.com/mattpocock/skills) @ `49dd158d1076` | MIT (`_licenses/mattpocock-skills.LICENSE`) | Removed Codex-only `agents/openai.yaml`. Repo config in `docs/agents/` instead of running `setup-matt-pocock-skills`. |
| `spring-modulith`, `problem-details-rfc9457`, `idempotency-patterns`, `flyway-migrations` | [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills) @ `f0c06a01b0b7` (`skills/spring-boot-4/`) | MIT (`_licenses/spring-boot-skills.LICENSE`) | Appended "HaYi overrides": own Outbox instead of Modulith registry; MySQL instead of PostgreSQL; Problem Details field `code`; framework-free domain exceptions; Flyway naming & reference-data seeding. Removed `agents/openai.yaml`. |

Not vendored on purpose: `openapi-first` (Maven/delegate pattern ≠ our Gradle + interfaceOnly) and `testing-pyramid` (mock-heavy, response envelope) — the equivalent project rules are in `CLAUDE.md`.

## Updating

1. Diff the upstream folder between the pinned commit and the new one.
2. Review for scope creep, network/exec instructions and conflicts with canonical decisions.
3. Copy, re-apply "HaYi overrides", update the commit hash here, open a PR.
