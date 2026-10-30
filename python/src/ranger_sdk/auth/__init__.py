"""Authentication providers.

Every provider implements :class:`AuthProvider`: it decorates outgoing requests
(headers and/or query parameters) and is asked to refresh once when the server
answers 401.
"""

from __future__ import annotations

import base64
import json
import threading
import time
from typing import Callable, Dict, Optional, Tuple, Union

from ranger_sdk.auth._base import AuthProvider
from ranger_sdk.auth._kerberos import Kerberos

SecretSource = Union[str, Callable[[], str]]
TokenSource = Union[str, Callable[[], Union[str, Tuple[str, float]]]]


def _resolve(secret: SecretSource) -> str:
    return secret() if callable(secret) else secret


class NoAuth(AuthProvider):
    pass


class Basic(AuthProvider):
    """HTTP Basic. ``password`` may be a callable so it is never stored in plain text."""

    def __init__(self, username: str, password: SecretSource) -> None:
        self.username = username
        self._password = password

    def headers(self, host: str) -> Dict[str, str]:
        raw = f"{self.username}:{_resolve(self._password)}".encode()
        return {"Authorization": "Basic " + base64.b64encode(raw).decode()}

    def refresh(self) -> bool:
        # a callable password may rotate; a literal one will not
        return callable(self._password)

    def describe(self) -> str:
        return f"Basic(username={self.username!r})"


def _jwt_exp(token: str) -> Optional[float]:
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        exp = json.loads(base64.urlsafe_b64decode(payload)).get("exp")
        return float(exp) if exp is not None else None
    except Exception:
        return None


class Jwt(AuthProvider):
    """Bearer token.

    ``token`` is either a literal token or a supplier returning a token (or a
    ``(token, expires_at_epoch)`` tuple). Tokens are refreshed
    ``refresh_skew`` seconds (default 60) before they expire; the expiry is read
    from the tuple or, failing that, from the token's ``exp`` claim.
    """

    servers = ("admin", "kms", "pdp")

    def __init__(self, token: TokenSource, refresh_skew: float = 60.0) -> None:
        self._source = token
        self._skew = refresh_skew
        self._token: Optional[str] = None
        self._expires: Optional[float] = None
        self._lock = threading.Lock()

    def _fetch(self) -> None:
        value = self._source() if callable(self._source) else self._source
        if isinstance(value, tuple):
            self._token, self._expires = value[0], float(value[1])
        else:
            self._token, self._expires = value, _jwt_exp(value)

    def _current(self) -> str:
        with self._lock:
            stale = self._expires is not None and time.time() >= self._expires - self._skew
            if self._token is None or (stale and callable(self._source)):
                self._fetch()
            assert self._token is not None
            return self._token

    def headers(self, host: str) -> Dict[str, str]:
        return {"Authorization": "Bearer " + self._current()}

    def refresh(self) -> bool:
        if not callable(self._source):
            return False
        with self._lock:
            self._fetch()
        return True


class TrustedHeader(AuthProvider):
    """Caller identity passed in a header set by a trusted proxy (PDP only)."""

    servers = ("pdp",)

    def __init__(self, user: str, header: str = "X-Forwarded-User") -> None:
        self.user = user
        self.header = header

    def headers(self, host: str) -> Dict[str, str]:
        return {self.header: self.user}

    def describe(self) -> str:
        return f"TrustedHeader({self.header}={self.user})"


class HadoopSimple(AuthProvider):
    """Hadoop simple auth (``?user.name=``), used by Ranger KMS without Kerberos."""

    servers = ("kms", "pdp")

    def __init__(self, user: str) -> None:
        self.user = user

    def query_params(self) -> Dict[str, str]:
        return {"user.name": self.user}

    def describe(self) -> str:
        return f"HadoopSimple(user={self.user})"


__all__ = [
    "AuthProvider",
    "NoAuth",
    "Basic",
    "Jwt",
    "TrustedHeader",
    "HadoopSimple",
    "Kerberos",
]
