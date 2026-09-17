# Plan file schema

`plan/plan.yaml` is the master tracker; `plan/steps/<id>.yaml` holds one
step each. `plan/tools/PlanCheck.java` (run through `scripts/plan-check.sh`)
validates both. Agents edit only the tracking fields listed below; every
other change is a plan revision and must be reviewed.

## plan.yaml

| Key | Meaning |
| --- | --- |
| `schema_version` | Integer; bump when this document changes incompatibly. |
| `plan_version`, `plan_date`, `baseline_commit` | Identity of the plan revision and the commit it was written against. |
| `governing_documents` | Files that override the plan on conflict. |
| `ethos` | The product's non-negotiables restated for agents. |
| `conventions` | Status values, transition rules, estimate scale, TDD protocol, branch and commit rules, environment notes. |
| `release_train` | Ordered releases, the phases they contain, and the gate for each. |
| `decisions_made_by_this_plan` | Every decision the plan makes (`ADR-*`), the step that writes the ADR, and the rationale. |
| `phases[]` | `id`, `title`, `goal`, `exit_gate`, `steps[]`. |
| `phases[].steps[]` | One-line flow map: `id`, `title`, `file`, `status`, `depends_on`, `estimate`, `owner`; tracking fields `started`, `completed`, `evidence`, `notes` may be added by agents. |
| `owner_actions_required` | Items only the owner can do, each linked to a step. |
| `review_findings_index` | Every defect or gap found in review (`F-*`) with `severity`, `text`, and the step that closes it. |

Step ids are `<phase>-<nn>`; the phase prefix must match the enclosing phase.

## steps/<id>.yaml

Required keys: `id`, `title`, `phase`, `status`, `depends_on`, `estimate`,
`owner`, `summary`, `requirements`, `tdd`, `verification`,
`acceptance_criteria`, `docs_to_update`, `risks`, `rollback`.

Optional keys: `closes_findings`, `owner_action`, `rationale`, `ethos_check`,
`constraints`, `design_decisions`, `files_touched`, `evidence`, `notes`.

- `requirements[]`: `{id: <step>-R<n>, text}`. Both keys are required;
  requirement wording is interpreted by the owner and is not rewritten by the
  checker.
- `tdd`: `red` (tests to write first, each `{name, asserts?}` or a string
  shorthand for a prose/manual check), `green` (minimal implementation
  notes), and `refactor`; all three keys are required.
- `verification`: `commands` (exact commands to run) and `evidence_required`
  (what to paste into plan.yaml `evidence`); both keys are required lists of
  strings.
- Review findings require string keys `id` (`F-<n>`), `severity`, `text`, and
  `step`; a comma-separated `step` value names multiple closing steps.
- `depends_on` must equal the list in plan.yaml for the same id.
- The lifecycle `status` in `plan/plan.yaml` is authoritative. The companion
  step-file `status` is a required, schema-checked field retained in the brief
  as its initial/default value; it is intentionally not compared with the
  tracker so agents only mutate the approved tracking fields in `plan.yaml`.

## Status lifecycle

`todo` → `in_progress` → `review` → `done`; `blocked` and `waived` as
described in plan.yaml `conventions.status_rules`. A step is `done` only
when a reviewer other than the implementer confirms the acceptance criteria
against the recorded evidence.

The checker requires non-empty tracker `evidence` before a master-plan step
may be marked `done`; `review` is the handoff state. Each
`owner_actions_required[]` entry requires `id`, `text`, and `step`, and every
step named there must exist in the plan.

## Tracking fields an agent may write in plan.yaml

```yaml
- {id: P0-01, ..., status: review, started: 2026-09-17, evidence: "commit abc123; scripts/tests/run.sh 4/4; plan-check 0 errors", notes: "waiting on reviewer"}
```

Keep the entry on one line so the checker's flow-map parsing stays valid.

`scripts/plan-check.sh` accepts an optional plan directory for fixture tests
and `--json` for machine-readable CI summaries. `scripts/plan-status.sh`
accepts the same optional plan directory and `--json`; both options may be
combined. Neither script downloads dependencies or accesses the network.
