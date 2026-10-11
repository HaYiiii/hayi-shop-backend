# Domain docs

The domain model, glossary and decisions live **outside this repo**, in the canonical documentation folder (not under git):

`/Users/hayi/Documents/projects/HaYi-Shop-Canonical-VI`

| What | Where |
|---|---|
| Bounded contexts, business rules, invariants (= glossary) | `docs/domain/*/CONTEXT.md`, `docs/foundation/*/CONTEXT.md` |
| Decisions (= ADRs) | `DECISIONS.md` (Decision A, B, …) |
| Aggregates & transaction boundaries | `docs/architecture/AGGREGATE-MAP.md` |
| Database schema | `docs/architecture/erd/` |
| API contract | `docs/architecture/api/` |
| Code layout | `docs/architecture/SOURCE-STRUCTURE.md` |
| Plan, phases, Definition of Done | `docs/architecture/IMPLEMENTATION-PLAN.md` |

Rules for skills that read or write domain docs (`domain-modeling`, `grill-with-docs`, `tdd`, `code-review`):

- **Do not create `GLOSSARY.md` or `docs/adr/` in this repo.** Read vocabulary from the CONTEXT files; propose new terms or decisions to the user, and record agreed decisions in the canonical `DECISIONS.md` (next free letter) plus the affected CONTEXT section — edit the per-context `CONTEXT.md` first, then re-sync the master file.
- Canonical docs win over code. If code and docs disagree, stop and ask; never silently change a business rule in code.
- Technical names (entities, enums, commands, events, fields) are English; explanations are Vietnamese.
