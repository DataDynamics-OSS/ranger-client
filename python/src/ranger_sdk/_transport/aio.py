from __future__ import annotations

import asyncio
from typing import Any

import httpx

from ranger_sdk._transport.core import Endpoints, Request, TransportConfig, _Call, _Retry, client_kwargs, decode


class AsyncTransport:
    """asyncio transport sharing the request life cycle with :class:`SyncTransport`."""

    def __init__(self, cfg: TransportConfig, client: "httpx.AsyncClient | None" = None) -> None:
        self.cfg = cfg
        self.endpoints = Endpoints(cfg.urls, cfg.cooldown)
        self._client = client or httpx.AsyncClient(**client_kwargs(cfg))

    async def send(self, req: Request) -> httpx.Response:
        call = _Call(self.cfg, self.endpoints, req, self._client.cookies)
        while True:
            request = call.build(self._client)
            try:
                resp = await self._client.send(request)
                await resp.aread()
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
                await asyncio.sleep(outcome.delay)

    async def call(self, req: Request) -> Any:
        return decode(await self.send(req))

    async def close(self) -> None:
        await self._client.aclose()
