"""Exception hierarchy shared (by name) with the Java SDK."""

from __future__ import annotations

from typing import Any, Optional

_BODY_EXCERPT = 2000


class RangerException(Exception):
    """Base class for every error raised by the SDK.

    Carries the request method, path, server URL, request id and an excerpt of
    the response body (already masked) so that failures can be diagnosed from a
    log line alone.
    """

    def __init__(
        self,
        message: str,
        *,
        status: Optional[int] = None,
        method: Optional[str] = None,
        path: Optional[str] = None,
        server: Optional[str] = None,
        request_id: Optional[str] = None,
        body: Optional[str] = None,
        server_message: Optional[str] = None,
    ) -> None:
        self.message = message
        self.status = status
        self.method = method
        self.path = path
        self.server = server
        self.request_id = request_id
        self.body = body[:_BODY_EXCERPT] if body else body
        self.server_message = server_message
        super().__init__(self._render())

    def _render(self) -> str:
        parts = [self.message]
        if self.method and self.path:
            parts.append(f"[{self.method} {self.path}]")
        if self.status is not None:
            parts.append(f"status={self.status}")
        if self.server:
            parts.append(f"server={self.server}")
        if self.request_id:
            parts.append(f"requestId={self.request_id}")
        return " ".join(parts)


class RangerTransportException(RangerException):
    """No server could be reached (connection refused, timeout, TLS failure)."""


class RangerAuthenticationException(RangerException):
    """401: authentication failed, even after one credential refresh."""


class RangerAccessDeniedException(RangerException):
    """403: the caller lacks permission (or delegation is not allowed on PDP)."""


class RangerNotFoundException(RangerException):
    """404: the target object does not exist."""


class RangerConflictException(RangerException):
    """409, a duplicate-name 400, or a client-side version mismatch."""


class RangerValidationException(RangerException):
    """400: the server rejected the payload."""

    def __init__(self, message: str, *, fields: Optional[Any] = None, **kw: Any) -> None:
        self.fields = fields
        super().__init__(message, **kw)


class RangerServerException(RangerException):
    """5xx after retries were exhausted."""


class UnsupportedFeatureException(RangerException):
    """The connected server version does not provide the requested API."""

    def __init__(self, feature: str, detail: str = "") -> None:
        self.feature = feature
        super().__init__(f"Feature {feature} is not supported by the server{': ' + detail if detail else ''}")


__all__ = [
    "RangerException",
    "RangerTransportException",
    "RangerAuthenticationException",
    "RangerAccessDeniedException",
    "RangerNotFoundException",
    "RangerConflictException",
    "RangerValidationException",
    "RangerServerException",
    "UnsupportedFeatureException",
]
