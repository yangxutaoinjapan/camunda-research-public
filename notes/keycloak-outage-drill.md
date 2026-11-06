# A short Keycloak outage is also a worker recovery test

## Problem

A signed token can outlive the service that issued it. That makes it tempting
to assume that a short identity outage only blocks new sign-ins and token
refreshes. Such a claim depends on receiver behavior, cached signing keys,
discovery dependencies, and the client's error handling. It needs an actual
outage test.

We deliberately stopped Keycloak for 150 seconds and observed several paths.
The purpose was to distinguish failure to obtain a new credential from failure
to use an existing one. A second purpose emerged during execution: the worker
running the change depended on the very token service being stopped.

The useful unit of evidence is one caller and one receiver during a bounded
window. A result from one API does not characterize all Camunda components or
all services using the same realm.

## What we observed

The drill reduced the Keycloak instance count to zero, waited 150 seconds, and
restored it. Total time until the restored service was Ready was approximately
three and a half minutes. The deliberate stop duration and the observed
recovery duration therefore described different intervals.

Five probe rounds during the outage attempted a fresh token request and loaded
the public realm page. Both paths failed closed with HTTP 503 responses. The
recorded response durations ranged from roughly a tenth of a second to just
over two seconds. We did not observe a successful new token or available login
page in those rounds.

A token obtained before the stop was also sent to one receiver's information
API. That receiver returned HTTP 500 in every round. The expectation had been
that cached signing keys would allow continued validation. The measured result
contradicted that availability assumption. It establishes that the protected
request did not keep working, without establishing precisely which internal
lookup or cache policy produced the failure.

The host worker subsequently failed to refresh its Zeebe token, encountered a
connection refusal at the token endpoint, and exited expecting its service
manager to restart it. The active execution job was not completed, and the
workflow's verification step did not run. The job remained open for its
configured timeout, approximately one hour, after which it could be offered
again. Some other process completions continued later, but that does not prove
how those clients behaved during the outage itself.

## What works

Design the drill as a bounded fault with an independent recovery path. Before
stopping anything, capture the original desired instance count, confirm who
controls it, and arrange restoration outside the worker's lifetime. In this
case an exit trap and a separate dead-man timer were part of the prepared
script. The planned maximum fault window was 240 seconds. Neither a trap alone
nor a workflow task that requires a new token is a sufficient recovery design.

Check the GitOps controller's reconciliation behavior before a temporary scale
change. In the observed setup automatic synchronization was disabled, so it did
not immediately undo the stop. Another setup might reverse the fault early or
fight the restore. Make that interaction explicit rather than interpreting an
unplanned reconciliation as a successful resilience test.

Make destructive execution replay-safe. A job lease can expire without the
business action being undone. A retried job must not stop Keycloak again just
because its first result was never acknowledged. A guard was added to refuse
execution when a drill log less than six hours old existed. For a reusable
implementation, bind a durable execution record to a request identity and
create it before the disruptive step. The age guard is a concrete safeguard;
it is not a substitute for defining request-level replay semantics.

Probe three separate capabilities: issuing a fresh token, opening the browser
login path, and using a previously issued token against a named receiver. Keep
token values out of logs. Record response status and elapsed time, with outage
and restore timestamps. Do not label all authentication healthy because one
cached client still works.

After restoration, independently verify service readiness, discovery, signing
keys, and a client-credentials grant. Confirm that the restored instance is
newer than the action start when that is part of the test contract. Then inspect
the workflow and worker: infrastructure recovery does not imply that the job
completion or verifier ran. Close those evidence gaps through read-only checks
before considering any replay of the disruptive action.

## What we did not verify

- We did not identify the receiver's exact internal reason for rejecting the
  pre-issued token, or prove a corrected cached-key implementation.
- We did not characterize every worker, every Camunda UI, or every protected
  API during the 150-second interval.
- We did not demonstrate that the later worker changes survive a repeat of
  this complete outage drill; implementation and recovery proof remain separate.
