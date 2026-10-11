# Issue tracker

Work is tracked in **GitHub Issues** of this repo and on the shared **GitHub Project "HaYi Shop"** (owner `HaYiiii`), which spans `hayi-shop-backend` and `hayi-shop-frontend` (Decision J1).

- CLI: `gh issue …`, `gh project item-list …` (`gh` is logged in as `HaYiiii`).
- Board columns (Status field): `Backlog` → `Ready` → `In Progress` → `In Review` → `Done`.
- **WIP limit: at most 3 items In Progress across the whole project** (Decision J2). Before moving a ticket to In Progress, count current In Progress items; if there are 3, finish/hand back one first.
- Milestone = phase (`Phase 0`, `Phase 1 – MVP`, …). Labels: `ready-for-agent`, `ready-for-human`, `needs-info`, `type:feature|chore|bug|design|docs`, `module:<name>`.
- Blocking: use GitHub's native "blocked by" relationship where available; otherwise a `Blocked by: #n` line at the top of the body. Cross-repo references use `HaYiiii/<repo>#n`.
- A ticket body must cite the canonical sections it implements (e.g. `Order §7.11`, `API 04-order §3.2`) and list the **seams under test** (see `tdd`).
- PRs reference their ticket (`Closes #n`).
