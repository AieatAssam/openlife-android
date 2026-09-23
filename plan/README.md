# OpenLife production plan

- `plan.yaml` — master tracker: phases, steps, status, dependencies, decisions, findings, owner actions.
- `steps/<id>.yaml` — complete implementation brief per step (TDD red/green/refactor, requirements, verification, acceptance).
- `SCHEMA.md` — file format and status rules.
- `tools/PlanCheck.java` — validator (syntax, ids, files, dependencies, cycles, finding references). Run `scripts/plan-check.sh`.

Start here: `scripts/plan-status.sh` prints the next runnable steps. Read
`AGENTS.md` (GPT-5.6 Luna) or `CLAUDE.md` (Claude Sonnet 5) for the
workflow, then open the step file.

For CI summaries, append `--json`; fixture plans can be supplied as the
optional final argument, for example `scripts/plan-check.sh --json /tmp/plan`.
