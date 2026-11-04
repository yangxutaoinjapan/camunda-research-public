# Experiment 01: Role-bounded lifecycle

> **The question.** Can a Camunda 8 process represent a *time window that owns
> its work*: it opens on a schedule, accepts events while open, stops accepting
> at a deadline (or when told to), and then stays alive just long enough for
> the work already started to finish, using only the engine's own elements and
> no job that polls the clock?

If Camunda is new to you, read the
[background in the root README](../../README.md#background-in-five-minutes)
first (five minutes). The rest of this page assumes only that.

## Why anyone would want this

Think of a **shift** at a help desk. At 09:00 a shift opens. All day, requests
arrive and each one is handled by someone. At 17:00 the shift closes: nobody
should *start* new requests under it, but a request half-handled at 16:58 must
not be thrown away. The shift is over only when the last request is done. Later
you want to ask: "which requests belonged to the 09:00 shift, and is it
finished?"

Here the shift is called a **role** (it owns a period of responsibility), and
the requests are **work events** of three kinds, called **lanes** `a`, `b` and
`c`. A month is the default period, but any schedule works: the page can open a
new role every minute so you can watch the whole life cycle in a few minutes.

## What the process does

```
  schedule tick or "Open" button
            │  message  role.open  (roleKey, deadline)
            ▼
   ┌────────────────────────── role (one process instance) ───────────────────────────┐
   │                                   fork                                           │
   │        ┌──────────────────────────┼──────────────────────────┐                   │
   │      lane a                     lane b                     lane c                │
   │   ┌──────────────┐           ┌──────────────┐           ┌──────────────┐         │
   │   │ wait for the │           │     (same)   │           │     (same)   │         │
   │   │ FIRST of:    │           └──────────────┘           └──────────────┘         │
   │   │  • deadline  │                                                               │
   │   │  • role.close│     meanwhile, inside each lane, at any time while it lives:  │
   │   └──────┬───────┘                                                               │
   │          │           message work.a ─▶ event subprocess ─▶ call activity ─▶ child │
   │          │           (non-interrupting: many can run side by side)                │
   │          ▼                                                                       │
   │   lane ends only when its race is decided AND no child is still running (drain)  │
   │                                   join                                           │
   └─────────────────────────────────── role completes when all three lanes ended ───┘
```

In words:

1. A **message** `role.open` *starts* a role instance. It carries a `roleKey`
   (the name of the period, such as `role-2026-11` or `role-2026-11-02T12:00`)
   and a `deadline` (an absolute timestamp).
2. The role forks into **three lanes**. Each lane waits on an **event-based
   gateway**: the deadline *timer* races a `role.close` *message*. Whichever
   comes first ends the wait and cancels the other.
3. While a lane is alive, a `work.a` / `work.b` / `work.c` message starts a
   **non-interrupting event subprocess** that contains a **call activity**. The
   call creates a real **child process instance** whose parent *and* root are the
   role. Children run in parallel, and neither the race nor other children are
   disturbed.
   Nothing claims a child: it is **started by the `work.*` message and finished by
   a message** (`work.a|b|c.done`, correlation key `childId`) that the Java side
   publishes once the child's work time has passed. There is no job worker and no
   service task in a child.
4. A lane does not finish when its race is decided; it finishes when the race
   is decided **and** every child it started has completed. That is the
   **drain**.
5. When all three lanes have drained, the role completes.

So the diagram *is* the life cycle: open by message, close by deadline or
command, drain by waiting for children. There is no lifecycle job that wakes up
to look at a date. A child whose finish message never arrives can still
prevent a drain; the Java side therefore re-sends it from the engine's own state
(a reconciler), so a restart of the lab cannot strand a child.

## What you see on the page

`scripts/lab.sh serve` starts a small control page (plain HTML, no framework):

- **Schedule**: switch the automatic opening on or off and choose when roles
  start and how long they stay open (rules below).
- **Role table**: one row per role with its state (waiting, draining,
  completed), start, deadline, end, and per-lane counts of running and finished
  children. A manually opened role gets a short name.
- **Open / Close now / Cancel**: open a role by hand; close the newest waiting
  role (the three lanes close at once, children keep running); or cancel a role
  and everything under it.
- **Send events**: pick a lane and a count to publish `work.*` messages to the
  newest open role and watch children appear and drain.

## Glossary

| Term | Meaning in this experiment |
| --- | --- |
| **Role** | The top-level process instance for one period. Identified by its `roleKey`. |
| **Lane** | One of three parallel branches (`a`, `b`, `c`) inside a role, each with its own deadline race and its own kind of work event. |
| **Child** | A process instance started by a call activity inside a lane. Its parent and root are the role. |
| **Deadline** | The absolute UTC time after which a lane stops waiting for the race. |
| **Close** | Ending the race early by the `role.close` message. Three messages are needed because each lane is a separate subscription. |
| **Drain** | The period after the race is decided in which the role stays alive until its children finish. |
| **Tick** | The scheduler's check, every 15 seconds, whether the current period already has a role. |

## Frequently asked questions

**Why three lanes instead of one?** To show that closing and draining are
independent per branch while the role as a whole waits for all of them.

**Why not interrupt running work at the deadline?** Because stopping started work
is exactly the failure mode the experiment is about. The deadline only decides
that no *new* work is accepted into the role; started work finishes.

**What happens to an event that arrives after the deadline?** The page sends it
to the newest role that is still waiting; if none is, the request is refused.
Late events sent to an old role by key are listed as *not yet measured* in the
findings; acceptance of a message is not proof that it was correlated.

**Why a scheduler in the lab process rather than a timer in the diagram?** A
timer start event would be the engine's own mechanism, but it fixes the schedule
in the deployed diagram. A message start lets an operator change the schedule at
run time. The price is that the schedule depends on the lab process being up;
the design document states this and what catch-up does after a restart.

**Is this production-ready?** No. It is a lab. The findings record which runs
were made and which behaviours are still unverified.

## Where to read next

- [Design](docs/design.md): how the race, the event subprocesses, the close
  fan-out and the scheduler interact, and what the costs are.
- [Findings](docs/findings.md): measured results and pending items, each with
  its status. Earlier designs are kept for history and clearly labelled.
- [Deployment](deploy/README.md): running the lab on a small host.

## Summary of the technical contract

Java 21, Camunda 8 / Zeebe, no Spring. A message-started role owns a scheduled
window. Pushed `work.a|b|c` messages create real call-activity children with the
role's instance key as parent and root. Each lane races its absolute `deadline`
against `role.close`, then drains its non-interrupting event subprocess calls. Each child waits for a
finish message that Java publishes; no job worker takes part.
The top-level role completes when all three lanes have drained. No lifecycle
polling job or `pollInterval` variable is used. A child whose finish message
never arrives can prevent drain.

## Run

Requires JDK 21, Maven and Camunda client 8.10.7 plus cached transitive libraries
for offline builds. From this directory, build with `mvn -q -o package` when
packaging is appropriate. Keep `target/role-bounded-lifecycle.jar` and `target/lib/`
together. `scripts/lab.sh deploy` deploys the message-started role and three child
definitions in one request. `scripts/lab.sh serve` runs the page, scheduler and
child finisher in one JVM; it does not deploy. Open http://localhost:8088/.

The page is **Role lifecycle**: schedule controls, a dense role table with
parent-filtered child counts, and event sending to the newest open role.
Manual opening works while the schedule is off. Turning it off prevents new
scheduled roles; existing roles finish normally. Close now publishes three
independent `role.close` messages (one correlation per BPMN process ID per
message) to close all three waits without interrupting children. Acceptance is
asynchronous; inspect engine state before retrying partial failures.

## Schedule

UTC start rules: `day of month D at HH:MM` (D 1–28), or
`every N minutes` (N 1–1440, epoch-aligned intervals). Close rules:
`after N minutes`, `after N hours`, `after N days` (N 1–999999), or
`at day of month D HH:MM of the next period` (month start rules only).
Calendar close means that day/time in the month following the period start.
Defaults: disabled, day 1 at 00:00, closes day 1 at 00:00 next month.

Every 15 seconds, including immediately on startup, the scheduler searches the
role definition by `roleKey`, including completed roles. If absent it publishes
`role.open`, messageId = roleKey, TTL 60 seconds, with `roleKey` and ISO instant
`deadline`. Keys use period start: `role-yyyy-MM` or `role-yyyy-MM-ddTHH:mm`.
Manual keys use `role-manual-UUID`; the configured close rule determines deadline.
A manual open with an already-past calendar close is rejected.

Config is atomically replaced in `${STATE_DIRECTORY:-/tmp}/schedule.json`.
Systemd supplies `STATE_DIRECTORY=/var/lib/lifecycle-lab` from
`StateDirectory=lifecycle-lab` and protects it with mode 0700. Invalid saved
config fails startup. There is no new secret. Run one scheduler per engine/lab:
search is eventually consistent and message de-duplication has a finite TTL.
Changing rules within an existing period does not change existing deadlines.

The schedule lives in the lab process and depends on it being up. Restart
catches up the current period, possibly with a past deadline that immediately
closes its waits; it does not replay every missed period. Engine deadline timers
continue independently of `serve`. See [design](docs/design.md).

## HTTP and environment

`CAMUNDA_REST_ADDRESS` defaults to `http://localhost:8080` (without `/v2`).
Client authentication uses standard client variables and optional
gateway credential support (`GATEWAY_CLIENT_ID` / `GATEWAY_CLIENT_SECRET`, sent as request headers). `LAB_HTTP_BIND=127.0.0.1`,
`LAB_HTTP_PORT=8088`. The command line `publish` takes `--millis N` (default 200).
`LAB_BASE_PATH` is empty by default; `/lifecycle` serves the prefixed page and
relative APIs. Use only lowercase letters, digits, `/`, `_`, `-` in the prefix.
`LAB_WINDOW=minute` selects the minute proof definition for `serve`.
`POLL_TIMEOUT_SECONDS=150` sets each live assertion timeout.

All API requests use `X-Lab-Token` when `LAB_HTTP_TOKEN` is set. An empty token
is invalid; without a token binding must be loopback. Use an authenticated proxy
for public access. POST requires JSON, maximum 4096 bytes, duplicate keys and
trailing JSON rejected. Keys are strings to preserve 64-bit precision.

Exact schedule API (paths relative to the base path):

`POST /api/schedule` accepts exactly:

```json
{"enabled":true,"start":"every 1 minute","close":"after 1 minute"}
```

`GET /api/schedule` and successful POST return this shape (times illustrative):

```json
{
  "config":{"enabled":true,"start":"every 1 minute","close":"after 1 minute"},
  "effectiveState":"Current period exists: role-2026-11-02T12:00",
  "now":"2026-11-02T12:00:15Z",
  "currentRoleKey":"role-2026-11-02T12:00",
  "currentClose":"2026-11-02T12:01:00Z",
  "nextOpen":"2026-11-02T12:01:00Z",
  "nextClose":"2026-11-02T12:02:00Z"
}
```

`effectiveState` is a descriptive string (off, awaiting tick, current period
exists, open requested, or scheduler request failed). Planned times are shown
even while disabled. The clock in this response is the scheduler JVM's UTC
clock; `GET /api/clock` returns `{"now":"ISO instant"}` from engine FEEL.
Keep host and engine clocks synchronized.

`POST /api/open` and `POST /api/close` accept exactly `{}` and return
`{"roleKey":"..."}`. Close targets the newest role still waiting, rather than
an older draining role. `POST /api/publish` accepts exactly
`{"lane":"a","count":1,"workMillis":200}` (count 1–20, workMillis 0–600000)
and returns `{"roleKey":"..."}` for the newest open role. No open role is a 400.
Validation errors return `{"error":"..."}` (400), auth failure 401, unsupported
method 405, engine or persistence failure 502. Publication can be partial.
`GET /api/roles` returns `roles` plus all-lab `instances` ROOT/CHILD totals;
role rows include key, roleKey, state, start, closes, end, incident and lanes
with ACTIVE/COMPLETED counts. Search may lag; successful rows cache for 2 seconds.

## Minute proof and cleanup

On a fresh exclusive disposable engine/search store in UTC, run
`scripts/lab.sh experiment --cleanup`. No distribution is downloaded here;
`scripts/run-local.sh` uses an already-unpacked `C8RUN_HOME`.
The minute variant comes from the same BPMN by changing the process ID only;
there is no cron substitution. The verified minute key/deadline FEEL expressions
remain engine-preflight checks. The experiment directly drives the shared
scheduler with `every 1 minute` / `after 1 minute`, begins at a fresh minute,
and checks two root roles and 18 real children with correct ancestry, overlapping
drain and completion after deadline. A separate early-close phase adds three
20-second children and checks all message waits complete while children drain
(final total 2 roots, 21 children). The child finisher runs in the same JVM. Do not deploy
both role variants together: they share the `role.open` message name.

Cleanup always runs on success/failure. `scripts/lab.sh --cleanup` separately
cancels active minute roles (cascading to children) and deletes minute definitions;
shared children remain deployed. There is no recurring engine start timer.
Stop the disposable engine after collecting results. A live proof and engine
permissions remain unverified. Earlier inline measurements are historical;
see [findings](docs/findings.md) and [deployment](deploy/README.md).

Client heap defaults below 256 MiB; RSS and
engine/search memory are separate and require measurement on the target node.
