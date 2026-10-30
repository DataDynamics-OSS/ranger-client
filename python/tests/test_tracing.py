"""Runs spec/contract/tracing.yaml (shared with the Java TracingContractTest) against ranger_sdk.otel."""

import os
from urllib.parse import urlsplit

import httpx
import pytest
import respx
import yaml

pytest.importorskip("opentelemetry.sdk")

from opentelemetry.sdk.trace import TracerProvider  # noqa: E402
from opentelemetry.sdk.trace.export import SimpleSpanProcessor  # noqa: E402
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter  # noqa: E402
from opentelemetry.trace import SpanKind, StatusCode  # noqa: E402
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator  # noqa: E402

from conftest import ADMIN, KMS, PDP, SPEC_DIR  # noqa: E402
from ranger_sdk import AsyncRangerAdmin, RangerAdmin, RetryPolicy, errors  # noqa: E402
from ranger_sdk.otel import OpenTelemetryInterceptor  # noqa: E402
from test_contract import _invoke  # noqa: E402

with open(os.path.join(SPEC_DIR, "contract", "tracing.yaml"), encoding="utf-8") as fh:
    SCENARIOS = yaml.safe_load(fh)["scenarios"]


@pytest.fixture
def otel():
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    interceptor = OpenTelemetryInterceptor(tracer_provider=provider, propagator=TraceContextTextMapPropagator())
    return provider, exporter, interceptor


def _check(exp, span, where):
    assert span.kind == SpanKind.CLIENT, where
    assert span.name == exp["name"], where
    status = "ERROR" if span.status.status_code == StatusCode.ERROR else "UNSET"
    assert status == exp["status"], where
    attrs = dict(span.attributes)
    for k, v in (exp.get("attributes") or {}).items():
        assert k in attrs, f"{where} missing {k} in {attrs}"
        if v != "*":
            assert str(attrs[k]) == str(v), f"{where}.{k}: {attrs[k]!r} != {v!r}"
    for k in exp.get("absent") or []:
        assert k not in attrs, f"{where} must not have {k}: {attrs}"
    if "url_path" in exp:
        assert urlsplit(attrs["url.full"]).path == exp["url_path"], where


@pytest.mark.parametrize("sc", SCENARIOS, ids=[s["name"] for s in SCENARIOS])
def test_scenario(sc, otel):
    _, exporter, interceptor = otel
    base = PDP if sc["op"].startswith("pdp.") else KMS if sc["op"].startswith("kms.") else ADMIN
    responses = []
    for r in sc["responses"]:
        if r.get("error") == "connect":
            responses.append(httpx.ConnectError("connection refused"))
        else:
            responses.append(httpx.Response(r["status"], json=r.get("body")) if "body" in r else httpx.Response(r["status"]))
    retry = RetryPolicy(max_attempts=sc.get("max_attempts", 3), base_delay=0, jitter=False)
    with respx.mock(assert_all_called=False) as mock:
        route = mock.route(host=urlsplit(base).hostname).mock(side_effect=responses)
        try:
            _invoke(sc["op"], sc["args"], retry=retry, interceptors=[interceptor])
        except errors.RangerException:
            pass  # error scenarios: only the spans matter here
        spans = exporter.get_finished_spans()
        assert len(spans) == len(sc["expect_spans"]), [s.name for s in spans]
        for i, (exp, span) in enumerate(zip(sc["expect_spans"], spans)):
            _check(exp, span, f"span[{i}]")
            req = route.calls[i].request
            assert format(span.context.span_id, "016x") in req.headers["traceparent"]
            assert span.attributes["ranger.request_id"] == req.headers["X-Request-Id"]


def test_attempt_spans_are_children_of_the_current_span(otel):
    provider, exporter, interceptor = otel
    with respx.mock() as mock:
        mock.route(host="ranger1").mock(side_effect=[httpx.Response(503), httpx.Response(200, json={"id": 1})])
        admin = RangerAdmin(ADMIN, retry=RetryPolicy(max_attempts=3, base_delay=0, jitter=False), interceptors=[interceptor])
        with provider.get_tracer("app").start_as_current_span("sync-policies") as parent:
            admin.policies.get(1)
    client = [s for s in exporter.get_finished_spans() if s.kind == SpanKind.CLIENT]
    assert len(client) == 2
    for s in client:
        assert s.context.trace_id == parent.get_span_context().trace_id
        assert s.parent.span_id == parent.get_span_context().span_id


async def test_async_client_keeps_the_task_context(otel):
    provider, exporter, interceptor = otel
    with respx.mock() as mock:
        mock.route(host="ranger1").mock(return_value=httpx.Response(200, json={"id": 1}))
        async with AsyncRangerAdmin(ADMIN, interceptors=[interceptor]) as admin:
            with provider.get_tracer("app").start_as_current_span("job") as parent:
                await admin.policies.get(1)
    (span,) = [s for s in exporter.get_finished_spans() if s.kind == SpanKind.CLIENT]
    assert span.parent.span_id == parent.get_span_context().span_id


def test_broken_interceptor_does_not_break_calls():
    class Broken:
        def begin(self, *a):
            raise RuntimeError("boom")

    with respx.mock() as mock:
        mock.route(host="ranger1").mock(return_value=httpx.Response(200, json={"id": 1}))
        assert RangerAdmin(ADMIN, interceptors=[Broken()]).policies.get(1).id == 1


def test_cancelled_attempt_still_ends_its_span(otel):
    _, exporter, interceptor = otel
    with respx.mock(assert_all_called=False) as mock:
        def interrupted(request):
            raise KeyboardInterrupt

        mock.route(host="ranger1").mock(side_effect=interrupted)
        with pytest.raises(KeyboardInterrupt):
            RangerAdmin(ADMIN, interceptors=[interceptor]).policies.get(1)
    (span,) = exporter.get_finished_spans()
    assert span.status.status_code == StatusCode.ERROR
    assert span.attributes["error.type"] == "builtins.KeyboardInterrupt"

