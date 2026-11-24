# OpenLife production plan

- `plan.yaml` — master tracker: phases, steps, status, dependencies, decisions, findings, owner actions.
- `steps/<id>.yaml` — complete implementation brief per step (TDD red/green/refactor, requirements, verification, acceptance).
- `SCHEMA.md` — file format and status rules.
- `tools/PlanCheck.java` — validator (syntax, ids, files, dependencies, cycles, finding references). Run `scripts/plan-check.sh`.

Start here: `scripts/plan-status.sh` prints the next runnable steps. Read
`AGENTS.md` (GPT-5.6 Luna) or `CLAUDE.md` (Claude sessions) for the
workflow, then open the step file.

For CI summaries, append `--json`; fixture plans can be supplied as the
optional final argument, for example `scripts/plan-check.sh --json /tmp/plan`.

## Delegation of ownership (2026-11-24)

On 2026-11-24 the owner delegated repository ownership to the coordinating
Claude session, with the instruction to analyse the implementation and redo
what is wrong or suboptimal (plan 1.3.0 and the audits in
`docs/reviews/2026-11-24-*.md` are the first result). `plan.yaml` has no
schema key for governance, so the record lives here and is referenced from a
comment above `owner_actions_required`:

- `review` -> `done` decisions are made by a reviewing agent that did not
  implement the step (unchanged rule, now exercised without the owner).
- Capability specifications (OA-4) are approved by the coordinator after an
  adversarial review.
- Merges to `main` follow the standing merge-when-CI-is-green instruction.
- Public release tags, GitHub Releases and the F-Droid request still need
  the human owner.
