from __future__ import annotations

import time
from typing import Any

import httpx

from ranger_sdk._transport.core import Endpoints, Request, TransportConfig, _Call, _Retry, client_kwargs, decode


class SyncTransport:
    """Thread-safe blocking transport; one instance per application is enough."""

    def __init__(self, cfg: TransportConfig, client: "httpx.Client | None" = None) -> None:
        self.cfg = cfg
        self.endpoints = Endpoints(cfg.urls, cfg.cooldown)
        self._client = client or httpx.Client(**client_kwargs(cfg))

    def send(self, req: Request) -> httpx.Response:
        call = _Call(self.cfg, self.endpoints, req, self._client.cookies)
        while True:
            request = call.build(self._client)
            try:
                resp = self._client.send(request)
                resp.read()
            except httpx.TransportError as exc:
                outcome: Any = call.on_exception(exc)
            except BaseException as exc:  # cancellation, decode bugs: still close the attempt
                call.abort(exc)
                raise
            else:
                outcome = call.on_response(resp)
                if not isinstance(outcome, _Retry):
                    return outcome
            if outcome.delay:
                time.sleep(outcome.delay)

    def call(self, req: Request) -> Any:
        return decode(self.send(req))

    def close(self) -> None:
        self._client.close()
