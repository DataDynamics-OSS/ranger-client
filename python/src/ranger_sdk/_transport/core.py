"""Transport logic shared by the sync and async clients.

The request life cycle (node selection, auth, retry decision, error mapping)
lives in :class:`_Call`; :mod:`.sync` and :mod:`.aio` only drive it with their
own I/O and sleep primitives, so both clients behave identically.
"""

from __future__ import annotations

import json
import logging
import os
import random
import re
import ssl
import threading
import time
import uuid
import warnings
from dataclasses import dataclass, field
from email.utils import parsedate_to_datetime
from typing import Any, Callable, Dict, List, Mapping, Optional, Protocol, Sequence, Tuple, Union
from urllib.parse import quote, urlsplit

import httpx

from ranger_sdk import errors as E
from ranger_sdk.auth import AuthProvider, NoAuth

LOG = logging.getLogger("ranger_sdk")

SESSION_COOKIE = "RANGERADMINSESSIONID"
REQUEST_ID_HEADER = "X-Request-Id"
USER_AGENT = "ranger-sdk-python/0.2.0"

IDEMPOTENT = {"GET", "HEAD", "PUT", "DELETE", "OPTIONS"}
_MASKED_HEADERS = {"authorization", "cookie", "set-cookie", "proxy-authorization"}
_SECRET_FIELDS = re.compile(r'("(?:password|passwd|secret|token|keytab|material)"\s*:\s*)"[^"]*"', re.I)
_DUPLICATE = re.compile(r"already exists|duplicate|same name", re.I)

Verify = Union[bool, str, ssl.SSLContext]


def mask_headers(headers: Mapping[str, str]) -> Dict[str, str]:
    return {k: ("***" if k.lower() in _MASKED_HEADERS else v) for k, v in headers.items()}


def mask_body(text: Optional[str]) -> Optional[str]:
    if not text:
        return text
    return _SECRET_FIELDS.sub(r'\1"***"', text)


def seg(value: Any) -> str:
    """Encode one path segment (names may contain spaces or slashes)."""
    return quote(str(value), safe="")


@dataclass
class RetryPolicy:
    """Exponential backoff with full jitter, honouring ``Retry-After``."""

    max_attempts: int = 3
    base_delay: float = 0.2
    max_delay: float = 5.0
    jitter: bool = True

    @classmethod
    def exponential(cls, max_attempts: int = 3, base_delay: float = 0.2, max_delay: float = 5.0) -> "RetryPolicy":
        return cls(max_attempts=max_attempts, base_delay=base_delay, max_delay=max_delay)

    @classmethod
    def none(cls) -> "RetryPolicy":
        return cls(max_attempts=1)

    def delay(self, attempt: int) -> float:
        d = min(self.max_delay, self.base_delay * (2 ** (attempt - 1)))
        return random.uniform(0, d) if self.jitter else d


@dataclass
class Timeouts:
    connect: float = 5.0
    read: float = 30.0

    def httpx(self) -> httpx.Timeout:
        return httpx.Timeout(self.read, connect=self.connect)


Hook = Callable[[str, Dict[str, Any]], None]


class Attempt(Protocol):
    """Outcome of one HTTP attempt; exactly one of ``on_response`` / ``on_error`` is called.

    Two optional methods receive the bodies (they may hold secrets such as KMS key material, so do not log
    them blindly): ``on_request_body(content_type, body)`` before the attempt is sent, only when it has a body,
    and ``on_response_body(status, body)`` right before ``on_response``.
    """

    def on_response(self, status: int) -> None: ...

    def on_error(self, error: BaseException) -> None: ...


class RequestInterceptor(Protocol):
    """Wraps each HTTP attempt (retries and failovers are separate attempts).

    Unlike hooks it can add headers before the attempt is sent (e.g. W3C ``traceparent``), and it gets one
    callback when the attempt ends. Exceptions raised by an interceptor are logged and ignored.
    """

    def begin(self, server: str, method: str, url: str, attempt: int, request_id: str,
              set_header: Callable[[str, str], None]) -> Attempt: ...


@dataclass
class TransportConfig:
    urls: Sequence[str]
    server: str = "admin"
    auth: AuthProvider = field(default_factory=NoAuth)
    verify: Verify = True
    cert: Optional[Union[str, Tuple[str, str]]] = None
    timeouts: Timeouts = field(default_factory=Timeouts)
    retry: RetryPolicy = field(default_factory=RetryPolicy)
    cooldown: float = 30.0
    headers: Dict[str, str] = field(default_factory=dict)
    params: Dict[str, str] = field(default_factory=dict)
    hooks: List[Hook] = field(default_factory=list)
    interceptors: List[RequestInterceptor] = field(default_factory=list)
    retry_non_idempotent: bool = False
    #: hostname verification can only be turned off explicitly (and is logged)
    verify_hostname: bool = True
    #: honour HTTP(S)_PROXY / SSL_CERT_FILE env vars; off by default for closed networks
    trust_env: bool = False

    def __post_init__(self) -> None:
        if isinstance(self.urls, str):
            self.urls = [self.urls]
        self.urls = [u.rstrip("/") for u in self.urls]
        if not self.urls:
            raise ValueError("at least one server URL is required")
        if self.server not in self.auth.servers:
            raise ValueError(f"{self.auth.describe()} cannot be used against the {self.server} server")
        if self.verify is False or not self.verify_hostname:
            what = "certificate" if self.verify is False else "hostname"
            msg = "TLS %s verification is disabled for %s; use only for testing" % (what, ", ".join(self.urls))
            warnings.warn(msg, stacklevel=3)
            LOG.warning(msg)


class Endpoints:
    """Round-robin over the configured URLs, skipping nodes in cooldown."""

    def __init__(self, urls: Sequence[str], cooldown: float) -> None:
        self._urls = list(urls)
        self._cooldown = cooldown
        self._down: Dict[str, float] = {}
        self._next = 0
        self._lock = threading.Lock()

    def pick(self, exclude: Sequence[str] = ()) -> str:
        now = time.monotonic()
        with self._lock:
            n = len(self._urls)
            candidates = []
            for i in range(n):
                url = self._urls[(self._next + i) % n]
                if url in exclude:
                    continue
                candidates.append(url)
            healthy = [u for u in candidates if self._down.get(u, 0) <= now]
            # every node is cooling down (or excluded): take the one that recovers first
            choice = healthy[0] if healthy else min(candidates or self._urls, key=lambda u: self._down.get(u, 0))
            self._next = (self._urls.index(choice) + 1) % n
            return choice

    def mark_down(self, url: str) -> None:
        with self._lock:
            self._down[url] = time.monotonic() + self._cooldown

    def mark_up(self, url: str) -> None:
        with self._lock:
            self._down.pop(url, None)


@dataclass
class Request:
    method: str
    path: str
    params: Optional[Dict[str, Any]] = None
    json: Any = None
    content: Optional[bytes] = None
    files: Optional[Dict[str, Any]] = None
    headers: Optional[Dict[str, str]] = None
    timeout: Optional[float] = None
    idempotent: Optional[bool] = None
    retry: Optional[RetryPolicy] = None


@dataclass
class _Retry:
    delay: float


class _Call:
    """State of one logical call across attempts."""

    def __init__(self, cfg: TransportConfig, endpoints: Endpoints, req: Request, cookies: httpx.Cookies) -> None:
        self.cfg = cfg
        self.endpoints = endpoints
        self.req = req
        self.cookies = cookies
        self.policy = req.retry or cfg.retry
        idem = req.idempotent if req.idempotent is not None else req.method.upper() in IDEMPOTENT
        self.retryable = idem or cfg.retry_non_idempotent
        self.request_id = (req.headers or {}).get(REQUEST_ID_HEADER) or uuid.uuid4().hex
        self.attempt = 0
        self.auth_refreshed = False
        self.failed: List[str] = []
        self.url = ""
        self.last_error: Optional[BaseException] = None
        self._attempts: List[Attempt] = []

    # -- request ---------------------------------------------------------
    def build(self, client: Union[httpx.Client, httpx.AsyncClient]) -> httpx.Request:
        self.attempt += 1
        self.url = self.endpoints.pick(self.failed)
        host = urlsplit(self.url).hostname or ""
        headers = {"Accept": "application/json", "User-Agent": USER_AGENT, REQUEST_ID_HEADER: self.request_id}
        headers.update(self.cfg.headers)
        auth = self.cfg.auth
        if not (auth.supports_session_reuse and self._has_session(host)):
            headers.update(auth.headers(host))
        if self.req.headers:
            headers.update(self.req.headers)
        params: Dict[str, Any] = dict(self.cfg.params)
        params.update(auth.query_params())
        if self.req.params:
            params.update({k: _param(v) for k, v in self.req.params.items() if v is not None})
        timeout = httpx.Timeout(self.req.timeout, connect=self.cfg.timeouts.connect) if self.req.timeout else None
        kw: Dict[str, Any] = {"headers": headers, "params": params}
        if timeout is not None:
            kw["timeout"] = timeout
        if self.req.json is not None:
            kw["content"] = json.dumps(self.req.json, separators=(",", ":")).encode()
            headers["Content-Type"] = "application/json"
        elif self.req.files is not None:
            kw["files"] = self.req.files
        elif self.req.content is not None:
            kw["content"] = self.req.content
        request = client.build_request(self.req.method, self.url + self.req.path, **kw)
        self._begin(request)
        self._emit("request", {"method": self.req.method, "url": str(request.url), "attempt": self.attempt,
                               "headers": mask_headers(request.headers), "request_id": self.request_id})
        if LOG.isEnabledFor(logging.DEBUG):
            LOG.debug("-> %s %s attempt=%d id=%s headers=%s", self.req.method, request.url, self.attempt,
                      self.request_id, mask_headers(request.headers))
        return request

    def _has_session(self, host: str) -> bool:
        for c in self.cookies.jar:
            if c.name != SESSION_COOKIE:
                continue
            d = (c.domain or "").lstrip(".")
            # http.cookiejar stores dot-less hosts as "<host>.local"
            if not d or d in (host, host + ".local") or host.endswith("." + d):
                return True
        return False

    def _begin(self, request: httpx.Request) -> None:
        self._attempts = []
        for interceptor in self.cfg.interceptors:
            try:
                self._attempts.append(interceptor.begin(
                    self.cfg.server, self.req.method, str(request.url), self.attempt, self.request_id,
                    request.headers.__setitem__))
            except Exception:  # interceptors must never break a call
                LOG.exception("ranger_sdk interceptor failed")
        if request.content:
            for a in self._attempts:
                hook = getattr(a, "on_request_body", None)
                if hook is not None:
                    try:
                        hook(request.headers.get("Content-Type"), request.content)
                    except Exception:
                        LOG.exception("ranger_sdk interceptor failed")

    def _end(self, status: Optional[int] = None, error: Optional[BaseException] = None,
             body: Optional[bytes] = None) -> None:
        attempts, self._attempts = self._attempts, []
        for a in attempts:
            hook = getattr(a, "on_response_body", None) if status is not None else None
            if hook is not None:
                try:
                    hook(status, body or b"")
                except Exception:
                    LOG.exception("ranger_sdk interceptor failed")
            try:
                if status is not None:
                    a.on_response(status)
                else:
                    a.on_error(error)  # type: ignore[arg-type]
            except Exception:
                LOG.exception("ranger_sdk interceptor failed")

    def abort(self, exc: BaseException) -> None:
        """The attempt ended without a response or a transport error (e.g. cancellation)."""
        self._end(error=exc)

    def _emit(self, event: str, info: Dict[str, Any]) -> None:
        for hook in self.cfg.hooks:
            try:
                hook(event, info)
            except Exception:  # hooks must never break a call
                LOG.exception("ranger_sdk hook failed")

    # -- outcome ---------------------------------------------------------
    def on_response(self, resp: httpx.Response) -> Union[_Retry, httpx.Response]:
        status = resp.status_code
        self._end(status=status, body=resp.content)
        self._emit("response", {"method": self.req.method, "url": str(resp.request.url), "status": status,
                                "attempt": self.attempt, "request_id": self.request_id})
        LOG.debug("<- %s %s status=%d id=%s", self.req.method, resp.request.url, status, self.request_id)
        if status < 400:
            self.endpoints.mark_up(self.url)
            return resp
        if status == 401 and not self.auth_refreshed:
            self.auth_refreshed = True
            had_session = any(c.name == SESSION_COOKIE for c in self.cookies.jar)
            self.cookies.clear()
            if self.cfg.auth.refresh() or had_session:
                self.attempt -= 1  # an auth refresh does not consume a retry
                return _Retry(0.0)
        if status in (429, 503) or status >= 500:
            mapped = self.error(resp)
            if isinstance(mapped, E.RangerNotFoundException):
                raise mapped  # e.g. KMS "key does not exist": an answer, not a server fault; no retry, node stays up
            if status >= 500:
                self.endpoints.mark_down(self.url)
                if len(self.cfg.urls) > 1:
                    self.failed.append(self.url)
            if self.retryable and self.attempt < self.policy.max_attempts:
                return _Retry(_retry_after(resp) or self.policy.delay(self.attempt))
        raise self.error(resp)

    def on_exception(self, exc: Exception) -> _Retry:
        self._end(error=exc)
        self.endpoints.mark_down(self.url)
        self.failed.append(self.url)
        self.last_error = exc
        self._emit("error", {"method": self.req.method, "url": self.url + self.req.path, "error": repr(exc),
                             "attempt": self.attempt, "request_id": self.request_id})
        LOG.warning("%s %s%s failed (attempt %d/%d): %s", self.req.method, self.url, self.req.path,
                    self.attempt, self.policy.max_attempts, exc)
        # a connect failure never reached the server, so it is always safe to retry
        safe = self.retryable or isinstance(exc, (httpx.ConnectError, httpx.ConnectTimeout))
        if safe and self.attempt < max(self.policy.max_attempts, 1):
            return _Retry(0.0 if len(self.cfg.urls) > 1 else self.policy.delay(self.attempt))
        raise E.RangerTransportException(
            f"Could not reach Ranger {self.cfg.server}: {exc}", method=self.req.method, path=self.req.path,
            server=self.url, request_id=self.request_id) from exc

    def error(self, resp: httpx.Response) -> E.RangerException:
        return map_error(resp, self.req.method, self.req.path, self.url, self.request_id)


def _param(v: Any) -> Any:
    if isinstance(v, bool):
        return "true" if v else "false"
    return v


def _retry_after(resp: httpx.Response) -> Optional[float]:
    value = resp.headers.get("Retry-After")
    if not value:
        return None
    try:
        return max(0.0, float(value))
    except ValueError:
        try:
            return max(0.0, parsedate_to_datetime(value).timestamp() - time.time())
        except Exception:
            return None


# Hadoop KMS reports an unknown key as a 500 RemoteException with one of these messages.
_KMS_NO_KEY = re.compile(r"does not exist|can't find metadata|key not found", re.IGNORECASE)


def _server_message(body: Any) -> Tuple[Optional[str], Any]:
    """Extract the human message from Ranger's error bodies.

    Admin returns ``VXResponse`` (``msgDesc`` + ``messageList``), PDP returns
    ``{code, message}``, KMS returns Hadoop's ``RemoteException``.
    """
    if not isinstance(body, dict):
        return None, None
    remote = body.get("RemoteException")
    if isinstance(remote, dict):
        body = remote
    msg = body.get("msgDesc") or body.get("message") or body.get("error")
    return (str(msg) if msg else None), body.get("messageList")


def _has_message_name(fields: Any, name: str) -> bool:
    return isinstance(fields, list) and any(isinstance(f, dict) and f.get("name") == name for f in fields)


def map_error(resp: httpx.Response, method: str, path: str, server: str, request_id: str) -> E.RangerException:
    text = mask_body(resp.text) or ""
    try:
        body = resp.json()
    except Exception:
        body = None
    msg, fields = _server_message(body)
    status = resp.status_code
    kw = dict(status=status, method=method, path=path, server=server, request_id=request_id, body=text,
              server_message=msg)
    summary = msg or (text[:200] if text else resp.reason_phrase)
    if status == 401:
        return E.RangerAuthenticationException(f"Authentication failed: {summary}", **kw)
    if status == 403:
        return E.RangerAccessDeniedException(f"Access denied: {summary}", **kw)
    remote = isinstance(body, dict) and isinstance(body.get("RemoteException"), dict)
    if (status == 404 or (status == 400 and _has_message_name(fields, "DATA_NOT_FOUND"))
            or (status == 500 and remote and msg and _KMS_NO_KEY.search(msg))):
        # Ranger's xusers API answers an unknown user/group name with 400 + messageList[DATA_NOT_FOUND];
        # KMS answers an unknown key with 500 RemoteException "Key x does not exist".
        return E.RangerNotFoundException(f"Not found: {summary}", **kw)
    if status == 409 or (status == 400 and msg and _DUPLICATE.search(msg)):
        return E.RangerConflictException(f"Conflict: {summary}", **kw)
    if status == 400:
        return E.RangerValidationException(f"Validation failed: {summary}", fields=fields, **kw)
    if status >= 500:
        return E.RangerServerException(f"Server error: {summary}", **kw)
    return E.RangerException(f"Unexpected status {status}: {summary}", **kw)


def decode(resp: httpx.Response) -> Any:
    if resp.status_code == 204 or not resp.content:
        return None
    ctype = resp.headers.get("Content-Type", "")
    if "json" in ctype or resp.content[:1] in (b"{", b"["):
        return resp.json()
    return resp.text


def ssl_context(cfg: TransportConfig) -> Union[bool, ssl.SSLContext]:
    """Build the TLS context: system CAs, a PEM bundle/dir, or a JKS-free truststore export."""
    if isinstance(cfg.verify, ssl.SSLContext):
        ctx = cfg.verify
    elif cfg.verify is False:
        return False
    elif isinstance(cfg.verify, str):
        if os.path.isdir(cfg.verify):
            ctx = ssl.create_default_context(capath=cfg.verify)
        else:
            ctx = ssl.create_default_context(cafile=cfg.verify)
    else:
        ctx = ssl.create_default_context()
    if cfg.cert:
        certfile, keyfile = (cfg.cert, None) if isinstance(cfg.cert, str) else cfg.cert
        ctx.load_cert_chain(certfile, keyfile)
    if not cfg.verify_hostname:
        ctx.check_hostname = False
    return ctx


def client_kwargs(cfg: TransportConfig) -> Dict[str, Any]:
    return {"verify": ssl_context(cfg), "timeout": cfg.timeouts.httpx(), "follow_redirects": False,
            "trust_env": cfg.trust_env}
