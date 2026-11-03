# Design: message-started role and draining children

The current design retains v3's real call-activity children and parent/root
ancestry. `role.open` starts the top-level role with `roleKey` and `deadline`
(an ISO instant); no BPMN start cron or polling variable remains.

A parallel fork opens three lane scopes. Each normal flow is start → event-based
gateway → either absolute deadline timer or `role.close` message catch → end.
Both catches race; the winning catch cancels the other wait. The close message
uses `roleKey` as correlation key. No interrupting boundary event cancels work.
Each lane also owns a non-interrupting `work.a|b|c` event subprocess containing a
call activity. Calls run `lab-child-a|b|c`, map workMillis and roleKey, and do not
propagate child variables. The engine supplies parent/root process instance IDs.
The lane remains alive until its active event scopes/calls finish; the join
waits for all lanes. This preserves v3's deadline plus drain semantics.

Zeebe correlates each message once per BPMN process ID. The operator close
command publishes three independent role.close messages with the same roleKey
and 60-second TTL to reach the three lane subscriptions. Live verification of
this fan-out, subscription cancellation, and drain behavior is pending. Work
arrival racing closure follows engine ordering; publication acceptance alone
does not establish correlation. Incidents or unbounded work can prevent drain.

The same-JVM scheduler runs inside `serve` every 15 seconds, immediately on
startup. UTC rules and exact API shapes are documented in the [README](../README.md).
Month days are restricted to 1–28; minute intervals are epoch-aligned. Close
is a positive duration or a date/time in the next monthly period. Config is
validated server-side and atomically persisted before becoming effective.
The default is disabled. Switching off stops new scheduled roles, leaving
existing engine timers and children running. Manual open uses a fresh UUID key
and the configured close rule; manual close targets the newest waiting role.

Each tick finds the current period, searches by roleKey including completed
instances, and publishes role.open only if absent. MessageId equals roleKey,
TTL 60 seconds, so repeated ticks during normal export lag are de-duplicated.
This is a single-scheduler lab, not a transactional distributed scheduler:
search lag beyond the message retention window or purged history can permit a
repeat. Changing rules with the same key leaves an existing role unchanged.

The cost is that the schedule lives in the lab process: it depends on that
process being up. Catch-up after restart bounds the damage to missed historical
periods; it opens only the current period, even if its deadline has passed.
It does not reconstruct all missed periods. Engine timers continue without
serve. JVM and engine clocks must be synchronized. Scheduled windows can overlap;
the page publishes to the newest still-waiting role.

The minute generator changes only the process ID, preserving the same BPMN.
Verified FEEL minute key/deadline expressions stay as engine preflight checks;
the shared Java scheduler supplies actual start variables. The experiment
aligns to a fresh minute and drives this scheduler directly; it verifies child
ancestry and overlapping drain using REST searches, then stops ticking and
cleans up even on failure. Deploy variants are mutually exclusive on one lab
engine because both use role.open. Cleanup retains shared child definitions.

Previous designs: v1/v2 used inline service-task event scopes and a fixed timer
start; v3 introduced child call activities but retained that start cron. Their
measurements do not establish the current scheduler's behavior. The previous
controlled-clock exporter failure is retained in findings; current proof uses
real UTC time. Neither this revision nor v3 has been verified live here.
