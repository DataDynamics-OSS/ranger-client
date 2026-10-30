"""OpenTelemetry tracing for SDK calls (``pip install 'ranger-sdk[otel]'``).

One CLIENT span per HTTP attempt (retries and failovers are separate spans), a child of the caller's
current span, named after the HTTP method and carrying the stable HTTP semantic-convention attributes plus
``ranger.server`` and ``ranger.request_id``. The span's context is sent with the request (W3C
``traceparent`` with the default propagator)::

    from ranger_sdk import RangerAdmin
    from ranger_sdk.otel import OpenTelemetryInterceptor

    admin = RangerAdmin("https://ranger:6182", interceptors=[OpenTelemetryInterceptor()])

Do not combine with ``opentelemetry-instrumentation-httpx``, which would trace the same requests twice.
"""

from __future__ import annotations

from typing import Callable, Dict, Optional
from urllib.parse import urlsplit

try:
    from opentelemetry import context as otel_context
    from opentelemetry import propagate, trace
    from opentelemetry.propagators.textmap import TextMapPropagator
    from opentelemetry.trace import SpanKind, Status, StatusCode
except ImportError as exc:  # pragma: no cover
    raise ImportError("ranger_sdk.otel needs opentelemetry-api: pip install 'ranger-sdk[otel]'") from exc

from ranger_sdk import __version__

INSTRUMENTATION_NAME = "ranger_sdk"


class _Attempt:
    def __init__(self, span: trace.Span) -> None:
        self._span = span

    def on_response(self, status: int) -> None:
        self._span.set_attribute("http.response.status_code", status)
        if status >= 400:
            self._span.set_attribute("error.type", str(status))
            self._span.set_status(Status(StatusCode.ERROR))
        self._span.end()

    def on_error(self, error: BaseException) -> None:
        self._span.set_attribute("error.type", f"{type(error).__module__}.{type(error).__qualname__}")
        self._span.record_exception(error)
        self._span.set_status(Status(StatusCode.ERROR))
        self._span.end()


class OpenTelemetryInterceptor:
    """:class:`~ranger_sdk._transport.RequestInterceptor` that traces each attempt.

    ``tracer_provider`` and ``propagator`` default to the globally configured ones.
    """

    def __init__(self, tracer_provider: Optional[trace.TracerProvider] = None,
                 propagator: Optional[TextMapPropagator] = None) -> None:
        self._tracer = trace.get_tracer(INSTRUMENTATION_NAME, __version__, tracer_provider=tracer_provider)
        self._propagator = propagator

    def begin(self, server: str, method: str, url: str, attempt: int, request_id: str,
              set_header: Callable[[str, str], None]) -> _Attempt:
        parts = urlsplit(url)
        attrs: Dict[str, object] = {
            "http.request.method": method,
            "url.full": url,
            "server.address": parts.hostname or "",
            "server.port": parts.port or (443 if parts.scheme == "https" else 80),
            "ranger.server": server,
            "ranger.request_id": request_id,
        }
        if attempt > 1:
            attrs["http.request.resend_count"] = attempt - 1
        parent = otel_context.get_current()
        span = self._tracer.start_span(method, context=parent, kind=SpanKind.CLIENT, attributes=attrs)
        carrier: Dict[str, str] = {}
        ctx = trace.set_span_in_context(span, parent)
        if self._propagator is not None:
            self._propagator.inject(carrier, context=ctx)
        else:
            propagate.inject(carrier, context=ctx)
        for k, v in carrier.items():
            set_header(k, v)
        return _Attempt(span)
