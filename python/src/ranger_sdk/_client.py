"""Construction shared by every client (Admin, KMS, PDP; sync and async)."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Sequence, Tuple, Union

import httpx

from ranger_sdk._flow import AsyncRunner, SyncRunner
from ranger_sdk._transport import AsyncTransport, RetryPolicy, SyncTransport, Timeouts, TransportConfig
from ranger_sdk._transport.core import Hook, RequestInterceptor, Verify
from ranger_sdk.auth import AuthProvider, NoAuth


def make_config(
    server: str,
    urls: Union[str, Sequence[str]],
    *,
    auth: Optional[AuthProvider] = None,
    verify: Verify = True,
    cert: Optional[Union[str, Tuple[str, str]]] = None,
    connect_timeout: float = 5.0,
    read_timeout: float = 30.0,
    retry: Optional[RetryPolicy] = None,
    cooldown: float = 30.0,
    headers: Optional[Dict[str, str]] = None,
    params: Optional[Dict[str, str]] = None,
    hooks: Optional[List[Hook]] = None,
    interceptors: Optional[List[RequestInterceptor]] = None,
    verify_hostname: bool = True,
    trust_env: bool = False,
) -> TransportConfig:
    return TransportConfig(
        urls=[urls] if isinstance(urls, str) else list(urls),
        server=server,
        auth=auth or NoAuth(),
        verify=verify,
        cert=cert,
        timeouts=Timeouts(connect=connect_timeout, read=read_timeout),
        retry=retry or RetryPolicy(),
        cooldown=cooldown,
        headers=dict(headers or {}),
        params=dict(params or {}),
        hooks=list(hooks or []),
        interceptors=list(interceptors or []),
        verify_hostname=verify_hostname,
        trust_env=trust_env,
    )


class _SyncBase:
    _server = "admin"

    def __init__(self, urls: Union[str, Sequence[str]], *, http_client: Optional[httpx.Client] = None,
                 **options: Any) -> None:
        self.config = make_config(self._server, urls, **options)
        self._transport = SyncTransport(self.config, http_client)
        self._runner = SyncRunner(self._transport)

    def close(self) -> None:
        self._transport.close()

    def __enter__(self) -> Any:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"<{type(self).__name__} urls={self.config.urls} auth={self.config.auth!r}>"


class _AsyncBase:
    _server = "admin"

    def __init__(self, urls: Union[str, Sequence[str]], *, http_client: Optional[httpx.AsyncClient] = None,
                 **options: Any) -> None:
        self.config = make_config(self._server, urls, **options)
        self._transport = AsyncTransport(self.config, http_client)
        self._runner = AsyncRunner(self._transport)

    async def close(self) -> None:
        await self._transport.close()

    async def __aenter__(self) -> Any:
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.close()

    def __repr__(self) -> str:
        return f"<{type(self).__name__} urls={self.config.urls} auth={self.config.auth!r}>"
