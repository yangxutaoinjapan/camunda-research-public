# Camunda Sharing Library

A hand-maintained collection of Camunda notes and experiments. Each entry starts
with a practical question and keeps **observed behavior** separate from
**proposed procedures** and **unverified assumptions**. The repository is an
independent reading resource; its contents describe bounded observations rather
than the current state of a running environment.

## Notes

| Note | Question |
| --- | --- |
| [Keycloak issuer is a realm attribute](notes/keycloak-issuer-is-a-realm-attribute.md) | Why can a new token endpoint still issue tokens naming the old issuer, and how can the issuer move? |
| [Keycloak outage drill](notes/keycloak-outage-drill.md) | What failed during a deliberate 150-second outage, and how should recovery and replay protection work? |
| [A rejected token can terminate a pyzeebe worker](notes/pyzeebe-unauthenticated-kills-the-worker.md) | How can pyzeebe 4.5.0 turn an authentication rejection into a worker exit, and what does a cache-refresh workaround require? |
| [Human approval before a runbook](notes/human-approval-gating-a-runbook.md) | How can preview, approval, execution, verification, and reporting remain distinct when jobs are retried? |
| [Parent and child workflows with message starts](notes/parent-child-workflows-with-message-starts.md) | How do stable request identities, a ledger, and guarded children support controlled ingress? |
| [Moving Camunda off an old entry point](notes/moving-camunda-off-a-remote-host.md) | How can application traffic move while identity paths remain available? |

The notes were drafted by AI assistants (Claude and Codex) from the author's own
operating records and then reviewed by the author.

Each note contains **Problem**, **What we observed**, **What works**, and
**What we did not verify**. Procedures are suggestions unless the observation
section records their execution. Examples use placeholders and omit credentials.
Version-specific findings need fresh checks before reuse.

## Experiments

Experiments are one category within this library. They include runnable code,
a stated thesis, and findings that distinguish implementation from verification.

| Experiment | Thesis | Evidence |
| --- | --- | --- |
| [01 — Role-bounded lifecycle](experiments/01-role-bounded-lifecycle/) | A message-started role owns a window, stops accepting new work at closure, and lets existing children drain. | Start with its README and follow the findings for measured behavior and remaining questions. |

Experiments remain self-contained. Build and run them from their own directories
and read their configuration requirements before connecting a client to an
engine. A successful build establishes that code builds; a recorded engine run
establishes only the behavior exercised by that run.

## Working paper

The [English working paper](paper/working-paper.md) and
[Japanese working paper](paper/working-paper.ja.md) discuss operational checks
and the limits of their evidence. It is a working paper, not peer reviewed, and
**its full text was written by AI assistants (Claude and Codex)**, which the paper
also states in its own text. It is not specific to Camunda and is kept here as
supplementary reading alongside the notes and experiments.

## Reading and contributing

Keep entries focused on one question. Record versions and the boundary of an
observation, explain what a check actually proves, and list important unknowns.
Avoid treating a healthy status, successful request, or single run as broader
availability evidence. Keep connection configuration outside recorded output,
and use public-safe examples without credentials.

Repository code and documentation are licensed under [Apache-2.0](LICENSE).
