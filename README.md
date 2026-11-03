# Camunda Research

Small, reproducible experiments about how Camunda 8 behaves when a business
process has to be *bounded in time* but its work cannot be cut off.

Each experiment states a thesis, ships runnable code, and keeps what was
**measured** apart from what was **assumed**. Nothing here is a product, a
benchmark suite or a recommendation; it is a lab notebook with working code.

## Background in five minutes

You do not need to know Camunda to follow the experiments. Five ideas are enough.

**Camunda 8** is a workflow engine. You draw a process as a diagram in a
standard notation called **BPMN** (Business Process Model and Notation), deploy
the diagram, and the engine runs it: every run is a **process instance**. The
engine itself is called **Zeebe**; it keeps the state of every instance and
decides what happens next. Your own code never runs inside the engine. It
connects from outside through a client library and takes jobs from it.

The diagram elements that matter here:

| Element | What it does |
| --- | --- |
| **Message** | An outside event (`role.open`, `work.a`, …) the engine matches to a waiting instance by a *correlation key*. A message can also *start* a new instance. |
| **Timer** | A point in time (here: an absolute deadline) at which a waiting path continues. |
| **Event-based gateway** | A fork where two or more things wait *at once* and the first to happen wins; the other wait is cancelled. A deadline timer racing a "close now" message is the classic use. |
| **Call activity** | A step that starts a *separate* process instance (a child) and waits for it. The child knows its parent and the root of the whole tree. |
| **Event subprocess** | A small process that lives inside a larger one and starts whenever its trigger arrives. A **non-interrupting** one runs next to the normal flow instead of stopping it. |
| **Job worker** | Your code. It asks the engine for work of a given type, does it, and reports back. |

## The problem these experiments start from

Many real processes are not "one request in, one result out". They are
*windows*: a billing period, a shift, a campaign, an on-call rotation. During
the window, events keep arriving and each one starts real work. When the window
ends you want exactly two things:

1. **no new work** is accepted into it, and
2. **work already started is allowed to finish**, not killed.

The obvious designs each break one of those:

- *One long-lived instance per window that is terminated at the end* kills work
  in flight.
- *One instance per event, with no window* gives you no unit that "owns" the
  work, so you cannot ask "is this period done?" or see which events belonged
  to it.
- *A polling loop that checks the clock* works, but adds a job that runs all
  the time just to look at a date, and hides the lifecycle from the diagram.

The experiments ask whether the engine's own building blocks (a message start,
a timer racing a message, non-interrupting event subprocesses, call activities)
can express the window directly, so that the diagram *is* the lifecycle and a
trace of any child leads back to the window that owned it.

## Experiments

| Experiment | Thesis | Status |
| --- | --- | --- |
| [01 — Role-bounded lifecycle](experiments/01-role-bounded-lifecycle/) | A message-started "role" owns a scheduled window. Events create real child process instances with the role as parent and root. The role closes at a deadline or on command, then *drains*: it stays open until its children finish. | Schedule, engine behaviour and a plain control page implemented; the experiment's findings say exactly what was and was not verified |

Start with the experiment's README: it opens with the question in plain
words, a diagram of the process and a glossary, before any configuration.

## Working paper

[`paper/working-paper.md`](paper/working-paper.md) (English) and
[`paper/working-paper.ja.md`](paper/working-paper.ja.md) (Japanese) hold an experience
report, "When my operational checks answered the wrong question": episodes from
operating a small home-lab platform where a successful check meant less than it
seemed to. It is a working paper, not peer reviewed, and **its full text was written
by AI assistants (Claude and Codex)**, which the paper states in its own text.
It is not specific to Camunda and is kept here with the experiments as part of the
same notebook.

## Reading the evidence

- Each experiment has a `docs/findings.md`. A claim is either backed by a run
  record (date, versions, what was observed) or it is marked **pending**.
- "Implemented" means the code exists and compiles. "Verified" means it was run
  against a real engine and the result is recorded. The two are never merged.
- One sample is not a statistical result, and the findings say so where it
  applies.

## Layout and conventions

Add experiments under `experiments/NN-short-name/`, using the next two-digit
number and a descriptive name. Each directory is a self-contained Maven project
with its own `pom.xml`, source, scripts and documentation. There is no root
aggregator POM and experiments must never depend on one another. Run Maven from
the chosen experiment directory, or use `mvn -f experiments/NN-short-name/pom.xml`.

Each experiment README must contain its thesis, design, how to build and run it,
and findings (or a link to findings that clearly marks unverified behaviour).
Keep configuration public-safe: localhost defaults, environment variables for
connections and authentication, and no credentials in source or recorded output.
Do not present hypotheses as measured results.

Experiments use Camunda 8 and its Java client (experiment 01: Java 21 and
`io.camunda:camunda-client-java:8.10.7`). Engine compatibility and behaviour must
be verified for the distribution you use. The client can connect to a remote
engine, so a laptop with a local engine is enough to reproduce everything.

## Hosting

A small experiment can be served under a path of one host, each as its own
process on its own loopback port with its own `LAB_BASE_PATH`. Experiment 01
uses `/lifecycle` on port 8088. Budget roughly two small JVMs per host;
prefer static or very light experiments.

Repository code and documentation are licensed under [Apache-2.0](LICENSE).
