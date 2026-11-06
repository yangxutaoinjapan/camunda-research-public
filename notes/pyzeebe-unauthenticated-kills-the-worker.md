# A rejected token can terminate a pyzeebe worker

## Problem

Token expiry is not the only reason a gateway can reject a bearer token. An
issuer migration can invalidate a cached token while its lifetime still looks
healthy to the client. Refreshing only when the local expiry timer approaches
zero does not handle that case.

In pyzeebe 4.5.0, a gateway response with gRPC `UNAUTHENTICATED` took an
unexpected path: the error mapper produced `UnknownGrpcStatusCodeError`, which
escaped the job poller's retry handling and terminated the worker. A normal
authentication rejection therefore became a process failure, leaving active
jobs dependent on their lease expiry and later reactivation.

The workaround described here patches a private library method. It is a
version-bound compatibility measure, with a deliberately small scope: discard
cached credentials after a gateway rejection and use an error category the
poller already retries. It does not authorize a rejected request.

## What we observed

The recorded worker implementation identifies the status mapping above and the
resulting exit. An issuer migration made the issue operationally relevant: a
still-cached token could name the previous issuer, or a gateway could still be
loading its new configuration. Either way, retrying the same cached credential
was insufficient.

The prepared fix invalidated the caches used by the custom OAuth channel and
mapped `UNAUTHENTICATED` to `ZeebeGatewayUnavailableError`. The poller already
handled that error with a five-second retry delay. Both worker environments
were recorded as running the fix before the issuer change. Subsequent readback
proved that a newly minted worker token authenticated to the engine; it did not
constitute an exhaustive worker fault-injection test.

A separate identity outage exposed another path: a token fetch raised a
connection exception inside the interceptor and the worker exited. The source
also added bounded token-fetch retries and converted exhausted refresh attempts
into gRPC `UNAVAILABLE`. Invalidating a rejected token and surviving an
unreachable token service are related but distinct mechanisms.

## What works

Keep invalidation attached to the cache actually consulted when constructing
RPC metadata. Clearing an unrelated variable, or creating a second token helper,
will not replace the credential carried by the next poll. Use asynchronous
fetching or move blocking HTTP work off the worker's event loop.

The following standalone demonstration requires an environment with pyzeebe
4.5.0 and its gRPC dependency installed. Save it as `refresh_demo.py` and run
`python refresh_demo.py`. It uses synthetic errors and a fake credential supplier,
so it needs no endpoint or credentials. The fake value never authenticates an
RPC. Replace `Cache.get()` with the real channel's token supplier for integration;
register every cache that channel uses before starting the worker.

```python
import grpc
from pyzeebe.errors import ZeebeGatewayUnavailableError
from pyzeebe.grpc_internals.zeebe_adapter_base import ZeebeAdapterBase


class Cache:
    def __init__(self, supplier):
        self.supplier = supplier
        self.value = None

    def get(self):
        if self.value is None:
            self.value = self.supplier()
        return self.value

    def forget(self):
        self.value = None


def install_refresh(caches):
    name = "_create_pyzeebe_error_from_grpc_error"
    previous = getattr(ZeebeAdapterBase, name)
    # Repeated installation reuses the original registry.
    if hasattr(previous, "refresh_caches"):
        previous.refresh_caches.extend(caches)
        return
    registry = list(caches)

    def convert(self, error):
        if error.code() == grpc.StatusCode.UNAUTHENTICATED:
            for cache in registry:
                cache.forget()
            return ZeebeGatewayUnavailableError(error)
        return previous(self, error)

    convert.refresh_caches = registry
    setattr(ZeebeAdapterBase, name, convert)


class Rejected(grpc.RpcError):
    def code(self):
        return grpc.StatusCode.UNAUTHENTICATED

    def details(self):
        return "synthetic rejection"


if __name__ == "__main__":
    fetches = []

    def supply():
        fetches.append(True)
        return object()

    cache = Cache(supply)
    first = cache.get()
    assert cache.get() is first
    install_refresh([cache])
    mapper = ZeebeAdapterBase._create_pyzeebe_error_from_grpc_error
    mapped = mapper(None, Rejected())
    assert isinstance(mapped, ZeebeGatewayUnavailableError)
    assert cache.get() is not first
    assert len(fetches) == 2
    print("Rejected credential forgotten; retry category selected.")
```

The demonstration establishes the local control flow, not a connected worker.
The real channel must acquire a fresh token on the next attempt and add it to
all relevant RPC shapes. In the reviewed implementation, server-streaming job
activation needed its own interceptor; successful unary calls did not prove
that activation carried metadata. Separate interceptor classes avoided a gRPC
registration branch that selected only the first matching interface.

Install the patch once during startup, before polling begins. Delegate every
other status to the original mapper. Never turn authentication off, print a
credential, or treat rejection as successful completion. Repeated rejections
should remain visible through bounded diagnostics and alerts: wrong audience,
wrong issuer, or missing permissions will not be repaired by endless refreshes.
Also preserve execution receipts, because surviving a poll error does not
make external side effects safe to repeat after job timeout.

## What we did not verify

- We did not execute this rewritten demonstration against installed dependencies
  here, or run it against a live gateway. Its assertions are local checks only.
- We did not prove compatibility with other pyzeebe releases or every RPC path;
  the patch depends on a private method and a particular retry category.
- We did not establish resilience to a repeated full identity outage, concurrent
  refresh races, or permanently invalid client configuration.
