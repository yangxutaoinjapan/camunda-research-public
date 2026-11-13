# Findings: experiment 01

Status (2026-11-13): the message-driven design (children started and finished by
messages only, no job worker, no service task) has now run live three times: the
17-assertion minute-variant proof of 2026-11-04, a 72-hour role that started the
same day, and a 3-hour role on 2026-11-13. The results are at the end of this file
("Scheduled runs"). Everything between this paragraph and that section describes
earlier designs or earlier states of the same one and is kept as history; where it
says "pending" it means pending at that date.

Previous v3 status: implemented and checked offline; no engine available for the new live
proof. Parent/root ancestry, call completion, cancellation cascade, deployment,
permissions and ROOT/CHILD totals remain unverified live.

The 2026-11-02 result below belongs to the **previous inline design**: events
were embedded element scopes with service tasks, not child process instances.
It proves no parent-instance relationship. Its page counters also counted scopes.
The v3 live proof will be added after a run; historical measurements are retained.

Previous design: measured live on Camunda 8 Run 8.10.7 (single partition, H2/RDBMS
secondary storage, TZ=UTC, 2026-11-02). The real-time minute scenario passed all
13 assertions in one run (below). One sample is not a statistical result; the
month boundary itself is not exercised.

## Previous inline result: real-time minute run (2026-11-02)

| Role | Start (engine) | End (engine) | Event scopes | Lifetime |
| --- | --- | --- | --- | --- |
| W1 | 05:13:00.419Z | 05:14:12.621Z | 12 | 1 min 12.2 s = window 59.6 s + drain 12.6 s |
| W2 | 05:14:01.002Z | still active | 6 | n/a |

- While W1's three deadline timers were COMPLETED and one slow (70 s) event scope
  was ACTIVE in every lane, W1 stayed ACTIVE and W2 had already started.
- After the boundary the publisher (engine clock) routed to W2's key; W1 stayed
  at four event scopes per lane, W2 handled two per lane.
- W1 completed only after the slow work finished, after its deadline; W2 stayed
  active. The scope-completes-when-no-event-sub-process-is-active rule holds.
- Cleanup cancelled active minute roles and deleted the generated definitions.

Not yet measured: client RSS on a 1 GiB host, late events (old key after
completion), engine downtime at 00:00, real month boundary and cron time zone.

## Previous inline front end (`serve`)

Checked locally against the same engine in a browser: the page renders the
current role, lane counters, countdown and the publish form; publishing three
events on lane a showed Completed 3 for the role. `LAB_WINDOW=minute` makes
`serve` follow the generated minute variant so this can be watched in minutes.

## Controlled-clock investigation

With `ZEEBE_CLOCK_CONTROLLED=true`, jumping a month ahead stalls the RDBMS
exporter: REST process-instance, element-instance and job searches stop showing
new records until the clock is reset to real time. Search assertions cannot
support this proof under controlled time. Zeebe itself continues to work: jobs
can be activated and messages correlate.

A timer start required a second small clock step after a large jump and a short
pause. `ClockController` retains that workaround for investigation, but is unused
by `experiment`.

Verified actuator paths on 8.10.7:

| Action | Request | Body |
| --- | --- | --- |
| Read | `GET /actuator/clock` | none |
| Pin | `POST /actuator/clock/pin` | `{"epochMilli": …}` |
| Add | `POST /actuator/clock/add` | `{"offsetMilli": …}` |
| Reset | `DELETE /actuator/clock` | none |

FEEL `string(now())` rendered `2026-12-01T00:00:06@UTC`. Minute expressions use
the first 16 characters and build an explicit UTC deadline; engine evaluation
checks both expressions before deploying. Timestamp parsing supports that zone-ID
format as well as offset timestamps.

## Previous v3 run record (pending; historical design, not current evidence)

Pending: date/operator, engine/Java versions, UTC settings, search/exporter
configuration, FEEL preflight output, full PASS/FAIL output, cleanup API support,
client heap/peak RSS, and engine memory.

| Observation | Expected | Evidence |
| --- | --- | --- |
| W1 cron | Key matches engine start minute, next-minute deadline, three active branches | Pending |
| Quick work | Three completed child instances per lane; parent/root = W1, W1 active | Pending |
| Slow work | One 70000 ms child active per lane; parent/root = W1 | Pending |
| W2 boundary | W1 timers completed and slow events active; W2 different key and three branches | Pending |
| W2 routing | Two completed children per lane, parent/root = W2; W1 stays at four | Pending |
| Drain | W1 four completed events per lane, end after deadline; W2 active | Pending |
| Instance totals | 2 roots and E=18 children across lab definitions; compare E naive roots | Pending |
| Lifetime | Engine start/end; window plus drain, one sample | Pending |
| Cleanup | Active minute instances canceled, minute definitions deleted | Pending |

The monthly BPMN remains the production artefact; the minute variant is generated
from it. This proof does not exercise a real month boundary, monthly cron time
zone behavior, or engine downtime at midnight.

## Previous-design additional probes

- Late events: publish old keys during drain and after completion, with zero and
  positive TTL. Measure subscription lifetime and correlation, not just acceptance.
- Boundary race: sample engine time before a boundary and delay publication until
  afterward. Clock sampling and arrival are not atomic.
- Downtime at midnight: stop before the first, restart afterward with persisted
  state, and measure missed/caught-up/duplicate starts and activation mappings.
- Memory: measure client heap/RSS under workload, separately from engine memory.

If deletion is unsupported or denied, cancellation alone does not stop future
cron starts: stop the disposable engine. Always stop it after collecting results.
No live minute run or memory measurement was performed during this offline change.

## 2026-11-04: stuck children behind a job worker

Observed on the live engine (read-only): role instance 11258999071103818
(`worker-role-monthly`, `role-2026-11`, started 2026-11-03 16:47Z) stayed ACTIVE while
new children completed every few seconds. Eight child instances per lane (24) created
between 16:48:25 and 16:49:05 on 2026-11-03 were still ACTIVE twelve hours later at the
service task `work_a|b|c`: Operate job state CREATED, no worker, retries 3, no incident.
Job metrics: created minus completed was 8 for each of `lab.work.a|b|c`, failed 0.
The worker polled every 100 ms and completed 1074 later `lab.work.a` jobs, so the 8
were not waiting for a worker; they had been taken and not completed. Not proven: how
the response was lost (the engine does not export job records). The settings that make
a loss permanent: job timeout 90 days, request timeout 15 s, and a handler that returned
without completing or failing the job when interrupted. Consequence: a lane drains only
when no child runs, so one lost job holds the role open.

Change: children no longer use jobs (see design.md, "Children are started and finished
by messages"). The new behaviour is unverified on a live engine until the experiment
runs.

### Live proof of the message-driven children (2026-11-04)

Revised artefact, minute variant, live Camunda: all 17 assertions
PASS, including a child of 70 s that kept its role draining and finished by message,
three-lane early close, 18 children with correct ancestry in the deadline phase and 21 in
total, and cleanup of the minute roles. Role lifetimes: W1 PT1M23.883S (window PT59.69S +
drain PT24.193S), W2 PT53.007S. No job worker took part. One sample; not a load test.

### 2026-11-04: request volume

The reverse proxy in front of the engine counted about 3.4 requests a second (26,735 in
131 minutes) on 2026-11-04. The lab client was one source: the finish-message reconciler
searched every 3 s and the scheduler every 15 s, and an open page refreshed the role table
every 5 s. Intervals are now 30 s for the reconciler (the delayed publish is the fast path,
the reconciler only a backstop), 60 s for the scheduler, 15 s for an idle page (3 s right
after an action) and a 10 s cache for the role table. Not measured after the change: the
new request rate.

## Scheduled runs: one root instance per window (2026-11-04 and 2026-11-13)

Engine: self-managed Camunda 8.10, the lab client on a small host. Both roles were started
through the lab client, not by a test harness; the numbers below are read back from the
engine's search API after the run (read-only).

| | Role of 2026-11-04 | Role of 2026-11-13 |
| --- | --- | --- |
| Window | 72 h (05:38 to 05:38 UTC) | 3 h (03:11 to 06:11 UTC) |
| Root instances | 1 | 1 |
| Children `lab-child-a` / `b` / `c` | 1040 / 980 / 440 (2460) | 40 / 40 / 60 (140) |
| Children whose parent and root are the role | all | all |
| Children COMPLETED / still ACTIVE | 2460 / 0 | 140 / 0 |
| Children with an incident | 0 | 0 |
| Child lifetime | 31.5 to 238.5 s | 120.0 to 120.2 s |
| Role lifetime | 72 h 0.7 s | 2 h 59 min 59.9 s |
| Job workers involved | none | none |

What this shows:

- With a role that owns the window and children started as called instances, a 72-hour
  window with 2460 units of work produced one root instance. The design the experiment
  replaced (message-start children) would have produced 2461.
- The failure that motivated the redesign (24 children left ACTIVE behind a lost job) did
  not occur: nothing was left ACTIVE after either role ended, and no incident was raised.
- The role ended at its window end although children were still being created until
  about seven hours into the first window; the scope-completion rule needs no polling.

What it does not show:

- On 2026-11-13 all 140 children were started within 14 seconds, early in the window, so no
  child was still running when the window closed in either run (the minute-variant proof of
  2026-11-04 does cover that, with a 70 s child).
- One engine, one version, two samples. The request rate after the 2026-11-04 interval
  change, client memory on the 1 GiB host, a month boundary and engine downtime during a
  window were not measured.
- Operate or search latency under a much larger child count was not measured; the largest
  role here has 2460 children.
