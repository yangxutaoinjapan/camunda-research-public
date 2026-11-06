# Put human approval before an automated runbook

## Problem

A workflow can make an infrastructure change visible without making it safe.
A user task labeled approval is useful only if execution is bound to what the
owner actually reviewed. A worker that accepts mutable inputs after approval
can perform a different action while the diagram still looks correct.

A practical BPMN sequence is preview, approve, execute, verify, and report.
Each stage answers a different question: what will happen, who authorized it,
what ran, what state resulted, and whether the outcome reached its audience.
Keeping these questions separate is especially important when the change
restarts Camunda or its identity dependency.

This note extracts a general design and a recorded failure sequence. It does
not claim that a completed user task authenticates arbitrary privileged host
execution by itself.

## What we observed

The reviewed design used a preview service task before a human user task.
Preview prepared a diff against a known base revision and selected executable
actions from a runbook catalog. Execution checked that the reviewed inputs and
base had not changed. Verification ran a named read-only check repeatedly,
rather than treating successful process launch as successful deployment.

One identity configuration change demonstrated the difference between action
state and workflow state. Its first execution applied the intended change, but
a post-synchronization admission check encountered a component still rolling
and failed. Reporting the result then timed out while Camunda restarted.
Approximately one hour later, Camunda offered the execution job again after
its activation timeout.

The second attempt refused to execute because the current source revision had
changed since preview. The change's own earlier commit had caused that
mismatch. A separate read-only verification later established that the issuer
change was live and accepted by the engine. The original approval record still
reported a stopped outcome. Neither that record nor the successful live state
alone told the whole story.

The design material also distinguishes backup artifact checks from approval
authentication. Matching checksums, freshness, and request metadata can prove
that an artifact belongs to a request. They do not, by themselves, prove that a
human approved the privileged action that created it.

## What works

Make preview an immutable proposal. Bind the request identity to the selected
runbook, target, arguments, source revision, dependency revisions, and diff
digest. Present a concise effect and risk summary to the owner. A decision
should refer to that exact proposal, rather than to a title that can remain
unchanged while its contents move.

Use a BPMN user task for the decision, with explicit approval and rejection
paths. Candidate groups help route the task, but group labels do not establish
that the intended owner is a member. Verify task visibility and completion
permissions with the real identity. Notifications should link to the task;
receiving or acknowledging a notification should not authorize execution.

At execution, independently re-check the approval binding and the cataloged
action. Reject changed inputs. A stale-input refusal is useful evidence and
should lead to a new preview when necessary. Do not weaken the check merely
because a previous attempt probably performed the action. Instead distinguish
an untouched request from a request with durable evidence of earlier execution.

Treat activation timeouts as leases. The approximately one-hour timeout in the
record was a configured value, not a universal engine delay. A lease expiring
can lead to another activation even when a subprocess already caused an
external effect. Persist an execution receipt and make actions idempotent or
explicitly guarded against repetition. A completion RPC timeout means the
acknowledgement is uncertain; it does not mean the external action was reversed.

Verification should poll a capability with a deadline and a useful failure
summary. The reviewed design allowed repeated verification for up to ten
minutes. Choose a budget appropriate to the actual rollout. Distinguish a
transient rollout state from a terminal failure, while preserving a finite
stop condition. Readiness, exact deployed revision, and a real authenticated
request answer more than the existence of a running process.

Keep reporting as its own stage and retain evidence outside the completion
acknowledgement. If the engine or identity service restarts, an independent
read-only recovery procedure should reconcile the external result, execution
receipt, and workflow state. Recovery runbooks must already be available where
they run; delivery that depends on the broken service cannot be assumed.

For privileged work, define a separate trust boundary. Verify the actual
completed approval and request binding through a protected authority. Files
writable by the same identity that requests execution cannot establish an
independent approval merely by containing convincing metadata.

## What we did not verify

- We did not prove exactly-once external effects under crashes or uncertain job
  completion. Durable receipts and idempotency require their own fault tests.
- We did not establish that every privileged execution path enforces independent
  approval, or that all required identities and credentials were provisioned.
- We did not measure a universally suitable job timeout or verification budget;
  the observed durations belong to the recorded configuration.
