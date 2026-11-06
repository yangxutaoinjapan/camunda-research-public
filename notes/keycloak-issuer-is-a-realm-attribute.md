# Keycloak issuer is a realm attribute

## Problem

Moving an identity service to a dedicated public address looks like a routing
change until tokens enter the picture. A client can reach Keycloak through a
new address and still receive a token whose `iss` names the old address. A
receiver configured for the new issuer then rejects a perfectly reachable token
service. Reachability, token acquisition, and issuer acceptance are separate
checks.

This note describes one Camunda 8.10 migration with Keycloak. The important
configuration was the realm's `frontendUrl` attribute. In the configuration we
observed, that attribute fixed the issuer independently of the address used by
the caller. This is a configuration-specific observation, not a claim that all
Keycloak installations derive every URL in the same way.

The migration also involved application callbacks, browser authorization links,
logout links, and cached tokens. Treating all of these as one interchangeable
URL would have made both the cutover and its rollback harder to reason about.

## What we observed

A second public route to the same realm was introduced before changing the
issuer. Discovery through that route succeeded. Its token endpoint already
used the new address, while the discovery document's issuer still named the old
address. The realm attribute explained the difference: changing where a request
arrived had not changed the realm's advertised identity.

The completed cutover changed the live realm `frontendUrl` and the corresponding
source configuration. The Keycloak resource's hostname setting deliberately
remained unchanged. No Keycloak restart was needed for this observed change.
Both public routes subsequently advertised the new issuer. A newly acquired
worker token contained it, and an authenticated Zeebe topology request in the
intended tenant succeeded.

The Camunda side updated its public issuer, authorization, and logout settings.
Orchestration, Optimize, Identity, and Web Modeler were synchronized and
restarted. Browser authorization from Orchestration pointed to the new identity
address. This is the set restarted in this migration; it is not evidence that
every Camunda distribution has exactly the same restart boundary.

Existing client token URLs could remain on the old route. They reached the same
realm and received tokens bearing the new issuer. Application callback URLs
continued to name the applications, rather than the identity service. Legacy
redirect URI entries remained temporarily available during the overlap.

## What works

Begin with an inventory organized by purpose. Record the realm issuer, discovery
address, token endpoint, browser authorization endpoint, logout endpoint, and
each application's registered redirect URI. Include worker environments and
read-only verification tools. A successful token request alone does not prove
that a receiver accepts that token.

Establish the dedicated identity route first. Read back discovery and compare
its `issuer` and `token_endpoint` separately. Confirm that browser resources
needed for sign-in can load. Restrict public routing to the intended identity
paths rather than assuming that exposing discovery requires exposing the
administration console.

Before the issuer window, prepare Camunda configuration and token-rejection
handling in workers. Old cached tokens can become unacceptable before their
expiry. Schedule a restart window for the components that consume the issuer
settings, and expect browser sessions to need renewal. Update the live realm
through its administration API and read it back. Editing a realm import file
alone did not update an already existing realm in this setup.

Keep callbacks registered for the actual application addresses. Retain necessary
legacy callbacks during overlap, then remove them after consumer checks. Move
token acquisition URLs separately once the issuer change is proved. This
separation is safe only while both routes still serve the same realm; deleting
the old identity route would remove that allowance.

Verification should compare source intent with the live realm, read discovery
through both routes, inspect only the new token's issuer without logging the
token, and make a real authenticated engine request. Also check the browser's
authorization destination. Record results independently of the workflow that
reports the change, because that workflow may itself lose connectivity during
the restart.

Prepare rollback as the reverse configuration change: restore the previous
realm attribute and Camunda issuer, authorization, and logout settings, then
restart the affected Camunda consumers. Keep the earlier route and required
callbacks available until the rollback window closes. Read back the restored
issuer and acquire another token; simply undoing a source edit is insufficient.

## What we did not verify

- We did not rehearse the full reverse migration. The rollback sequence above
  is a prepared procedure, not a measured recovery time.
- We did not establish uninterrupted sessions, acceptance of both issuers at
  once, or zero downtime. The observed migration used a restart window.
- We did not prove that other Keycloak hostname modes or other Camunda versions
  use the same URL precedence and restart boundaries.
