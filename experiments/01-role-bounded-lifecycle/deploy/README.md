# Run on a small host (Ubuntu, 1 GiB)

The Camunda engine and its search storage run remotely. This node runs one Java
21 JVM: three job workers, the JDK HTTP server, and the offline vanilla JS page.
This unit limits the lab to 400 MiB,
with no swap allowance. The heap is 192 MiB. These are budgets, not measured RSS
guarantees; leave room for Ubuntu and any proxy. An OOM kill is
possible if native memory exceeds the budget. Deploy the message-started role to the
remote engine separately using the `deploy` command and authorized credentials.
`serve` does not deploy; its page can open and close roles and control the schedule.

## Install

On a build machine with dependencies cached, build with Java 21 and `mvn -q -o
package`. Do not package over a jar being used by a running experiment; use a
separate checkout/build location. Transfer `target/role-bounded-lifecycle.jar`
and the entire `target/lib/` directory, plus these deployment files, to the
operator's staging directory on the node. No shade build is needed: the existing
jar manifest has `Main-Class: lab.Main` and a `Class-Path` of `lib/<dependency>.jar`.
Those paths resolve relative to the application jar, so the supplied unit's
`-jar` command works with `/opt/lifecycle-lab/lib/` next to it. The jar alone is
not a complete distribution. Keep all libraries from the same build.

On the node (operator commands; package installation needs repository access):

```sh
sudo apt-get update
sudo apt-get install --no-install-recommends openjdk-21-jre-headless
sudo useradd --system --user-group --home-dir /nonexistent --shell /usr/sbin/nologin lifecycle-lab
sudo install -d -o root -g root -m 755 /opt/lifecycle-lab
sudo install -m 644 role-bounded-lifecycle.jar /opt/lifecycle-lab/
sudo cp -r lib /opt/lifecycle-lab/
sudo chown -R root:root /opt/lifecycle-lab
sudo chmod -R go-w /opt/lifecycle-lab
sudo install -d -o root -g root -m 700 /etc/lifecycle-lab
sudo install -o root -g root -m 600 deploy/env.example /etc/lifecycle-lab/env
# Edit /etc/lifecycle-lab/env from the operator's secret store before starting.
sudo install -m 644 deploy/role-bounded-lifecycle.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now role-bounded-lifecycle.service
sudo systemctl status role-bounded-lifecycle.service
sudo journalctl -u role-bounded-lifecycle.service -n 50
```

Replace the engine placeholder. Remove unused empty auth variables and set the
standard client auth values for your distribution. Set `LAB_HTTP_TOKEN` to
a strong secret, or remove that variable entirely for tokenless loopback use.
An explicitly empty token is refused. Never put credentials in the repository,
command lines, URLs, browser query strings, or logs. The app does not log tokens
or request bodies. The root-owned environment file is read by systemd.

Defaults: `LAB_HTTP_BIND=127.0.0.1`, `LAB_HTTP_PORT=8088`,
`LAB_SERVE_WORKERS=true`. The supplied env file sets `LAB_BASE_PATH=/lifecycle`;
when unset or empty it serves at `/`. A nonempty base must start with `/` and
use only `a-z`, `0-9`, `-`, `_`, and `/`. Trailing slashes are removed (`/`
means the root); other values fail at startup. The bare base redirects with
308 to its trailing-slash URL. Page, API and any static asset paths are under
the base; requests outside it return 404. Set `LAB_SERVE_WORKERS=false` only
when workers run elsewhere.
Without a token the server refuses non-loopback binding. The app directory is
read-only to the service; no local data files are required.

## Authenticated access

**Do not publish this page without authentication.** Keep the app bound to
loopback. Use public hostname `lab.example.org`, path `/lifecycle`, with
an authenticating reverse proxy in front of origin `127.0.0.1:8088`.
Require operator login for `/lifecycle` and everything under `/lifecycle/`,
including `/lifecycle/api/`. Preserve the prefix: the proxy must not strip it,
and the origin must use `LAB_BASE_PATH=/lifecycle`. Configure the proxy to
overwrite `X-Lab-Token` with the shared
secret (including for API GETs), suppress that header in access/debug logs, and
restrict the upstream port in the firewall. Configure proxy request timeouts for
engine operations and bursts (up to 20 publishes, each with engine key sampling).
Do not add automatic retries to publishing POSTs: failure may be partial.
The shared token is defense in depth, not a replacement for proxy user auth.
Alternatively a trusted loopback session can enter the token in the page's API
access field; it stays in page memory and is lost on reload. No CORS is enabled.

## Memory and health

```sh
sudo systemctl show role-bounded-lifecycle.service -p MemoryCurrent -p MemoryPeak -p MemoryMax -p TasksCurrent
sudo systemctl show role-bounded-lifecycle.service -p MainPID
sudo journalctl -u role-bounded-lifecycle.service --since today
```

For JVM diagnostics, `jcmd` requires `openjdk-21-jdk-headless` (the JRE package
alone does not provide it). Run as the service user against the reported PID:
`sudo -u lifecycle-lab jcmd PID GC.heap_info`. With `PrivateTmp=true`, attachment
may require entering the service mount namespace first:
`sudo nsenter -t PID -m -- runuser -u lifecycle-lab -- jcmd PID GC.heap_info`.
For a controlled diagnostic restart, enable `-XX:NativeMemoryTracking=summary`
in a systemd override, then use `jcmd PID VM.native_memory summary` in the same
namespace. NMT has overhead and is disabled by default. Compare JVM figures with
cgroup MemoryCurrent and OS usage during realistic work and drain loads.
The page displays engine failures; an HTTP listener alone is not engine health.
Counts can lag due to search export. No live memory measurements are supplied.

## Rollback

Before an upgrade, preserve the previous jar **and matching lib directory**
outside `/opt/lifecycle-lab`, plus a protected copy of the unit and environment.
Stop the service, replace jar and lib together, then start it. For rollback,
stop it again, restore that matched pair and any changed unit/environment,
run `systemctl daemon-reload`, and restart. Check status, logs and the page.
Do not replace files while the JVM is running. Stopping workers does not cancel
engine instances; outstanding jobs can wait until workers return and their job
leases expire. Application rollback does not undo process deployments or messages.

## Engine authentication and tenant permissions (client 8.10.7)

Set `CAMUNDA_REST_ADDRESS=https://ENGINE_HOST/orchestration`: keep the context
path and omit `/v2`; the client appends endpoint paths (using URIBuilder.appendPath).
Set `GATEWAY_CLIENT_ID` and `GATEWAY_CLIENT_SECRET` to the gateway
**service credential for the engine host**. This is separate from the page's user
login and `LAB_HTTP_TOKEN`. Set both to nonempty values, or remove both variables;
a missing partner fails startup and names the missing variable without its value.
Empty values also fail. Remove unused empty auth entries from env.example.

For OIDC client credentials set `CAMUNDA_CLIENT_ID`, `CAMUNDA_CLIENT_SECRET`,
`CAMUNDA_AUTHORIZATION_SERVER_URL` (the token endpoint), and
`CAMUNDA_TOKEN_AUDIENCE` as required by your identity provider. The normal OAuth
builder reads these environment variables and retains its token cache, refresh
and retry behavior. Selection matches the local 8.10.7 client: both OAuth ID and
secret present selects OAuth; otherwise both `CAMUNDA_BASIC_AUTH_USERNAME` and
`CAMUNDA_BASIC_AUTH_PASSWORD` present selects basic; otherwise no engine auth.
Presence includes empty strings, so remove unused entries rather than leaving them
empty. OAuth takes precedence over basic. The wrapper delegates auth and retry
decisions, then adds both Access headers to every engine REST request, including
retries. The isolated clock management helper also adds the Access pair if used.

**Limit: an Access-protected OAuth token endpoint is not supported by this
wrapper.** Inspection with javap of the actual target/lib 8.10.7 jar shows
OAuthCredentialsProvider opens its own HttpURLConnection and sets only
Content-Type, Accept and User-Agent. OAuthCredentialsProviderBuilder has no
request-header customizer or injectable HTTP client. CamundaClientBuilder's
withChainHandlers applies to the separate engine Apache HTTP client, not token
fetches (nor OIDC discovery). CredentialsApplier only decorates engine requests.
Use a token endpoint reachable without this Access service token, or arrange a
trusted proxy that adds the headers for token/discovery requests. If the token
endpoint requires Access headers, this version of the lab cannot complete OIDC
login; configuring the pair does not solve that boundary. No custom token client
or process-wide URL interception is installed.

The lab explicitly uses REST, disables worker streaming and client-side load
balancing, and sets the unused gRPC address to http://127.0.0.1:26500. The 8.10.7
builder defaults to REST preference true and gRPC http://0.0.0.0:26500, but applies
environment overrides during build. To prevent overrides from enabling gRPC,
startup rejects CAMUNDA_PREFER_REST other than true, streaming/load-balancing set
to true, and a different CAMUNDA_GRPC_ADDRESS. Leave these variables unset.
The library still constructs a lazy gRPC channel; lab commands and job polling
use REST, and no topology load balancing or job streams are enabled.

Grant the client identity access in the lab's tenant only. Required capabilities:
RESOURCE CREATE (deployment), PROCESS_DEFINITION read, PROCESS_INSTANCE read
(process-instance search including parentProcessInstanceKey filter and
parent/root fields, variables and element search), MESSAGE CREATE (publication), and job
activation/completion for `lab.work.a`, `lab.work.b`, `lab.work.c`. In the local
8.10.7 authorization enums there is no standalone PROCESS_INSTANCE or JOB resource:
use PROCESS_DEFINITION permissions READ_PROCESS_DEFINITION,
READ_PROCESS_INSTANCE and UPDATE_PROCESS_INSTANCE for the lab definitions
`worker-role-monthly`, `worker-role-minute`, and `lab-child-a|b|c` as applicable.
Allow creation of child instances via call activities (CREATE_PROCESS_INSTANCE on
the child definitions, subject to engine enforcement). Job access is process-scoped
in this model: only the children contain jobs, exclusively `lab.work.a|b|c`.
Do not grant unrelated job types.
Expression sampling/preflight needs EXPRESSION EVALUATE. The `experiment` and
`cleanup` commands additionally need PROCESS_DEFINITION CANCEL_PROCESS_INSTANCE
and RESOURCE DELETE (delete only the minute role definition to remove its timer, never the shared
child definitions). Cancellation of minute roles must cascade to active children.
The exact endpoint enforcement and supported resource-ID scoping must be verified
against the deployed engine; enum inspection alone cannot prove authorization
behavior. No tenant was available here. Do not use an admin identity merely to
avoid configuring these grants.

Do not enable HTTP wire/header debug logging. Credential-application errors drop
upstream messages and causes; CLI/HTTP error responses omit exception details.
No header values are intentionally printed. Inspection and javac checks were
offline; no engine, identity provider, Access policy, permissions, actual request
routing, token renewal or live job behavior could be verified.

## Persistent schedule

The unit declares `StateDirectory=lifecycle-lab` and `StateDirectoryMode=0700`.
Systemd creates `/var/lib/lifecycle-lab` and supplies `STATE_DIRECTORY` to the JVM;
do not set another value in the environment file for this unit. The application
atomically writes `schedule.json` there. Outside systemd it defaults to
`/tmp/schedule.json`, or use a writable `STATE_DIRECTORY` for local persistence.
Back up this file with the service stopped. An invalid file fails startup.
Default configuration is disabled, opens day 1 00:00 UTC, closes day 1 00:00 UTC
next month. Keep this state directory across jar rollback. An older timer-based
role deployment must be removed before operating the message-started revision;
stop serve and inspect/cancel obsolete definitions and instances as appropriate.

Open **Role lifecycle**, authenticate with the existing token, configure Opens
and Closes, and Save. The native Schedule on/off checkbox affects new roles
only. Manual Open and Close work while the schedule is off. The scheduler lives
in this JVM and needs it running; restart catches up only the current period.
Existing engine timers and child drain continue while the service is stopped.
Monitor the effective schedule state and keep host/engine UTC clocks synchronized.
See the [experiment README](../README.md) for exact JSON and rules. The role and
three children deploy together. No engine verification or remote deployment was
performed for this revision.
