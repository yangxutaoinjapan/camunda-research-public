# Parent and child workflows with message starts

## Problem

Multiple producers can start the same kind of work: a browser form, a script,
a timer, or a delivery event. If each directly starts a business process,
request identity, duplicate handling, permissions, and outcome reporting become
separate implementations. A parent workflow offers one place to establish the
request before calling a child that performs the business work.

The design here used typed message ingress and a unique business request ID.
That ID was stable across retries of the same request. It was not the engine's
process instance key, and it was not regenerated whenever a caller lost a
response. A durable admission record connected the outside request to the
parent and, later, to the child.

A crucial consequence is that a callable child is not an alternate public
entry point. It expects state created by admission and parent reservation.

## What we observed

The recorded implementation reserved request identities in a ledger, recorded
parent and child instance keys, and rejected reuse of an identity with different
payload contents. One accepted command-line route started a parent; identical
replay returned the same parent instance, while conflicting payload reuse was
rejected. Another route also returned its original instance on identical replay.
A real message-driven delivery completed through both parent and child.

Source review found that each callable child's first attachment task checked
parent ledger variables. A child started directly without those variables
failed the gate and ended without doing business work. This is evidence from
the reviewed model and ledger code, rather than a claim that every direct-start
negative case was exercised against the live engine.

The migration evolved from a single activation step to proposed, coexisting,
and active stages. Coexistence allowed individual producers to move while
legacy ingress still existed. Parent timer starts were initially deferred so
that deploying the parent did not immediately duplicate the old schedules.
Later readback recorded the legacy ingress definitions replaced by models
containing only a start and an end, followed by retirement of old authorities.

A permission assumption also changed during live acceptance. In the tested
Camunda 8.10 configuration, message-start callers needed permission to create
instances of the parent as well as their exact message grants. Earlier static
reasoning that message grants alone were enough was corrected by the live test.

## What works

Give each logical request a stable identity at its origin. Bind it to ingress
route, authenticated actor, and a normalized payload digest. Identical retries
should return the existing result or a bounded pending status. A conflicting
payload with the same ID should fail before dispatch. Document how long this
identity remains reserved; message publication alone is not a durable business
idempotency contract.

Serialize admission around the durable record. Persist dispatch ownership and
retain the original parent key. If dispatch becomes uncertain, reconcile it
rather than blindly send another message. The source review found that reserving
only inside an already-started parent could allow duplicate parents to start
and then exit. Preventing duplicate business execution and guaranteeing one
parent instance are different promises.

Have the parent reserve the request, validate its typed route, and pass explicit
inputs into a call activity. The child's first task should attach its instance
to the existing reservation and verify the expected parent context. Missing or
mismatched ledger variables must stop execution. Hiding a start button is not
an execution boundary, and a plain start event does not remove the child's
application-level prerequisites.

Migrate through coexistence. First deploy the ledger schema and prepare narrow
identities, then deploy compatible child and parent definitions with new ingress
held off. Prove the route before switching its producer. During this stage,
rollback can be a producer mode change, provided request records and uncertain
dispatches remain intact. Never silently fall back from a failed parent request
to direct child creation.

Hand timers over explicitly so old and new subscriptions do not both schedule
business work. After producer acceptance, replace retired legacy definitions
with the start-to-end models under their existing process IDs. Merely removing
files from a deployment catalog does not retire already deployed subscriptions.
Preserve existing active instances and their historical definitions; replacing
the latest model is not a reason to rewrite completed history.

Finally revoke old direct-start and wildcard permissions after readback proves
the new routes work. Check the actual grants and attempted cross-route denial,
not just source declarations. Keep the human launch path until a replacement
can supply the required stable identity and ledger context. An apparently
redundant form may be the only supported admission path for manual requests.

## What we did not verify

- We did not establish full live acceptance for every route, identity, duplicate
  race, cancellation, or notification. Several acceptance items remained open.
- We did not prove one-parent behavior across every crash point or indefinitely
  retained deduplication; the recorded replay checks cover specific routes.
- We did not exercise all historical instance migration and rollback cases, or
  generalize the observed permission requirements beyond the tested version.
