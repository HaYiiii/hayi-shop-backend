Closes #

## What changed
<!-- From the user's perspective: what works now that did not before. -->

## Canonical refs
<!-- Sections of HaYi-Shop-Canonical-VI this PR implements, e.g. `Identity §2.6`, `API 01-security §5`. -->

## Acceptance criteria → evidence
| Criterion | Evidence (test name / command output) |
|---|---|
|  |  |

## Definition of Done (IMPLEMENTATION-PLAN §1.1)
- [ ] API Contract Markdown matches; `api-spec/*.yaml` updated; controllers implement generated interfaces (or N/A)
- [ ] Flyway migration for new tables (DDL from ERD) (or N/A)
- [ ] Domain invariants unit-tested; use cases tested with MySQL Testcontainers
- [ ] `ModularityTest` + `ArchitectureTest` green
- [ ] Permission + branch scope enforced server-side; Audit for sensitive actions (or N/A)
- [ ] No secrets; no generated code committed

## Deviations / follow-ups
<!-- Anything not done, decisions taken, candidate tickets. -->
