# Move Camunda UI and API traffic away from an old entry point

## Problem

Moving Camunda workloads does not automatically move their traffic. Browser
links, redirects, identity callbacks, worker token URLs, and gateway routes can
continue to depend on an earlier entry point. A successful UI request may still
cross the old location before reaching the current application.

The harder case is an old application address that also serves Keycloak realm
and browser resource paths. Redirecting everything immediately can break token
acquisition and sign-in even if the new Camunda UI is healthy. Separating the
identity service's public address from the application's address makes the
traffic migration easier to stage.

This note describes the ordering and readbacks from one Camunda 8.10 move. It
separates the intended sequence from the actual sequence: the identity address
was established first, application redirects were enabled next, and the realm
issuer changed afterward while identity paths remained available.

## What we observed

A dedicated public identity route was introduced with `/realms` and `/resources`
as its intended path families. Discovery succeeded there before the issuer
changed. The new discovery response advertised a token endpoint using the new
address, but its issuer still used the old address because the realm
`frontendUrl` fixed that value.

The old application entry point then gained edge redirect rules. Readback
confirmed that `/realms` and `/resources` remained untouched, while other paths
returned HTTP 302 toward the destination applications. Path and query contents
were preserved. Some application prefixes required their own destination rules,
so the rules were not treated as one undifferentiated root redirect.

The origin route at the old entry point was trimmed to the identity path
families. Edge rules were read back, and the GitOps controller reported the
intended source revision synchronized and healthy. These were separate checks:
controller health did not stand in for external redirect behavior.

The subsequent issuer change updated the live realm and Camunda consumers.
Both the old and new identity routes then advertised the new issuer. A fresh
worker token carried that issuer, and an authenticated engine topology request
succeeded. Some clients still fetched tokens through the old identity route.
Those remaining calls did not mean that their UI or API traffic still needed
to pass through the old application entry point.

## What works

Start with a route inventory, not a workload inventory alone. Separate UI
navigation, API access, authorization redirects, logout, token acquisition,
and signing-key discovery. Identify which public paths belong to the identity
service and which should move to an application destination. Also inventory
links that generate future traffic, such as approval notices and bookmarks.

Give Keycloak its own public address before retiring the shared entry point.
Read discovery there and inspect both issuer and token endpoint. Check browser
resource delivery as well as machine token acquisition. Decide deliberately
which identity paths are public; a working discovery document does not require
opening an administration console. Do not infer issuer migration from the fact
that the new route answers.

Prepare the issuer change as a separate identity operation. Keep application
callback registrations aligned with the application destinations. Update realm
state and Camunda issuer, authorization, and logout configuration together in
a planned window, with the affected Camunda components restarted. In the
observed run the realm attribute changed without a Keycloak restart. Leave
legacy token paths available until remaining callers have moved.

At the edge, exempt the identity path families before applying broad redirects.
Use path boundaries deliberately so similarly named application paths do not
accidentally inherit the exception. Preserve the path and query when redirecting
to the new application location, and check any special prefix mappings before
a catch-all rule. A temporary 302 gives a visible migration response without
claiming a permanent client caching policy.

Keep readback small and explicit after each mutation. After identity routing,
read discovery and fetch needed resources. After edge rules, compare Location
headers for a root request, a nested application path, a query-bearing request,
and identity requests. After origin cleanup, inspect actual routes and source
revision. After issuer change, inspect the issuer claim without exposing the
token and make a real authenticated request.

Treat API clients separately from browser navigation. A redirect response is
an observed routing result; it does not prove that a client follows it safely
with its method, request body, and credentials. Move API base addresses
explicitly where possible. Finish by repointing remaining token callers and
removing legacy callbacks only after evidence shows they are no longer needed.
Keep an inventory of temporary exceptions so a compatibility route does not
silently become permanent architecture.

## What we did not verify

- We did not demonstrate that every API client follows a 302 correctly,
  especially for requests carrying bodies or authentication metadata.
- We did not prove that every old identity caller and legacy callback had been
  retired; the recorded final state deliberately retained compatibility paths.
- We did not measure latency improvement, uninterrupted sessions, or a complete
  reverse traffic migration. Reachability checks establish none of those.
